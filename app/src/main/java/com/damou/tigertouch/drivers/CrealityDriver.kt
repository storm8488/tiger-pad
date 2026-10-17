package com.damou.tigertouch.drivers

import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.PV_LABEL_EXT
import com.damou.tigertouch.model.PV_MAX_UNITS
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvSlot
import com.damou.tigertouch.model.PvState
import com.damou.tigertouch.model.PvUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Live state of one Creality printer (K2 family, Ender-3 V4, K1...), read over
 * its local WebSocket on :9999: the firmware's drivers/k2_client.c and the
 * Creality part of drivers/drivers.c.
 *
 * What the printer does, measured on a K2:
 *  - no authentication, and no request is needed: it pushes a complete
 *    snapshot (about 80 fields) as soon as the socket opens, then only the
 *    fields that changed (temperatures every ~3 s);
 *  - it sends {"ModeCode":"heart_beat"} and expects the plain text "ok" back;
 *  - the filament boxes are not part of the snapshot: `boxsInfo` is asked for;
 *  - numbers often arrive as strings ("22.930000").
 *
 * OkHttp hands whole messages over, so the firmware's chunk reassembly
 * (ws_rx.c) has no counterpart here.
 */
object CrealityDriver : PrinterDriver {
    override val brand: String = "creality"

    private const val PORT = 9999

    override fun support(p: AccountPrinter): DrvKind =
        if (p.ip.isNotEmpty() && !p.cloud) DrvKind.LAN else DrvKind.NONE

    override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink {
        val link = CrealityLink("ws://" + Addr.withPort(p.ip, PORT), wsClient(env.lanHttp), env.scope)
        link.begin()
        return link
    }

    private var wsBase: OkHttpClient? = null
    private var ws: OkHttpClient? = null

    /**
     * One WebSocket client for every Creality link: no read timeout, so a quiet
     * printer is not cut, and the firmware's ping every 5 s (a printer that
     * vanished without closing is noticed: OkHttp fails the socket when a pong
     * has not come back by the next ping). 8 s to connect, as the firmware's
     * network timeout.
     */
    @Synchronized
    private fun wsClient(lan: OkHttpClient): OkHttpClient {
        val cur = ws
        if (cur != null && wsBase === lan) return cur
        val c = lan.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .build()
        ws = c
        wsBase = lan
        return c
    }
}

/** A unit as the firmware's memset leaves it: every field zero (black, 0 %), not the model's "unknown" defaults. */
private fun zeroUnit(): PvUnit = PvUnit(slot = Array(4) { PvSlot(color = 0, percent = 0) })

/**
 * cJSON_GetObjectItem, which the firmware uses, matches keys without regard to
 * case: "TotalLayer" also reads "totalLayer". An exact match is taken first.
 */
private fun JSONObject.ci(key: String): Any? {
    if (has(key)) return opt(key)
    val it = keys()
    while (it.hasNext()) {
        val k = it.next()
        if (k.equals(key, ignoreCase = true)) return opt(k)
    }
    return null
}

private val ATOF_PREFIX = Regex("^\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

/** C's atof: the leading number of the string, 0 when there is none ("12abc" is 12, "abc" is 0). */
private fun atof(s: String): Double {
    val m = ATOF_PREFIX.find(s) ?: return 0.0
    return m.value.trim().toDoubleOrNull() ?: 0.0
}

/** Creality sends many numbers as strings. A non-empty string always reads (as atof does). */
private fun asFloat(v: Any?): Float? = when (v) {
    is Number -> v.toFloat()
    is String -> if (v.isNotEmpty()) atof(v).toFloat() else null
    else -> null
}

/** Rounded half away from zero, as the firmware does. */
private fun asInt(v: Any?): Int? {
    val f = asFloat(v) ?: return null
    return (f + if (f >= 0f) 0.5f else -0.5f).toInt()
}

/** The array at [key] as a list, empty when it is not an array. */
private fun JSONObject.listAt(key: String): List<Any?> {
    val a = ci(key) as? JSONArray ?: return emptyList()
    return List(a.length()) { a.opt(it) }
}

/** A box's `type` when it is a JSON number (0 = CFS, 1 = the external spool). */
private fun boxType(box: Any?): Int? = ((box as? JSONObject)?.ci("type") as? Number)?.toInt()

private class CrealityLink(
    private val url: String,
    private val client: OkHttpClient,
    parent: CoroutineScope,
) : PrinterLink {
    private companion object {
        const val RECONNECT_MS = 5000L
        const val BOXS_REFRESH_NS = 30L * 1000 * 1000 * 1000
        /** `state`: 0 rest, 1 printing, 2 finished (Tiger Studio's notes), 5 paused (seen on a K2). */
        const val STATE_PAUSED = 5
        const val REQ_BOXS = "{\"method\":\"get\",\"params\":{\"boxsInfo\":1}}"
    }

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val lock = Any()

    /** The listener of the socket in use; a callback from an older socket is ignored. */
    @Volatile private var current: Listener? = null
    @Volatile private var stopped = false

    // Everything below is guarded by `lock`.
    private var socket: WebSocket? = null
    private var connected = false
    private var gotFrame = false
    private var lastBoxsReqNs: Long? = null

    private var nozzle = 0f
    private var nozzleTarget = 0f
    private var bed = 0f
    private var bedTarget = 0f
    private var chamber = 0f
    private var prState = 0
    private var progress = 0
    private var layer = 0
    private var totalLayer = 0
    private var leftS = 0
    /** The printer's own pause flag. */
    private var paused = false
    /** 0 when there is no fault. */
    private var errCode = 0
    /** `lightSw`: 1 is the light on. */
    private var lightOn = false
    /** Kept: the K2 zeroes the counters when a print ends. */
    private var lastLayer = 0
    private var lastTotal = 0
    private var file = ""
    private var unit: Array<PvUnit> = Array(PV_MAX_UNITS) { zeroUnit() }

    private val view = PrinterView()

    fun begin() {
        // Connect, wait for the socket to end, wait 5 s, again: the firmware's
        // client reconnects on its own with reconnect_timeout_ms = 5000.
        scope.launch {
            while (isActive) {
                val l = Listener()
                current = l
                var ws: WebSocket? = null
                try {
                    ws = client.newWebSocket(Request.Builder().url(url).build(), l)
                    synchronized(lock) { socket = ws }
                    l.closed.await()
                } catch (e: IllegalArgumentException) {
                    // An address OkHttp cannot make a URL of: try again later, as a refused connection.
                } finally {
                    ws?.cancel()
                    dropped(l)
                }
                delay(RECONNECT_MS)
            }
        }
        // The boxes are not pushed: ask again from time to time. The firmware
        // checks this each time the dashboard takes a snapshot (every second).
        scope.launch {
            while (isActive) {
                delay(1000)
                val due = synchronized(lock) {
                    val last = lastBoxsReqNs
                    connected && (last == null || System.nanoTime() - last > BOXS_REFRESH_NS)
                }
                if (due) requestBoxs()
            }
        }
    }

    /** The socket of [l] is gone: not connected, and no frame from it any more. The data is kept. */
    private fun dropped(l: Listener) {
        synchronized(lock) {
            if (current === l || current == null) {
                connected = false
                gotFrame = false
                socket = null
            }
        }
        l.closed.complete(Unit)
    }

    private fun requestBoxs() {
        val ws = synchronized(lock) { if (connected) socket else null } ?: return
        ws.send(REQ_BOXS)
        synchronized(lock) { lastBoxsReqNs = System.nanoTime() }
    }

    private inner class Listener : WebSocketListener() {
        val closed = CompletableDeferred<Unit>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (current !== this || stopped) return
                socket = webSocket
                connected = true
            }
            requestBoxs()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (current !== this) return
            handleFrame(webSocket, text)
        }

        // Binary frames are ignored, as the firmware takes text frames only.

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            dropped(this)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dropped(this)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dropped(this)
        }
    }

    // ---- Frame handling ---------------------------------------------------------

    private fun handleFrame(webSocket: WebSocket, text: String) {
        val root = parseObject(text) ?: return
        val mode = root.ci("ModeCode")
        if (mode is String && mode == "heart_beat") {
            // Plain text, not JSON; without it the printer drops the link.
            webSocket.send("ok")
        } else {
            val boxs = root.ci("boxsInfo")
            if (boxs is JSONObject) applyBoxs(boxs)
            applyStatus(root)
        }
    }

    private fun applyBoxs(boxs: JSONObject) {
        val units = Array(PV_MAX_UNITS) { zeroUnit() }
        val boxes = boxs.listAt("materialBoxs")

        // Every CFS box becomes a unit (type 0 = CFS, 1 = the external spool).
        val cfsTotal = boxes.count { boxType(it) == 0 }
        var n = 0
        for (box in boxes) {
            if (boxType(box) != 0 || n >= PV_MAX_UNITS) continue
            val u = units[n]
            u.present = true
            u.slots = 4
            u.label = if (cfsTotal > 1) "CFS ${n + 1}" else "CFS"
            for (m in (box as JSONObject).listAt("materials")) {
                val mo = m as? JSONObject ?: continue
                val id = asInt(mo.ci("id")) ?: continue
                if (id < 0 || id > 3) continue
                val st = asInt(mo.ci("state")) ?: 0
                val pct = asInt(mo.ci("percent")) ?: 0
                val sel = asInt(mo.ci("selected")) ?: 0
                val type = mo.ci("type")
                val color = mo.ci("color")
                val nm = mo.ci("name")
                val s = u.slot[id]
                s.present = st != 0 || (type is String && type.isNotEmpty())
                s.percent = pct.coerceIn(0, 100)
                s.selected = sel != 0
                if (type is String && type.isNotEmpty()) {
                    s.material = type
                } else if (nm is String) {
                    s.material = nm
                }
                s.color = colorFromHex(color as? String)
            }
            n++
        }
        // The external spool (type 1) is a unit of its own, after the boxes.
        if (n < PV_MAX_UNITS) {
            for (box in boxes) {
                if (boxType(box) != 1) continue
                val m = (box as JSONObject).listAt("materials").firstOrNull() ?: break
                val mo = m as? JSONObject ?: JSONObject()
                val u = units[n++]
                u.present = true
                u.slots = 1
                u.label = PV_LABEL_EXT
                val st = asInt(mo.ci("state")) ?: 0
                val sel = asInt(mo.ci("selected")) ?: 0
                val type = mo.ci("type")
                val color = mo.ci("color")
                val nm = mo.ci("name")
                val s = u.slot[0]
                // "no filament" is state 0; the colour of an empty holder is a
                // placeholder, so presence is the state alone here.
                s.present = st != 0
                s.selected = sel != 0
                if (s.present && type is String && type.isNotEmpty()) {
                    s.material = type
                } else if (s.present && nm is String) {
                    s.material = nm
                }
                s.color = colorFromHex(color as? String)
                break
            }
        }

        synchronized(lock) { unit = units }
    }

    private fun applyStatus(root: JSONObject) {
        synchronized(lock) {
            asFloat(root.ci("nozzleTemp"))?.let { nozzle = it }
            asFloat(root.ci("targetNozzleTemp"))?.let { nozzleTarget = it }
            asFloat(root.ci("bedTemp0"))?.let { bed = it }
            asFloat(root.ci("targetBedTemp0"))?.let { bedTarget = it }
            asFloat(root.ci("boxTemp"))?.let { chamber = it }
            asInt(root.ci("state"))?.let { prState = it }
            asInt(root.ci("printProgress"))?.let { progress = it }
            asInt(root.ci("layer"))?.let { layer = it }
            asInt(root.ci("TotalLayer"))?.let { totalLayer = it }
            (asInt(root.ci("pause")) ?: asInt(root.ci("isPaused")))?.let { paused = it != 0 }
            val er = root.ci("err")
            if (er is JSONObject) {
                asInt(er.ci("errcode"))?.let { errCode = it }
            } else {
                asInt(er)?.let { errCode = it }
            }
            asInt(root.ci("lightSw"))?.let { lightOn = it != 0 }
            if (layer > 0) lastLayer = layer
            if (totalLayer > 0) lastTotal = totalLayer
            asInt(root.ci("printLeftTime"))?.let { leftS = it }
            val fn = root.ci("printFileName")
            if (fn is String) {
                var name = fn.substring(fn.lastIndexOf('/') + 1)
                val dot = name.lastIndexOf('.')
                if (dot >= 0 && name.substring(dot).equals(".gcode", ignoreCase = true)) {
                    name = name.substring(0, dot)
                }
                file = name
            }
            gotFrame = true
        }
    }

    // ---- PrinterLink ------------------------------------------------------------

    /**
     * Pause, resume or stop the print: {"method":"set","params":{"pause":1|0}} and
     * {"stop":1}, the frames Tiger Studio sends and the printer answers; the light
     * is `lightSw`. false when not connected.
     */
    override fun control(cmd: PvControl): Boolean {
        val body = when (cmd) {
            PvControl.PAUSE -> "{\"method\":\"set\",\"params\":{\"pause\":1}}"
            PvControl.RESUME -> "{\"method\":\"set\",\"params\":{\"pause\":0}}"
            PvControl.STOP -> "{\"method\":\"set\",\"params\":{\"stop\":1}}"
            PvControl.LIGHT_ON -> "{\"method\":\"set\",\"params\":{\"lightSw\":1}}"
            PvControl.LIGHT_OFF -> "{\"method\":\"set\",\"params\":{\"lightSw\":0}}"
        }
        val ws = synchronized(lock) { if (connected && !stopped) socket else null } ?: return false
        return ws.send(body)
    }

    override fun snapshot(): PrinterView = synchronized(lock) {
        val v = view
        v.notice = ""
        v.noticeError = false
        v.online = connected && gotFrame
        v.canControl = v.online
        v.canLight = v.online
        v.lightOn = lightOn
        // `state` is 0 at rest, 1 while printing, 2 once finished, 5 while paused
        // (seen on a K2); a fault is reported by `err` (Tiger Studio's
        // creality/PROTOCOL.md, section 8).
        val finished = progress >= 100 || prState == 2
        v.state = when {
            errCode != 0 -> PvState.ERROR
            finished -> PvState.DONE
            // Paused is a `pause` / `isPaused` flag in Tiger Studio's notes, but the K2
            // was seen reporting it as `state` 5 (no flag at all) while paused.
            prState == 1 || prState == STATE_PAUSED || progress > 0 || leftS > 0 || layer > 0 ->
                if (paused || prState == STATE_PAUSED) PvState.PAUSED else PvState.PRINTING
            else -> PvState.IDLE
        }
        v.progress = when (v.state) {
            PvState.IDLE -> 0
            PvState.DONE -> 100
            else -> progress
        }
        v.layer = layer
        v.totalLayer = totalLayer
        if (v.state == PvState.DONE && v.layer == 0) {
            v.layer = lastLayer // the K2 zeroes the counters at the end
            v.totalLayer = lastTotal
        }
        v.leftS = if (v.state == PvState.PRINTING || v.state == PvState.PAUSED) leftS else 0
        v.nozzle = nozzle
        v.nozzleTarget = nozzleTarget
        v.bed = bed
        v.bedTarget = bedTarget
        v.chamber = chamber
        v.file = file
        for (i in 0 until PV_MAX_UNITS) v.unit[i] = unit[i].copy()
        v.copyDeep()
    }

    override fun stop() {
        stopped = true
        val ws = synchronized(lock) {
            connected = false
            gotFrame = false
            socket.also { socket = null }
        }
        current = null
        try {
            ws?.cancel()
        } catch (e: Exception) {
            // never throws
        }
        try {
            scope.cancel()
        } catch (e: Exception) {
            // never throws
        }
    }
}
