package com.damou.tigertouch.drivers

import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.COLOR_UNKNOWN
import com.damou.tigertouch.model.PV_LABEL_EXT
import com.damou.tigertouch.model.PV_MAX_UNITS
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * FlashForge (Adventurer 5M family, Creator 5), over its HTTP interface on
 * port 8898 (reference: Tiger Studio's flashforge/PROTOCOL.md). Port of the
 * firmware's drivers/drv_flashforge.c.
 *
 * Seen on a real machine: an AD5X, idle (2026-10-14) - state, nozzle and bed.
 * A print, pause, resume, stop and the light have not been run.
 *
 * There is no push: POST /detail with the serial number and the check code in
 * the JSON body answers the whole state, and is repeated every two seconds by
 * a coroutine of its own. The legacy TCP port (8899) is not used, as on the
 * firmware.
 */
object FlashForgeDriver : PrinterDriver {
    override val brand: String = "flashforge"

    override fun support(p: AccountPrinter): DrvKind =
        if (p.ip.isNotEmpty() && p.sn.isNotEmpty() && !p.cloud) DrvKind.LAN else DrvKind.NONE

    override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink = FfLink(p, env)
}

private const val POLL_MS = 2000L
private const val ONLINE_WINDOW_NS = 10_000_000_000L
private const val REQ_TIMEOUT_MS = 4000L

private class FfSlot {
    var present = false
    var color = COLOR_UNKNOWN
    var material = ""
}

private class FfLink(p: AccountPrinter, env: DriverEnv) : PrinterLink {
    private val lock = Any()
    private val job: Job = SupervisorJob(env.scope.coroutineContext[Job])
    private val scope = CoroutineScope(env.scope.coroutineContext + job)

    /** host:port, 8898 unless the address names one. */
    private val hp: String = Addr.withPort(p.ip, 8898)
    /** The serial travels with its "SN" prefix, whatever the account stores. */
    private val sn: String = if (p.sn.startsWith("SN")) p.sn else "SN${p.sn}"
    private val code: String = p.cc

    private val http: OkHttpClient = env.lanHttp.newBuilder()
        .connectTimeout(REQ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(REQ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(REQ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile private var inFlight: Call? = null
    @Volatile private var stopped = false

    // ---- guarded by lock ----
    private var lastOkNs: Long? = null
    /** 0 ok, 1 refused (serial or code), -1 no answer. */
    private var codeErr = 0
    private var status = ""
    /** 0..100 */
    private var progress = 0f
    private var layer = 0
    private var totalLayer = 0
    private var leftS = 0
    private var file = ""
    private var nozzle = 0f
    private var nozzleTarget = 0f
    private var bed = 0f
    private var chamber = 0f
    /*
     * A tool-changer (Creator 5 Pro) reports one reading per nozzle in
     * nozzleTemps[]; a dual-extruder model, leftTemp and rightTemp.
     */
    private var nzCount = 0
    private val nzTemp = FloatArray(4)
    private val nzTarget = FloatArray(4)
    private var station = false
    private var currentSlot = 0
    private val slot = Array(4) { FfSlot() }
    private var extPresent = false
    private var extColor = COLOR_UNKNOWN
    private var extMaterial = ""
    /** detail.lightStatus: 1 "open", 0 "close", -1 not said. */
    private var light = -1
    private val view = PrinterView(name = p.name, brand = "flashforge")

    init {
        scope.launch(Dispatchers.IO) { pollLoop() }
    }

    /** The firmware writes these bodies with printf; the two values are escaped here so a quote cannot break them. */
    private fun creds(): String = "\"serialNumber\":\"${jsonEsc(sn)}\",\"checkCode\":\"${jsonEsc(code)}\""

    /** POST [body] to [path]: the status code and the answer, -1 when the printer did not answer. */
    private fun post(client: OkHttpClient, path: String, body: String, track: Boolean): Pair<Int, String> =
        try {
            val req = Request.Builder()
                .url("http://$hp$path")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(req)
            if (track) inFlight = call
            call.execute().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: Exception) {
            -1 to ""
        } finally {
            if (track) inFlight = null
        }

    private suspend fun pollLoop() {
        val body = "{${creds()}}"
        while (scope.isActive && !stopped) {
            val (st, reply) = post(http, "/detail", body, true)
            var verdict = -1
            if (st == 200) {
                val root = parseObject(reply)
                if (root != null) {
                    val c = root.numOrNull("code")
                    val detail = root.optJSONObject("detail")
                    if (c != null && c.toInt() == 0 && detail != null) {
                        applyDetail(detail)
                        verdict = 0
                    } else {
                        verdict = 1 // "SN is different" / "access code is different"
                    }
                }
            }
            synchronized(lock) {
                codeErr = verdict
                if (verdict == 0) lastOkNs = System.nanoTime()
            }
            delay(POLL_MS)
        }
    }

    private fun applyDetail(d: JSONObject) = synchronized(lock) {
        status = d.str("status")
        val ls = d.str("lightStatus")
        if (ls.isNotEmpty()) light = if (ls.equals("open", ignoreCase = true)) 1 else 0

        val pp = d.numOrNull("printProgress")
        if (pp != null) {
            // 0..100 on some firmwares, 0..1 on others.
            progress = if (pp <= 1.0) (pp * 100.0).toFloat() else pp.toFloat()
        }
        layer = d.numOrNull("printLayer")?.toInt() ?: 0
        totalLayer = d.numOrNull("targetPrintLayer")?.toInt() ?: 0
        leftS = d.numOrNull("estimatedTime")?.toInt() ?: 0

        var fn = d.str("printFileName")
        if (fn.isEmpty()) fn = d.str("fileName")
        val dot = fn.lastIndexOf('.')
        file = if (dot >= 0 && (fn.substring(dot).equals(".gx", true) || fn.substring(dot).equals(".gcode", true))) {
            fn.substring(0, dot)
        } else {
            fn
        }

        nozzle = d.numOrNull("rightTemp")?.toFloat() ?: 0f
        if (nozzle <= 0) nozzle = d.numOrNull("leftTemp")?.toFloat() ?: 0f
        // Set points, when the model gives them (the Creator 5 Pro does).
        nozzleTarget = 0f
        val tg = d.numOrNull("rightTargetTemp") ?: d.numOrNull("leftTargetTemp")
        if (tg != null) nozzleTarget = tg.toFloat()

        nzCount = 0
        val nts = d.optJSONArray("nozzleTemps")
        val ngs = d.optJSONArray("nozzleTargetTemps")
        if (nts != null && nts.length() > 1) {
            val n = minOf(nts.length(), 4)
            for (i in 0 until n) {
                val a = nts.opt(i)
                val g = ngs?.opt(i)
                nzTemp[i] = if (a is Number) a.toFloat() else 0f
                nzTarget[i] = if (g is Number) g.toFloat() else 0f
            }
            nzCount = n
        } else {
            val cnt = d.numOrNull("nozzleCnt")
            val lt = d.numOrNull("leftTemp")
            val rt = d.numOrNull("rightTemp")
            if (cnt != null && cnt.toInt() == 2 && lt != null && rt != null) {
                nzTemp[0] = lt.toFloat()
                nzTemp[1] = rt.toFloat()
                nzTarget[0] = d.numOrNull("leftTargetTemp")?.toFloat() ?: 0f
                nzTarget[1] = d.numOrNull("rightTargetTemp")?.toFloat() ?: 0f
                nzCount = 2
            }
        }

        bed = d.numOrNull("platTemp")?.toFloat() ?: 0f
        chamber = d.numOrNull("chamberTemp")?.toFloat() ?: 0f

        val ind = d.optJSONObject("indepMatlInfo")
        val em = ind?.str("materialName") ?: ""
        extPresent = em.isNotEmpty()
        extMaterial = em
        extColor = colorFromHex(ind?.str("materialColor") ?: "")

        station = truthy(d.opt("hasMatlStation"))
        val ms = d.optJSONObject("matlStationInfo")
        if (station && ms != null) {
            currentSlot = ms.numOrNull("currentSlot")?.toInt() ?: 0
            // cJSON_ArrayForEach walks an object's members as well as an array's items.
            val items: List<Any?> = when (val slots = ms.opt("slotInfos")) {
                is JSONArray -> List(slots.length()) { slots.opt(it) }
                is JSONObject -> slots.keys().asSequence().map { slots.opt(it) }.toList()
                else -> emptyList()
            }
            for (s in items) {
                if (s !is JSONObject) continue
                val i = s.numOrNull("slotId")?.toInt()?.minus(1) ?: -1 // 1-based
                if (i < 0 || i > 3) continue
                slot[i].present = truthy(s.opt("hasFilament"))
                slot[i].material = s.str("materialName")
                slot[i].color = colorFromHex(s.str("materialColor"))
            }
        }
    }

    /*
     * Pause, resume, stop: POST /control with jobCtl_cmd pause / continue /
     * cancel, the form the recent models use. Written from Tiger Studio's
     * protocol notes; this driver has not been run against a real printer, so
     * the command is not verified.
     *
     * Blocks the caller until the printer answers (up to its 4 s timeout), as
     * the firmware does; the request runs on Dispatchers.IO, but this should be
     * called from a background coroutine.
     */
    override fun control(cmd: PvControl): Boolean {
        if (stopped) return false
        val body = when (cmd) {
            // lightControl_cmd, "open" / "close": checked against a slicer capture
            // in Tiger Studio's notes (section 13.6).
            PvControl.LIGHT_ON, PvControl.LIGHT_OFF ->
                "{${creds()},\"payload\":{\"cmd\":\"lightControl_cmd\"," +
                    "\"args\":{\"status\":\"${if (cmd == PvControl.LIGHT_ON) "open" else "close"}\"}}}"
            else -> {
                val action = when (cmd) {
                    PvControl.PAUSE -> "pause"
                    PvControl.RESUME -> "continue"
                    else -> "cancel"
                }
                "{${creds()},\"payload\":{\"cmd\":\"jobCtl_cmd\"," +
                    "\"args\":{\"jobID\":\"\",\"action\":\"$action\"}}}"
            }
        }
        val st = try {
            runBlocking(Dispatchers.IO) { post(http, "/control", body, false).first }
        } catch (e: Exception) {
            -1
        }
        return st == 200
    }

    override fun snapshot(): PrinterView = synchronized(lock) {
        val v = view
        // What the firmware's link manager does before each snapshot.
        v.notice = ""
        v.noticeError = false

        val last = lastOkNs
        v.online = last != null && System.nanoTime() - last < ONLINE_WINDOW_NS
        v.canControl = v.online
        v.canLight = v.online
        v.lightOn = light == 1
        if (codeErr == 1) {
            v.notice = Tr.t("Wrong email or password.", "E-mail ou mot de passe incorrect.")
            v.noticeError = true
        }

        v.state = when (status) {
            "printing", "preparing", "prepare", "busy", "heating" -> PvState.PRINTING
            "paused", "pause", "pausing" -> PvState.PAUSED
            "complete" -> PvState.DONE
            else -> PvState.IDLE
        }
        val pct = (progress + 0.5f).toInt()
        v.progress = if (v.state == PvState.IDLE) 0 else minOf(pct, 100)
        v.layer = layer
        v.totalLayer = totalLayer
        v.leftS = if (v.state == PvState.PRINTING || v.state == PvState.PAUSED) leftS else 0
        v.nozzle = nozzle
        v.nozzleTarget = nozzleTarget
        v.nozzleCount = 0
        v.nozzleActive = -1
        if (nzCount >= 2) {
            // The head's current slot, 1-based, is the nozzle in use; a dual-extruder
            // model that heats only one says which by its set point.
            var active = -1
            if (station && currentSlot >= 1 && currentSlot <= nzCount) {
                active = currentSlot - 1
            } else {
                for (i in 0 until nzCount) {
                    if (nzTarget[i] > 0 && (active < 0 || nzTarget[i] > nzTarget[active])) active = i
                }
            }
            v.nozzleCount = nzCount
            v.nozzleActive = active
            for (i in 0 until nzCount) {
                val nz = v.nozzles[i]
                nz.temp = nzTemp[i]
                nz.target = nzTarget[i]
                nz.label = if (nzCount == 2 && !station) {
                    if (i == 0) Tr.t("Left", "Gauche") else Tr.t("Right", "Droite")
                } else {
                    Tr.t("Nozzle ${i + 1}", "Buse ${i + 1}")
                }
            }
            if (active >= 0) {
                v.nozzle = nzTemp[active]
                v.nozzleTarget = nzTarget[active]
            }
        }
        v.bed = bed
        v.bedTarget = 0f
        v.chamber = chamber
        v.file = file

        for (u in v.unit) u.clear()
        var n = 0
        if (extPresent) {
            val u = v.unit[n++]
            u.present = true
            u.slots = 1
            u.label = PV_LABEL_EXT
            u.slot[0].present = true
            u.slot[0].color = extColor
            u.slot[0].percent = -1
            u.slot[0].material = extMaterial
        }
        if (station && n < PV_MAX_UNITS) {
            val u = v.unit[n++]
            u.present = true
            u.slots = 4
            u.label = "Station"
            for (i in 0 until 4) {
                val d = u.slot[i]
                d.present = slot[i].present
                d.color = slot[i].color
                d.percent = -1
                d.selected = currentSlot == i + 1
                d.material = slot[i].material
            }
        }
        v.copyDeep()
    }

    override fun stop() {
        stopped = true
        try {
            inFlight?.cancel()
        } catch (_: Exception) {
        }
        try {
            job.cancel()
        } catch (_: Exception) {
        }
    }
}

/** cJSON_IsTrue, or a non-zero number: how FlashForge writes its flags. */
private fun truthy(v: Any?): Boolean = v == true || (v is Number && v.toDouble().toInt() != 0)

/** A string for a JSON body written by hand. */
private fun jsonEsc(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s) {
        when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}
