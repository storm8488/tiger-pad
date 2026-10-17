package com.damou.tigertouch.drivers

import com.damou.tigertouch.model.AccountPrinter
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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * OctoPrint (a Marlin printer behind it): its REST API over HTTP, port 80 on
 * an OctoPi (or the port written in the address), with the API key the account
 * holds - Tiger Studio's OctoPrint plugin, read in its source
 * (renderer/printers/octoprint, core.js and PROTOCOL.md). The firmware's
 * drivers/drv_octoprint.c.
 *
 * Every two seconds: GET /api/connection (is the printer plugged into
 * OctoPrint?), /api/printer (state flags and temperatures; 409 when it is not)
 * and /api/job (file, progress, times). OctoPrint reports no layers, no light
 * and no filament, so this brand has none of them.
 *
 * Written from Studio's plugin, itself marked beta; run against Studio's
 * OctoPrint mock, not yet a real printer.
 */
object OctoPrintDriver : PrinterDriver {
    override val brand: String = "octoprint"

    private const val PORT = 80

    override fun support(p: AccountPrinter): DrvKind =
        if (p.ip.isNotEmpty() && !p.cloud) DrvKind.LAN else DrvKind.NONE

    override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink {
        // "host" or "host:port"; port 80 when none.
        val link = OctoLink("http://" + Addr.withPort(p.ip, PORT), p.key, httpClient(env.lanHttp), env.scope)
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
private class OctoReply(val code: Int, val body: String)

/** The serial link's states that mean "no printer to talk to" (matched as prefixes, any case). */
private val LINK_DOWN = listOf("closed", "offline", "error", "detecting", "connecting", "opening")

private fun isFlag(flags: JSONObject?, name: String): Boolean = flags?.opt(name) == true

/** A unit as the firmware's memset leaves it: every field zero. */
private fun octoZeroUnit(): PvUnit = PvUnit(slot = Array(4) { PvSlot(color = 0, percent = 0) })

private class OctoLink(
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
    private var lastOkNs: Long? = null
    /** 0 ok, 401/403 refused, -1 no answer */
    private var httpErr = 0
    /** The printer is connected to OctoPrint. */
    private var linkUp = false
    private var error = false
    private var cancelling = false
    private var paused = false
    private var printing = false
    /** printer.state.text: shown on an error. */
    private var stateText = ""
    /** 0..100 */
    private var completion = 0f
    private var leftS = 0
    private var file = ""
    private var nTools = 0
    private val temp = FloatArray(4)
    private val target = FloatArray(4)
    private var bed = 0f
    private var bedTarget = 0f
    private var chamber = 0f

    private val view = PrinterView()

    fun begin() {
        // The firmware logs it and polls anyway; the card then says the key is missing.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                poll()
                delay(POLL_MS)
            }
        }
    }

    private fun poll() {
        // The serial link first: OctoPrint answers even with no printer on it.
        val conn = request("GET", "/api/connection", null)
        val st = conn.code
        var link = false
        if (st == 200) {
            val s = parseObject(conn.body)?.optJSONObject("current")?.str("state") ?: ""
            link = s.isNotEmpty()
            for (d in LINK_DOWN) link = link && !s.startsWith(d, ignoreCase = true)
        }
        val pr: JSONObject?
        val pst: Int
        if (st == 200) {
            val r = request("GET", "/api/printer", null)
            pst = r.code
            pr = if (pst == 200) parseObject(r.body) else null
        } else {
            pst = st
            pr = null
        }
        synchronized(lock) {
            httpErr = if (st == 200) 0 else st
            if (st == 200) lastOkNs = System.nanoTime()
            linkUp = link && pst != 409 // 409: no printer connected
            if (pr != null) readPrinter(pr)
        }

        if (st == 200) {
            val r = request("GET", "/api/job", null)
            if (r.code == 200) {
                val job = parseObject(r.body)
                if (job != null) synchronized(lock) { readJob(job) }
            }
        }
    }

    /** Blocking: called on Dispatchers.IO. X-Api-Key only when the account has a key. */
    private fun request(method: String, path: String, body: String?): OctoReply {
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
            return OctoReply(-1, "") // an address OkHttp cannot make a URL of
        }
        synchronized(calls) {
            if (stopped) return OctoReply(-1, "")
            calls.add(call)
        }
        return try {
            call.execute().use { r -> OctoReply(r.code, r.body?.string() ?: "") }
        } catch (e: Exception) {
            OctoReply(-1, "")
        } finally {
            synchronized(calls) { calls.remove(call) }
        }
    }

    /** Under `lock`. */
    private fun readPrinter(root: JSONObject) {
        val state = root.optJSONObject("state")
        val flags = state?.optJSONObject("flags")
        stateText = state?.str("text") ?: ""
        error = isFlag(flags, "error")
        cancelling = isFlag(flags, "cancelling")
        paused = isFlag(flags, "paused") || isFlag(flags, "pausing")
        printing = isFlag(flags, "printing")

        val t = root.optJSONObject("temperature")
        nTools = 0
        for (i in 0 until 4) {
            val tool = t?.optJSONObject("tool$i") ?: break
            tool.numOrNull("actual")?.let { temp[i] = it.toFloat() }
            tool.numOrNull("target")?.let { target[i] = it.toFloat() }
            nTools = i + 1
        }
        val bedO = t?.optJSONObject("bed")
        bedO?.numOrNull("actual")?.let { bed = it.toFloat() }
        bedO?.numOrNull("target")?.let { bedTarget = it.toFloat() }
        t?.optJSONObject("chamber")?.numOrNull("actual")?.let { chamber = it.toFloat() }
    }

    /** Under `lock`. */
    private fun readJob(root: JSONObject) {
        val prog = root.optJSONObject("progress")
        val job = root.optJSONObject("job")
        // Absent or null (OctoPrint sends null when it does not know) keeps these defaults.
        val c = prog?.numOrNull("completion")?.toFloat() ?: 0f
        val elapsed = prog?.numOrNull("printTime")?.toFloat() ?: 0f
        val left = prog?.numOrNull("printTimeLeft")?.toFloat() ?: -1f
        val est = job?.numOrNull("estimatedPrintTime")?.toFloat() ?: 0f
        completion = if (c < 0f) 0f else if (c > 100f) 100f else c
        // OctoPrint's own estimate, else the file's less the time printed, else
        // extrapolated from the progress - Studio's order.
        leftS = when {
            left >= 0f -> left.toInt()
            est > elapsed -> (est - elapsed).toInt()
            completion > 0f && completion < 100f -> (elapsed * (100 - completion) / completion).toInt()
            else -> 0
        }
        val f = job?.optJSONObject("file")
        val path = f?.str("path") ?: ""
        val name = if (path.isNotEmpty()) path else (f?.str("name") ?: "")
        var short = name.substring(name.lastIndexOf('/') + 1)
        val dot = short.lastIndexOf('.')
        if (dot >= 0) {
            val ext = short.substring(dot)
            if (ext.equals(".gcode", ignoreCase = true) || ext.equals(".gco", ignoreCase = true)) {
                short = short.substring(0, dot)
            }
        }
        file = short
    }

    // ---- PrinterLink ------------------------------------------------------------

    /**
     * Pause, resume, cancel: POST /api/job, as Studio sends them. No light:
     * OctoPrint has no light command. The request is sent in the background:
     * true once it is on its way.
     */
    override fun control(cmd: PvControl): Boolean {
        val body = when (cmd) {
            PvControl.PAUSE -> "{\"command\":\"pause\",\"action\":\"pause\"}"
            PvControl.RESUME -> "{\"command\":\"pause\",\"action\":\"resume\"}"
            PvControl.STOP -> "{\"command\":\"cancel\"}"
            PvControl.LIGHT_ON, PvControl.LIGHT_OFF -> return false
        }
        if (stopped) return false
        scope.launch(Dispatchers.IO) { request("POST", "/api/job", body) }
        return true
    }

    override fun snapshot(): PrinterView = synchronized(lock) {
        val v = view
        v.notice = ""
        v.noticeError = false
        val last = lastOkNs
        val reached = last != null && System.nanoTime() - last < ONLINE_WINDOW_NS
        v.online = reached && linkUp
        if (key.isEmpty() || httpErr == 401 || httpErr == 403) {
            v.notice = Tr.t(
                "The printer refused its API key: check it in Tiger Studio.",
                "L'imprimante refuse sa clé API : vérifie-la dans Tiger Studio.",
            )
            v.noticeError = true
        } else if (reached && !linkUp) {
            v.notice = Tr.t(
                "OctoPrint answers, but no printer is connected to it.",
                "OctoPrint répond, mais aucune imprimante n'y est connectée.",
            )
            v.noticeError = true
        } else if (v.online && error && stateText.isNotEmpty()) {
            v.notice = stateText // OctoPrint's own words
            v.noticeError = true
        }
        v.canControl = v.online
        v.canLight = false

        // Studio's order: error, cancelling, paused, printing, finished, idle.
        v.state = when {
            error -> PvState.ERROR
            cancelling -> PvState.IDLE
            paused -> PvState.PAUSED
            printing -> PvState.PRINTING
            completion >= 99.9f -> PvState.DONE
            else -> PvState.IDLE
        }
        v.progress = if (v.state == PvState.IDLE) 0 else (completion + 0.5f).toInt()
        v.layer = 0 // OctoPrint does not report layers
        v.totalLayer = 0
        v.leftS = if (v.state == PvState.PRINTING || v.state == PvState.PAUSED) leftS else 0
        v.file = file

        v.nozzle = temp[0]
        v.nozzleTarget = target[0]
        v.nozzleCount = 0
        v.nozzleActive = -1
        if (nTools >= 2) {
            v.nozzleCount = nTools
            for (i in 0 until nTools) {
                v.nozzles[i].temp = temp[i]
                v.nozzles[i].target = target[i]
                v.nozzles[i].label = "T$i"
            }
        }
        v.bed = bed
        v.bedTarget = bedTarget
        v.chamber = chamber
        for (i in 0 until PV_MAX_UNITS) v.unit[i] = octoZeroUnit() // no filament data
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
