package com.damou.tigertouch.drivers

import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.COLOR_UNKNOWN
import com.damou.tigertouch.model.PV_MAX_UNITS
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvSlot
import com.damou.tigertouch.model.PvState
import com.damou.tigertouch.model.PvUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Klipper, through Moonraker: HTTP on port 7125 (or the port written in the
 * address), polled every two seconds - Tiger Studio's Klipper plugin, read in
 * its source (renderer/printers/klipper, core.js and PROTOCOL.md). The
 * firmware's drivers/drv_klipper.c.
 *
 * The objects a printer has are listed once (GET /printer/objects/list): its
 * extruders, a chamber sensor, a light (an LED chain or an output pin named
 * like one), a Happy Hare MMU or AFC lanes. Each poll then asks exactly those
 * (POST /printer/objects/query). The time left comes from the file's metadata
 * (estimated_time), asked once per file. An API key is sent only when the
 * account has one: most Moonraker installs trust the local network.
 *
 * A Snapmaker U1 is Moonraker too, but has a driver of its own for its
 * proprietary filament data; this one is for every other Klipper printer.
 * Written from Studio's plugin, which is itself marked beta; run against
 * Studio's Moonraker mock, not yet a real printer.
 */
object KlipperDriver : PrinterDriver {
    override val brand: String = "klipper"

    private const val PORT = 7125

    override fun support(p: AccountPrinter): DrvKind =
        if (p.ip.isNotEmpty() && !p.cloud) DrvKind.LAN else DrvKind.NONE

    override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink {
        // "host" or "host:port"; Moonraker's port when none is written.
        val link = KlipperLink("http://" + Addr.withPort(p.ip, PORT), p.key, httpClient(env.lanHttp), env.scope)
        link.begin()
        return link
    }

    private var httpBase: OkHttpClient? = null
    private var http: OkHttpClient? = null

    /** The firmware's requests: 6 s timeouts, redirects not followed. */
    @Synchronized
    private fun httpClient(lan: OkHttpClient): OkHttpClient {
        val cur = http
        if (cur != null && httpBase === lan) return cur
        val c = lan.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        http = c
        httpBase = lan
        return c
    }
}

/** Status and body of one request; status -1 when there was no answer. */
private class KlipReply(val code: Int, val body: String)

/** MMU gates or AFC lanes: four cards of four. */
private const val MAX_LANES = 16

private class KlipLane {
    var present = false
    var color = 0
    var material = ""
    var active = false
}

/** The object's name after its type: "led chamber" -> "chamber". */
private fun secondToken(obj: String): String {
    val sp = obj.indexOf(' ')
    return if (sp >= 0) obj.substring(sp + 1) else obj
}

/** C's atoi: the leading integer of the string, 0 when there is none. */
private fun klipAtoi(s: String): Int {
    var i = 0
    while (i < s.length && s[i].isWhitespace()) i++
    var neg = false
    if (i < s.length && (s[i] == '+' || s[i] == '-')) {
        neg = s[i] == '-'
        i++
    }
    var n = 0L
    while (i < s.length && s[i] in '0'..'9') {
        n = (n * 10 + (s[i] - '0')).coerceAtMost(Int.MAX_VALUE.toLong() + 1)
        i++
    }
    return (if (neg) -n else n).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

private const val HEX = "0123456789ABCDEF"

private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/** Happy Hare may name a colour ("red"): only hexadecimal is read, as in Studio. */
private fun klipColorOf(s: String): Int {
    val h = if (s.startsWith("#")) s.substring(1) else s
    if (h.length != 6 && h.length != 8) return COLOR_UNKNOWN
    if (!h.all { isHexDigit(it) }) return COLOR_UNKNOWN
    return colorFromHex(s)
}

private fun strings(vararg s: String): JSONArray {
    val a = JSONArray()
    for (x in s) a.put(x)
    return a
}

/** A unit as the firmware's memset leaves it: every field zero. */
private fun klipZeroUnit(): PvUnit = PvUnit(slot = Array(4) { PvSlot(color = 0, percent = 0) })

/** A file name for the screen: the last path element, ".gcode" dropped. */
private fun klipShortName(path: String): String {
    var name = path.substring(path.lastIndexOf('/') + 1)
    val dot = name.lastIndexOf('.')
    if (dot >= 0 && name.substring(dot).equals(".gcode", ignoreCase = true)) name = name.substring(0, dot)
    return name
}

private class KlipperLink(
    /** http://host:port */
    private val base: String,
    private val key: String,
    private val client: OkHttpClient,
    parent: CoroutineScope,
) : PrinterLink {
    private companion object {
        const val POLL_MS = 2000L
        const val ONLINE_WINDOW_NS = 10L * 1000 * 1000 * 1000
        val JSON = "application/json".toMediaType()
    }

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val lock = Any()
    @Volatile private var stopped = false
    private val calls = HashSet<Call>() // guarded by itself

    // Everything below is guarded by `lock`.

    // What the printer has, from the object list.
    private var detected = false
    private var nExt = 0
    private var chamber = ""
    /** The whole object name, "led chamber" / "output_pin caselight". */
    private var light = ""
    private var lightIsPin = false
    private var mmu = false
    /** "AFC_stepper" or "AFC_lane" */
    private var afcPrefix = ""
    private val afcLane = ArrayList<String>()
    /** The body of the poll, built from the above. */
    private var query = ""

    // What it says.
    private var lastOkNs: Long? = null
    /** 0 ok, 401/403 refused, -1 no answer */
    private var httpErr = 0
    /** Klippy is ready. */
    private var ready = false
    /** Its state_message when it is not. */
    private var notReady = ""
    /** standby printing paused complete cancelled error */
    private var pstate = "standby"
    /** print_stats.message: why, on an error. */
    private var message = ""
    /** 0..1 */
    private var progress = 0f
    private var filename = ""
    /** The file whose estimated_time is known. */
    private var metaFor = ""
    private var estS = 0f
    private var elapsedS = 0f
    private var layer = 0
    private var totalLayer = 0
    private val temp = FloatArray(4)
    private val target = FloatArray(4)
    private var activeExt = ""
    private var bed = 0f
    private var bedTarget = 0f
    private var chamberT = 0f
    private var lightOn = false
    private val lane = Array(MAX_LANES) { KlipLane() }
    private var nLanes = 0

    private val view = PrinterView()

    fun begin() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!detected) detected = detect()
                if (detected) {
                    val q = synchronized(lock) { query }
                    val r = request("POST", "/printer/objects/query", q)
                    val status = if (r.code == 200) {
                        parseObject(r.body)?.optJSONObject("result")?.optJSONObject("status")
                    } else {
                        null
                    }
                    val newFile = synchronized(lock) {
                        httpErr = if (r.code == 200) 0 else r.code
                        if (status != null) {
                            parseStatus(status)
                            lastOkNs = System.nanoTime()
                        }
                        filename.isNotEmpty() && filename != metaFor
                    }
                    if (r.code in 400..499 && r.code != 401 && r.code != 403) {
                        synchronized(lock) { detected = false } // Klippy restarted: its objects may have changed
                    }
                    if (newFile) fetchEstimate()
                }
                delay(POLL_MS)
            }
        }
    }

    // ---- Requests ---------------------------------------------------------------

    /** Blocking: called on Dispatchers.IO. X-Api-Key only when the account has a key. */
    private fun request(method: String, path: String, body: String?): KlipReply {
        val call: Call
        try {
            val b = Request.Builder().url(base + path)
            if (key.isNotEmpty()) b.header("X-Api-Key", key)
            when {
                body != null -> b.method(method, body.toByteArray().toRequestBody(JSON))
                method == "POST" -> b.post(ByteArray(0).toRequestBody(null))
                else -> b.get()
            }
            call = client.newCall(b.build())
        } catch (e: Exception) {
            return KlipReply(-1, "") // an address OkHttp cannot make a URL of
        }
        synchronized(calls) {
            if (stopped) return KlipReply(-1, "")
            calls.add(call)
        }
        return try {
            call.execute().use { r -> KlipReply(r.code, r.body?.string() ?: "") }
        } catch (e: Exception) {
            KlipReply(-1, "")
        } finally {
            synchronized(calls) { calls.remove(call) }
        }
    }

    /** The objects, once: what to ask for in every poll. */
    private fun detect(): Boolean {
        val r = request("GET", "/printer/objects/list", null)
        synchronized(lock) { httpErr = if (r.code == 200) 0 else r.code }
        if (r.code != 200) return false
        val objs = parseObject(r.body)?.optJSONObject("result")?.opt("objects") as? JSONArray ?: return false

        var nExt = 0
        var chamber = ""
        var light = ""
        var lightIsPin = false
        var mmu = false
        var afcPrefix = ""
        val afcLane = ArrayList<String>()
        var chamberAny = ""
        for (k in 0 until objs.length()) {
            val n = objs.opt(k) as? String ?: continue
            if (n.startsWith("extruder") && (n.length == 8 || n[8] in '0'..'9')) {
                // extruder, extruder1, extruder2...: the count of the highest seen
                val i = if (n.length > 8) klipAtoi(n.substring(8)) else 0
                if (i < 4 && i + 1 > nExt) nExt = i + 1
            } else if (n == "mmu") {
                mmu = true
            } else if ((n.startsWith("AFC_stepper ") || n.startsWith("AFC_lane ")) && afcLane.size < MAX_LANES) {
                if (afcPrefix.isEmpty()) afcPrefix = n.substring(0, n.indexOf(' '))
                afcLane.add(secondToken(n))
            } else if (n == "heater_generic chamber" || n == "temperature_sensor chamber") {
                if (chamber.isEmpty() || n.startsWith("heater_generic")) chamber = n
            } else if ((n.startsWith("heater_generic ") || n.startsWith("temperature_sensor ")) &&
                n.contains("chamber", ignoreCase = true)
            ) {
                if (chamberAny.isEmpty()) chamberAny = n
            } else if (light.isEmpty() && (n.startsWith("led ") || n.startsWith("neopixel ") ||
                    n.startsWith("dotstar ") || n.startsWith("pca9533 ") || n.startsWith("pca9632 "))
            ) {
                light = n
                lightIsPin = false
            } else if (light.isEmpty() && n.startsWith("output_pin ") &&
                (n.contains("light", ignoreCase = true) || n.contains("led", ignoreCase = true) ||
                    n.contains("lamp", ignoreCase = true))
            ) {
                light = n
                lightIsPin = true
            }
        }
        if (chamber.isEmpty()) chamber = chamberAny
        if (nExt == 0) nExt = 1

        // The poll's body: only what this printer has.
        val o = JSONObject()
        o.put("webhooks", strings("state", "state_message"))
        o.put("print_stats", strings("state", "filename", "print_duration", "info", "message"))
        o.put("display_status", strings("progress"))
        o.put("virtual_sdcard", strings("progress", "is_active"))
        o.put("heater_bed", strings("temperature", "target"))
        o.put("toolhead", strings("extruder"))
        for (i in 0 until nExt) o.put(if (i == 0) "extruder" else "extruder$i", strings("temperature", "target"))
        if (chamber.isNotEmpty()) o.put(chamber, strings("temperature", "target"))
        if (light.isNotEmpty()) o.put(light, strings(if (lightIsPin) "value" else "color_data"))
        if (mmu) {
            o.put("mmu", strings("num_gates", "gate", "gate_status", "gate_material", "gate_color"))
        } else if (afcLane.isNotEmpty()) {
            o.put("AFC", strings("current_lane"))
            for (l in afcLane) o.put("$afcPrefix $l", strings("material", "color", "load", "prep", "tool_loaded"))
        }
        val body = JSONObject().put("objects", o).toString()

        synchronized(lock) {
            this.nExt = nExt
            this.chamber = chamber
            this.light = light
            this.lightIsPin = lightIsPin
            this.mmu = mmu
            this.afcPrefix = afcPrefix
            this.afcLane.clear()
            this.afcLane.addAll(afcLane)
            this.query = body
        }
        return true
    }

    /** Under `lock`. */
    private fun parseStatus(s: JSONObject) {
        val wh = s.optJSONObject("webhooks")
        val wst = wh?.str("state") ?: ""
        ready = wst.isEmpty() || wst == "ready"
        notReady = wh?.str("state_message") ?: ""

        val ps = s.optJSONObject("print_stats")
        val st = ps?.str("state") ?: ""
        pstate = if (st.isNotEmpty()) st else "standby"
        filename = ps?.str("filename") ?: ""
        message = ps?.str("message") ?: ""
        ps?.numOrNull("print_duration")?.let { elapsedS = it.toFloat() }
        val info = ps?.optJSONObject("info")
        layer = 0
        totalLayer = 0
        info?.numOrNull("current_layer")?.let { layer = it.toInt() }
        info?.numOrNull("total_layer")?.let { totalLayer = it.toInt() }

        // The SD card's own progress while it prints, else the display's.
        val vs = s.optJSONObject("virtual_sdcard")
        var p = 0f
        if (vs?.opt("is_active") == true) {
            vs?.numOrNull("progress")?.let { p = it.toFloat() }
        } else {
            s.optJSONObject("display_status")?.numOrNull("progress")?.let { p = it.toFloat() }
        }
        progress = if (p < 0f) 0f else if (p > 1f) 1f else p

        for (i in 0 until nExt) {
            val e = s.optJSONObject(if (i == 0) "extruder" else "extruder$i")
            e?.numOrNull("temperature")?.let { temp[i] = it.toFloat() }
            e?.numOrNull("target")?.let { target[i] = it.toFloat() }
        }
        activeExt = s.optJSONObject("toolhead")?.str("extruder") ?: ""
        val bedO = s.optJSONObject("heater_bed")
        bedO?.numOrNull("temperature")?.let { bed = it.toFloat() }
        bedO?.numOrNull("target")?.let { bedTarget = it.toFloat() }
        if (chamber.isNotEmpty()) {
            s.optJSONObject(chamber)?.numOrNull("temperature")?.let { chamberT = it.toFloat() }
        }

        if (light.isNotEmpty()) {
            val l = s.optJSONObject(light)
            if (lightIsPin) {
                val v = l?.numOrNull("value") ?: 0.0
                lightOn = v > 0
            } else {
                // On when any channel of any LED in the chain is lit.
                var on = false
                val leds = l?.optJSONArray("color_data")
                if (leds != null) {
                    for (i in 0 until leds.length()) {
                        val led = leds.opt(i) as? JSONArray ?: continue
                        for (j in 0 until led.length()) {
                            val ch = led.opt(j)
                            on = on || (ch is Number && ch.toDouble() > 0)
                        }
                    }
                }
                lightOn = on
            }
        }

        if (mmu) {
            val m = s.optJSONObject("mmu")
            val status = m?.optJSONArray("gate_status")
            var n = m?.numOrNull("num_gates")?.toInt() ?: 0
            if (n <= 0) n = status?.length() ?: 0
            val gate = m?.numOrNull("gate")?.toInt() ?: -1
            val materials = m?.optJSONArray("gate_material")
            val colors = m?.optJSONArray("gate_color")
            nLanes = if (n > MAX_LANES) MAX_LANES else n
            for (g in 0 until nLanes) {
                val gs = status?.opt(g)
                val l = lane[g]
                l.present = gs is Number && gs.toInt() != 0 // 0 empty, -1 unknown, 1/2 loaded
                l.material = materials?.strAt(g) ?: ""
                l.color = klipColorOf(colors?.strAt(g) ?: "")
                l.active = g == gate
            }
        } else if (afcLane.isNotEmpty()) {
            val cur = s.optJSONObject("AFC")?.str("current_lane") ?: ""
            nLanes = afcLane.size
            for (i in afcLane.indices) {
                val o = s.optJSONObject("$afcPrefix ${afcLane[i]}")
                val l = lane[i]
                l.present = !(o?.opt("load") == false && o?.opt("prep") == false)
                l.material = o?.str("material") ?: ""
                l.color = klipColorOf(o?.str("color") ?: "")
                l.active = cur == afcLane[i] || o?.opt("tool_loaded") == true
            }
        }
        // Neither: nLanes is left as it was, as the firmware does.
    }

    /** The file's estimated time, once per file: Moonraker has no live "time left". */
    private fun fetchEstimate() {
        val file = synchronized(lock) { filename }
        val path = StringBuilder("/server/files/metadata?filename=")
        for (byte in file.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xff
            val c = b.toChar()
            if (b < 0x80 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~/")) {
                path.append(c)
            } else {
                path.append('%').append(HEX[b shr 4]).append(HEX[b and 0xf])
            }
        }
        var est = 0f
        val r = request("GET", path.toString(), null)
        if (r.code == 200) {
            parseObject(r.body)?.optJSONObject("result")?.numOrNull("estimated_time")?.let { est = it.toFloat() }
        }
        synchronized(lock) {
            estS = est
            metaFor = file
        }
    }

    // ---- PrinterLink ------------------------------------------------------------

    /**
     * Pause, resume, cancel: POST /printer/print/<action>. The light: a G-code
     * script, SET_PIN for an output pin, SET_LED for an LED chain, as Studio does.
     * The request is sent in the background: true once it is on its way.
     */
    override fun control(cmd: PvControl): Boolean {
        if (stopped) return false
        val path: String
        var body: String? = null
        when (cmd) {
            PvControl.LIGHT_ON, PvControl.LIGHT_OFF -> {
                val (l, pin) = synchronized(lock) { light to lightIsPin }
                if (l.isEmpty()) return false
                val on = if (cmd == PvControl.LIGHT_ON) 1 else 0
                val name = secondToken(l)
                val script = if (pin) {
                    "SET_PIN PIN=$name VALUE=$on"
                } else {
                    "SET_LED LED=$name RED=$on GREEN=$on BLUE=$on WHITE=$on"
                }
                path = "/printer/gcode/script"
                body = JSONObject().put("script", script).toString()
            }
            PvControl.PAUSE -> path = "/printer/print/pause"
            PvControl.RESUME -> path = "/printer/print/resume"
            PvControl.STOP -> path = "/printer/print/cancel"
        }
        scope.launch(Dispatchers.IO) { request("POST", path, body) }
        return true
    }

    override fun snapshot(): PrinterView = synchronized(lock) {
        val v = view
        v.notice = ""
        v.noticeError = false
        val last = lastOkNs
        v.online = last != null && System.nanoTime() - last < ONLINE_WINDOW_NS
        if (httpErr == 401 || httpErr == 403) {
            v.notice = Tr.t(
                "The printer refused its API key: check it in Tiger Studio.",
                "L'imprimante refuse sa clé API : vérifie-la dans Tiger Studio.",
            )
            v.noticeError = true
        } else if (v.online && !ready) {
            v.notice = Tr.t("Klipper is not ready:", "Klipper n'est pas prêt :") + " " + notReady
            v.noticeError = true
        } else if (v.online && pstate == "error" && message.isNotEmpty()) {
            v.notice = message // the printer's own words
            v.noticeError = true
        }
        v.canControl = v.online && ready
        v.canLight = v.online && ready && light.isNotEmpty()
        v.lightOn = lightOn

        v.state = when (pstate) {
            "printing" -> PvState.PRINTING
            "paused" -> PvState.PAUSED
            "complete" -> PvState.DONE
            "error" -> PvState.ERROR
            else -> PvState.IDLE // standby, cancelled
        }
        if (!ready) {
            v.state = PvState.IDLE // Klippy restarting: no job to speak of until it is back
        }
        v.progress = when (v.state) {
            PvState.DONE -> 100
            PvState.IDLE -> 0
            else -> (progress * 100 + 0.5f).toInt()
        }
        v.layer = if (ready) layer else 0
        v.totalLayer = if (ready) totalLayer else 0
        // Studio's rule: the estimate less the time printed, else extrapolated.
        var left = 0
        if (estS > elapsedS && metaFor == filename) {
            left = (estS - elapsedS).toInt()
        } else if (progress > 0f && progress < 1f) {
            left = (elapsedS * (1 - progress) / progress).toInt()
        }
        v.leftS = if (v.state == PvState.PRINTING || v.state == PvState.PAUSED) left else 0
        v.file = if (!ready) "" else klipShortName(filename)

        v.nozzle = temp[0]
        v.nozzleTarget = target[0]
        v.nozzleCount = 0
        v.nozzleActive = -1
        if (nExt >= 2) {
            v.nozzleCount = nExt
            val a = activeExt
            v.nozzleActive = when {
                a == "extruder" -> 0
                a.startsWith("extruder") -> klipAtoi(a.substring(8))
                else -> -1
            }
            for (i in 0 until nExt) {
                v.nozzles[i].temp = temp[i]
                v.nozzles[i].target = target[i]
                v.nozzles[i].label = "T$i"
            }
            if (v.nozzleActive in 0 until nExt) {
                v.nozzle = temp[v.nozzleActive]
                v.nozzleTarget = target[v.nozzleActive]
            }
        }
        v.bed = bed
        v.bedTarget = bedTarget
        v.chamber = if (chamber.isNotEmpty()) chamberT else 0f

        // The MMU's gates or the AFC lanes, four to a card.
        for (i in 0 until PV_MAX_UNITS) v.unit[i] = klipZeroUnit()
        var u = 0
        while (u * 4 < nLanes && u < PV_MAX_UNITS) {
            val pu = v.unit[u]
            pu.present = true
            pu.slots = if (nLanes - u * 4 >= 4) 4 else nLanes - u * 4
            val kind = if (mmu) "MMU" else "AFC"
            pu.label = if (nLanes > 4) "$kind ${u + 1}" else kind
            for (k in 0 until pu.slots) {
                val l = lane[u * 4 + k]
                val sl = pu.slot[k]
                sl.present = l.present
                sl.color = l.color
                sl.percent = -1
                sl.selected = l.active
                sl.material = l.material
            }
            u++
        }
        v.copyDeep()
    }

    override fun stop() {
        stopped = true
        val inFlight = synchronized(calls) { calls.toList() }
        for (c in inFlight) {
            try {
                c.cancel()
            } catch (e: Exception) {
                // never throws
            }
        }
        try {
            scope.cancel()
        } catch (e: Exception) {
            // never throws
        }
    }
}
