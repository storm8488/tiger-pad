package com.damou.tigertouch.drivers

import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.COLOR_UNKNOWN
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Snapmaker, through Moonraker: a WebSocket on port 7125 carrying JSON-RPC 2.0,
 * no authentication (reference: Tiger Studio's snapmaker/PROTOCOL.md). Port of
 * the firmware's drivers/drv_snapmaker.c.
 *
 * Seen on a real machine: a U1, idle (2026-10-14) - state, the four nozzles with
 * the active one, bed. Pause, resume, stop and the light have not been run.
 *
 * The connection is opened, the printer is asked to subscribe us to the few
 * objects the dashboard shows, and from then on it pushes the changes. The
 * state is kept merged here, field by field, because a push carries only what
 * moved.
 *
 * No Origin header is sent: Moonraker refuses a handshake whose origin is not
 * in its cors_domains, and OkHttp (like esp_websocket_client) sends none by
 * default.
 */
object SnapmakerDriver : PrinterDriver {
    override val brand: String = "snapmaker"

    private var wsBase: OkHttpClient? = null
    private var wsClient: OkHttpClient? = null

    /**
     * The WebSocket client, derived once from the LAN client: no read timeout
     * (the socket is quiet while nothing moves), a ping every 5 s and 8 s to
     * connect, as the firmware's ping_interval_sec / network_timeout_ms.
     */
    @Synchronized
    private fun ws(env: DriverEnv): OkHttpClient {
        val cached = wsClient
        if (cached != null && wsBase === env.lanHttp) return cached
        val c = env.lanHttp.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .build()
        wsBase = env.lanHttp
        wsClient = c
        return c
    }

    override fun support(p: AccountPrinter): DrvKind =
        if (p.ip.isNotEmpty() && !p.cloud) DrvKind.LAN else DrvKind.NONE

    override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink = SnapLink(p, env, ws(env))
}

/** A frame larger than this is dropped, as on the firmware. */
private const val MAX_FRAME = 48 * 1024
private const val SLOTS = 4
/** esp_websocket_client's reconnect_timeout_ms: the wait after a lost connection. */
private const val RECONNECT_MS = 10_000L

private val HEATERS = arrayOf("extruder", "extruder1", "extruder2", "extruder3")

private const val SUBSCRIBE =
    "{\"jsonrpc\":\"2.0\",\"method\":\"printer.objects.subscribe\",\"params\":{\"objects\":{" +
        "\"print_task_config\":null,\"print_stats\":null,\"virtual_sdcard\":null,\"display_status\":null," +
        "\"extruder\":[\"temperature\",\"target\"],\"extruder1\":[\"temperature\",\"target\"]," +
        "\"extruder2\":[\"temperature\",\"target\"],\"extruder3\":[\"temperature\",\"target\"]," +
        "\"toolhead\":[\"extruder\"],\"led cavity_led\":[\"color_data\"]," +
        "\"heater_bed\":[\"temperature\",\"target\"]}},\"id\":1}"

private const val CMD_PAUSE = "{\"jsonrpc\":\"2.0\",\"method\":\"printer.print.pause\",\"params\":{},\"id\":300}"
private const val CMD_RESUME = "{\"jsonrpc\":\"2.0\",\"method\":\"printer.print.resume\",\"params\":{},\"id\":301}"
private const val CMD_STOP = "{\"jsonrpc\":\"2.0\",\"method\":\"printer.print.cancel\",\"params\":{},\"id\":302}"
private const val CMD_LIGHT_ON =
    "{\"jsonrpc\":\"2.0\",\"method\":\"printer.gcode.script\",\"params\":{\"script\":" +
        "\"SET_LED LED=cavity_led RED=1 GREEN=1 BLUE=1 WHITE=1 SYNC=0\"},\"id\":303}"
private const val CMD_LIGHT_OFF =
    "{\"jsonrpc\":\"2.0\",\"method\":\"printer.gcode.script\",\"params\":{\"script\":" +
        "\"SET_LED LED=cavity_led RED=0 GREEN=0 BLUE=0 WHITE=0 SYNC=0\"},\"id\":304}"

private class SnapSlot {
    var present = false
    var color = COLOR_UNKNOWN
    var material = ""
}

private class SnapLink(p: AccountPrinter, env: DriverEnv, private val client: OkHttpClient) : PrinterLink {
    private val lock = Any()
    private val job: Job = SupervisorJob(env.scope.coroutineContext[Job])
    private val scope = CoroutineScope(env.scope.coroutineContext + job)
    private val url = "ws://${Addr.withPort(p.ip, 7125)}/websocket"

    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false

    // ---- guarded by lock ----
    private var connected = false
    private var got = false
    private var pstate = ""
    private var file = ""
    /** 0..1, -1 when not reported. */
    private var pDisplay = -1f
    private var pVsd = -1f
    /** print_duration, seconds. */
    private var duration = 0f
    private var layer = 0
    private var totalLayer = 0
    private val temp = FloatArray(SLOTS)
    private val target = FloatArray(SLOTS)
    /** The printer has this extruder (it was reported). */
    private val haveExt = BooleanArray(SLOTS)
    /** toolhead.extruder: the one at work, -1 when not said. */
    private var tool = -1
    private var bed = 0f
    private var bedTarget = 0f
    /** The cavity LED was reported: the printer has one to switch. */
    private var haveLed = false
    private var ledOn = false
    private val slot = Array(SLOTS) { SnapSlot() }
    private val view = PrinterView(name = p.name, brand = "snapmaker")

    init {
        scope.launch(Dispatchers.IO) { connectLoop() }
    }

    // ---- WebSocket ----------------------------------------------------------

    private suspend fun connectLoop() {
        // An address OkHttp cannot read: never online, as a firmware link that did not start.
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            return
        }
        while (scope.isActive && !stopped) {
            val closed = CompletableDeferred<Unit>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (stopped) return
                    synchronized(lock) { connected = true }
                    // The answer carries the full current state; pushes follow.
                    webSocket.send(SUBSCRIBE)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Text frames only; a binary frame is ignored, as on the firmware.
                    if (stopped || text.isEmpty() || text.length > MAX_FRAME) return
                    handleFrame(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    down()
                    closed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    down()
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    down()
                    closed.complete(Unit)
                }
            }
            val s = client.newWebSocket(request, listener)
            ws = s
            try {
                closed.await()
            } finally {
                ws = null
                if (stopped || !scope.isActive) s.cancel()
            }
            delay(RECONNECT_MS)
        }
    }

    private fun down() = synchronized(lock) {
        connected = false
        got = false
    }

    private fun handleFrame(text: String) {
        val root = parseObject(text) ?: return
        // An answer to the subscribe / query: result.status.
        val st = root.optJSONObject("result")?.optJSONObject("status")
        if (st != null) applyStatus(st)
        // A push: {"method":"notify_status_update","params":[{...}, eventtime]}.
        val m = root.opt("method")
        if (m == "notify_status_update" || m == "notify_status_changed") {
            val p0 = root.optJSONArray("params")?.optJSONObject(0)
            if (p0 != null) applyStatus(p0)
        }
    }

    // ---- State merge --------------------------------------------------------

    private fun applyConfig(cfg: JSONObject) {
        val col = cfg.optJSONArray("filament_color_rgba")
        val typ = cfg.optJSONArray("filament_type")
        val sub = cfg.optJSONArray("filament_sub_type")
        if (typ == null && col == null) return
        for (i in 0 until SLOTS) {
            val t = typ.strAtOrEmpty(i)
            val st = sub.strAtOrEmpty(i)
            slot[i].present = t.isNotEmpty()
            // The family and the variant, the way the printer's own screen writes
            // them: "PLA Silk", not "PLA". (The firmware also cuts it to its 11-byte
            // slot label; the screens here ellipsize instead.)
            slot[i].material = if (st.isNotEmpty()) "$t $st" else t
            // RRGGBBAA: the colour is the first six digits.
            slot[i].color = colorFromHex(col.strAtOrEmpty(i))
        }
    }

    private fun applyStatus(st: JSONObject) = synchronized(lock) {
        // The cavity LED: Klipper's `led` object, color_data [[R,G,B,W]] in 0..1.
        val led = st.optJSONObject("led cavity_led")
        if (led != null) {
            val px = led.optJSONArray("color_data")?.optJSONArray(0)
            if (px != null) {
                var any = false
                for (i in 0 until px.length()) {
                    val ch = px.opt(i)
                    any = any || (ch is Number && ch.toDouble() > 0.0)
                }
                haveLed = true
                ledOn = any
            }
        }

        val ps = st.optJSONObject("print_stats")
        if (ps != null) {
            val s = ps.opt("state")
            if (s is String) pstate = s
            val fn = ps.opt("filename")
            if (fn is String) {
                val base = fn.substring(fn.lastIndexOf('/') + 1)
                val dot = base.lastIndexOf('.')
                file = if (dot >= 0 && base.substring(dot).equals(".gcode", ignoreCase = true)) {
                    base.substring(0, dot)
                } else {
                    base
                }
            }
            ps.numOrNull("print_duration")?.let { duration = it.toFloat() }
            val info = ps.optJSONObject("info")
            if (info != null) {
                info.numOrNull("current_layer")?.let { layer = it.toInt() }
                info.numOrNull("total_layer")?.let { totalLayer = it.toInt() }
            }
        }

        st.optJSONObject("virtual_sdcard")?.numOrNull("progress")?.let { pVsd = it.toFloat() }
        st.optJSONObject("display_status")?.numOrNull("progress")?.let { pDisplay = it.toFloat() }

        for (i in 0 until SLOTS) {
            val h = st.optJSONObject(HEATERS[i]) ?: continue
            haveExt[i] = true
            h.numOrNull("temperature")?.let { temp[i] = it.toFloat() }
            h.numOrNull("target")?.let { target[i] = it.toFloat() }
        }
        // Klipper names the extruder in use: "extruder", "extruder1"...
        val th = st.optJSONObject("toolhead")
        if (th != null) {
            val name = th.opt("extruder")
            if (name is String) {
                if (name == "extruder") {
                    tool = 0
                } else if (name.startsWith("extruder") && name.length > 8 &&
                    name[8] >= '1' && name[8] < '0' + SLOTS
                ) {
                    tool = name[8] - '0'
                }
            }
        }
        val hb = st.optJSONObject("heater_bed")
        if (hb != null) {
            hb.numOrNull("temperature")?.let { bed = it.toFloat() }
            hb.numOrNull("target")?.let { bedTarget = it.toFloat() }
        }

        val cfg = st.optJSONObject("print_task_config")
        if (cfg != null) applyConfig(cfg)
        got = true
    }

    // ---- Driver -------------------------------------------------------------

    /*
     * Pause, resume, stop: Moonraker's printer.print.pause / resume / cancel over
     * the socket already open; the light: SET_LED on the cavity LED, as Tiger
     * Studio does it. Written from Tiger Studio's protocol notes; this driver
     * has not been run against a real printer, so the command is not verified.
     */
    override fun control(cmd: PvControl): Boolean {
        val s = ws ?: return false
        if (stopped || !synchronized(lock) { connected }) return false
        val body = when (cmd) {
            PvControl.PAUSE -> CMD_PAUSE
            PvControl.RESUME -> CMD_RESUME
            PvControl.STOP -> CMD_STOP
            PvControl.LIGHT_ON -> CMD_LIGHT_ON
            PvControl.LIGHT_OFF -> CMD_LIGHT_OFF
        }
        return try {
            s.send(body)
        } catch (e: Exception) {
            false
        }
    }

    override fun snapshot(): PrinterView = synchronized(lock) {
        val v = view
        // What the firmware's link manager does before each snapshot.
        v.notice = ""
        v.noticeError = false

        v.online = connected && got
        v.canControl = v.online
        v.canLight = v.online && haveLed
        v.lightOn = ledOn

        v.state = when (pstate) {
            "printing", "busy", "heating" -> PvState.PRINTING
            "paused" -> PvState.PAUSED
            "complete" -> PvState.DONE
            "error" -> PvState.ERROR
            else -> PvState.IDLE
        }

        // display_status first, then the file position; a value above 1 is a
        // percentage already.
        var p = if (pDisplay >= 0) pDisplay else if (pVsd >= 0) pVsd else 0f
        if (p > 1.0f) p /= 100.0f
        val pct = (p * 100.0f + 0.5f).toInt()
        v.progress = if (v.state == PvState.IDLE) 0 else minOf(pct, 100)
        v.layer = layer
        v.totalLayer = totalLayer
        // Moonraker reports no estimate in this feed: the time left is deduced
        // from the time spent and the share done.
        v.leftS = 0
        if ((v.state == PvState.PRINTING || v.state == PvState.PAUSED) && p > 0.01f && duration > 0) {
            v.leftS = (duration * (1.0f - p) / p).toInt()
        }

        // The hottest extruder is the one working.
        var e = 0
        for (i in 1 until SLOTS) if (target[i] > target[e]) e = i
        v.nozzle = temp[e]
        v.nozzleTarget = target[e]

        // More than one extruder reported (a U1 has four): one tile each, the one
        // at work ringed. Klipper's toolhead says which; failing that, the one
        // that is being heated.
        var count = 0
        for (i in 0 until SLOTS) if (haveExt[i]) count = i + 1
        var active = tool
        if (active < 0 || active >= count) active = if (target[e] > 0) e else -1
        v.nozzleCount = 0
        v.nozzleActive = -1
        if (count >= 2) {
            v.nozzleCount = count
            v.nozzleActive = active
            for (i in 0 until count) {
                val nz = v.nozzles[i]
                nz.temp = temp[i]
                nz.target = target[i]
                nz.label = if (count == 2) {
                    if (i == 0) Tr.t("Left", "Gauche") else Tr.t("Right", "Droite")
                } else {
                    Tr.t("Nozzle ${i + 1}", "Buse ${i + 1}")
                }
            }
            if (active >= 0) {
                v.nozzle = temp[active]
                v.nozzleTarget = target[active]
            }
        }
        v.bed = bed
        v.bedTarget = bedTarget
        v.chamber = 0f
        v.file = file

        for (u in v.unit) u.clear()
        if (slot.any { it.present }) {
            val u = v.unit[0]
            u.present = true
            u.slots = SLOTS
            u.label = "E1-E4"
            for (i in 0 until SLOTS) {
                val d = u.slot[i]
                d.present = slot[i].present
                d.color = slot[i].color
                d.percent = -1
                // On a tool-changer each head carries its own spool: the active
                // head's is the one in use.
                d.selected = v.nozzleCount >= 2 && i == v.nozzleActive
                d.material = slot[i].material
            }
        }
        v.copyDeep()
    }

    override fun stop() {
        stopped = true
        try {
            ws?.cancel()
        } catch (_: Exception) {
        }
        try {
            job.cancel()
        } catch (_: Exception) {
        }
    }
}

/** json_str_at on what may not be an array: "" then. */
private fun JSONArray?.strAtOrEmpty(i: Int): String = this?.strAt(i) ?: ""
