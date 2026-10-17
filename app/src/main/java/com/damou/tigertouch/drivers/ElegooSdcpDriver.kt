package com.damou.tigertouch.drivers

import android.os.SystemClock
import android.util.Log
import com.damou.tigertouch.model.AccountPrinter
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/*
 * Elegoo Centauri Carbon 1, through SDCP v3: a plain WebSocket on port 3030
 * carrying JSON, no authentication (reference: Tiger Studio's elegoo/sdcp.js,
 * and the protocol report written from a CC1 on firmware V1.4.49). Port of the
 * firmware's drivers/drv_elegoo_sdcp.c.
 *
 * The CC2 of the same brand speaks MQTT and has its own driver (ElegooDriver.kt);
 * what tells them apart is the `protocol: "sdcp"` Studio writes with the printer.
 *
 * Every message needs the printer's MainboardID. It comes with the account when
 * Studio found the printer by discovery; when it does not, it is asked the way
 * Studio asks: the text "M99999" to UDP 3000, answered with a JSON that holds
 * it. That exchange runs on its own coroutine (one datagram a few seconds
 * apart, the answer waited for a second at a time), so a printer that is off
 * simply stays "not answering" instead of holding anything up.
 *
 * The printer pushes its status by itself every couple of seconds; one is also
 * asked for every few seconds so that a dead connection is noticed (a send on
 * it fails and the client reconnects). WebSocket pings are not sent by the
 * library here: OkHttp drops a connection whose ping goes unanswered, and only
 * the plain-text "ping"/"pong" is documented for this printer.
 *
 * A read-only session, seen by a third party on firmware V1.4.49, covered
 * discovery, the unauthenticated connection and commands 0 (status) and 1
 * (attributes). Pause (129), resume (131) and stop (130) are sent on a touch
 * (control); their numbers come from the OpenCentauri API notes, not from a
 * CC1 session. This driver has NOT been run against a real CC1 yet.
 */
object ElegooSdcpDriver : PrinterDriver {
    override val brand: String = "elegoo"

    override fun support(p: AccountPrinter): DrvKind =
        if (p.ip.isNotEmpty() && !p.cloud && p.proto == "sdcp") DrvKind.LAN else DrvKind.NONE

    override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink = SdcpLink(p, env, wsClient(env))

    /** For the WebSocket: no read timeout (the socket stays open), and no library pings (above). */
    @Volatile private var ws: OkHttpClient? = null

    private fun wsClient(env: DriverEnv): OkHttpClient =
        ws ?: synchronized(this) {
            ws ?: env.lanHttp.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .build()
                .also { ws = it }
        }
}

private const val TAG = "sdcp"

private const val WS_PORT = 3030
private const val DISCOVERY_PORT = 3000
private const val MAX_FRAME = 16 * 1024
private const val PROBE_EVERY_MS = 3_000L
private const val ASK_EVERY_MS = 5_000L
private const val PING_EVERY_MS = 10_000L
/** The printer pushes about every 2 s; this is several of them. */
private const val ONLINE_WINDOW_MS = 15_000L
/** esp_websocket_client's reconnect_timeout_ms. */
private const val RECONNECT_MS = 5_000L
private const val TICK_MS = 1_000L

private val rng = SecureRandom()
private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

private fun nowMs(): Long = SystemClock.elapsedRealtime()

/** inet_pton(AF_INET): a dotted IPv4 literal only, never a name to resolve. */
private fun ipv4Literal(host: String): InetAddress? {
    val m = IPV4.matchEntire(host) ?: return null
    if (m.groupValues.drop(1).any { it.toInt() > 255 }) return null
    return try {
        InetAddress.getByName(host) // a literal: no lookup
    } catch (e: Exception) {
        null
    }
}

private fun hex32(): String {
    val b = ByteArray(16)
    rng.nextBytes(b)
    val sb = StringBuilder(32)
    for (x in b) {
        val v = x.toInt() and 0xff
        sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0xf])
    }
    return sb.toString()
}

private class SdcpLink(p: AccountPrinter, env: DriverEnv, private val http: OkHttpClient) : PrinterLink {
    private val lock = Any()
    private val job: Job = SupervisorJob(env.scope.coroutineContext[Job])
    private val scope = CoroutineScope(env.scope.coroutineContext + job + Dispatchers.IO)

    private val ip: String = p.ip

    // Guarded by `lock`.
    private var board: String = p.boardId
    /** The open WebSocket, null while there is none. */
    private var socket: WebSocket? = null
    private var askAtMs = 0L
    private var pingAtMs = 0L
    private var lastMsgMs = 0L
    private var connected = false
    /** a status has been read since the connection */
    private var got = false

    // PrintInfo / CurrentStatus, as received
    /** CurrentStatus holds 1 */
    private var stPrinting = false
    /** 3: calibrating */
    private var stBusy = false
    /** PrintInfo.Status */
    private var sub = 0
    private var file = ""
    /** PrintInfo.Progress, -1 when absent */
    private var progress = -1
    private var layer = 0
    private var totalLayer = 0
    /** seconds */
    private var ticks = 0
    private var totalTicks = 0
    private var nozzle = 0f
    private var nozzleTarget = 0f
    private var bed = 0f
    private var bedTarget = 0f
    private var box = 0f

    @Volatile private var udp: DatagramSocket? = null

    private val view = PrinterView(name = p.name, brand = "elegoo")

    init {
        scope.launch {
            if (synchronized(lock) { board }.isEmpty()) discover()
            wsLoop()
        }
        scope.launch { tickLoop() }
    }

    // ---- Messages --------------------------------------------------------------

    private fun applyStatus(st: JSONObject) = synchronized(lock) {
        // CurrentStatus is an array on this firmware; a lone number is tolerated.
        val cs = st.opt("CurrentStatus")
        if (cs is JSONArray || cs is Number) {
            stPrinting = false
            stBusy = false
            if (cs is Number) {
                val n = cs.toDouble().toInt()
                stPrinting = n == 1
                stBusy = n == 3
            } else if (cs is JSONArray) {
                for (i in 0 until cs.length()) {
                    val e = cs.opt(i)
                    if (e is Number) {
                        val n = e.toDouble().toInt()
                        stPrinting = stPrinting || n == 1
                        stBusy = stBusy || n == 3
                    }
                }
            }
        }

        st.numOrNull("TempOfNozzle")?.let { nozzle = it.toFloat() }
        st.numOrNull("TempTargetNozzle")?.let { nozzleTarget = it.toFloat() }
        st.numOrNull("TempOfHotbed")?.let { bed = it.toFloat() }
        st.numOrNull("TempTargetHotbed")?.let { bedTarget = it.toFloat() }
        st.numOrNull("TempOfBox")?.let { box = it.toFloat() }

        st.optJSONObject("PrintInfo")?.let { pi ->
            pi.numOrNull("Status")?.let { sub = it.toInt() }
            pi.numOrNull("CurrentLayer")?.let { layer = it.toInt() }
            pi.numOrNull("TotalLayer")?.let { totalLayer = it.toInt() }
            pi.numOrNull("CurrentTicks")?.let { ticks = it.toInt() }
            pi.numOrNull("TotalTicks")?.let { totalTicks = it.toInt() }
            progress = pi.numOrNull("Progress")?.toInt() ?: -1
            val fn = pi.opt("Filename")
            if (fn is String) {
                var f = fn.substring(fn.lastIndexOf('/') + 1)
                val dot = f.lastIndexOf('.')
                if (dot >= 0 && f.substring(dot).equals(".gcode", ignoreCase = true)) f = f.substring(0, dot)
                file = f
            }
        }
        got = true
    }

    private fun handleFrame(text: String) {
        synchronized(lock) { lastMsgMs = nowMs() } // a "pong" is proof of life too
        if (text.length < 2 || text[0] != '{') return
        val root = parseObject(text) ?: return
        // sdcp/status/<id> carries "Status" at the top; the answer to a request is
        // sdcp/response/<id> and holds only an Ack.
        root.optJSONObject("Status")?.let { applyStatus(it) }
    }

    // ---- Requests --------------------------------------------------------------

    private fun request(cmd: Int, boardId: String): String =
        "{\"Id\":\"${hex32()}\",\"Data\":{\"Cmd\":$cmd,\"Data\":{},\"RequestID\":\"${hex32()}\"," +
            "\"MainboardID\":${JSONObject.quote(boardId)},\"TimeStamp\":${System.currentTimeMillis() / 1000}," +
            "\"From\":0},\"Topic\":${JSONObject.quote("sdcp/request/$boardId")}}"

    /** Cmd 0 = "send me your status". */
    private fun askStatus() {
        val (ws, b) = synchronized(lock) { (if (connected) socket else null) to board }
        ws?.send(request(0, b))
    }

    // ---- WebSocket -------------------------------------------------------------

    private inner class Listener(private val closed: CompletableDeferred<Unit>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "connected")
            synchronized(lock) {
                socket = webSocket
                connected = true
                askAtMs = 0L // ask at once
            }
            askStatus()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (synchronized(lock) { socket } !== webSocket) return
            // Text frames only, as the firmware's reassembly; an oversized one is dropped.
            if (text.isEmpty() || text.length > MAX_FRAME || text.toByteArray(Charsets.UTF_8).size > MAX_FRAME) return
            try {
                handleFrame(text)
            } catch (e: Exception) {
                Log.w(TAG, "frame not handled", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            down(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            down(webSocket)
        }

        private fun down(webSocket: WebSocket) {
            synchronized(lock) {
                if (socket === webSocket || socket == null) {
                    if (connected) Log.w(TAG, "disconnected")
                    socket = null
                    connected = false
                    got = false
                }
            }
            closed.complete(Unit)
        }
    }

    private suspend fun wsLoop() {
        val req = try {
            Request.Builder().url("ws://${Addr.withPort(ip, WS_PORT)}/websocket").build()
        } catch (e: Exception) {
            Log.w(TAG, "bad printer address")
            return
        }
        Log.i(TAG, "connecting to the printer")
        while (scope.isActive) {
            val closed = CompletableDeferred<Unit>()
            val ws = http.newWebSocket(req, Listener(closed))
            try {
                closed.await()
            } finally {
                if (!closed.isCompleted) ws.cancel()
            }
            delay(RECONNECT_MS)
        }
    }

    // ---- Discovery of the MainboardID ------------------------------------------

    private suspend fun discover() = withContext(Dispatchers.IO) {
        // A port in the address is the WebSocket's; discovery stays on its own.
        val to = ipv4Literal(Addr.hostOnly(ip))
        if (to == null) {
            // Not an address: nothing to ask, and nothing else to do (as the firmware).
            Log.w(TAG, "the address is not an IPv4 literal: no discovery")
            while (isActive) delay(60_000L)
            return@withContext
        }
        var probeAtMs = 0L
        val buf = ByteArray(1500)
        while (isActive) {
            val s = udp ?: try {
                DatagramSocket().also {
                    it.soTimeout = TICK_MS.toInt()
                    udp = it
                }
            } catch (e: Exception) {
                delay(TICK_MS)
                continue
            }
            val now = nowMs()
            if (now >= probeAtMs) {
                probeAtMs = now + PROBE_EVERY_MS
                try {
                    val q = "M99999".toByteArray(Charsets.US_ASCII)
                    s.send(DatagramPacket(q, q.size, to, DISCOVERY_PORT))
                } catch (e: Exception) {
                    // Unreachable for now: asked again on the next probe.
                }
            }
            // The answer to the last question, if it comes.
            val pkt = DatagramPacket(buf, buf.size)
            try {
                s.receive(pkt)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!isActive) break
                closeUdp()
                delay(TICK_MS)
                continue
            }
            if (pkt.length <= 0 || pkt.address != to) continue
            val r = parseObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
            val id = r?.optJSONObject("Data")?.opt("MainboardID")
            if (id is String && id.isNotEmpty()) {
                synchronized(lock) { board = id }
                closeUdp()
                return@withContext
            }
        }
        closeUdp()
    }

    private fun closeUdp() {
        val s = udp
        udp = null
        try {
            s?.close()
        } catch (_: Throwable) {
        }
    }

    // ---- Timers ----------------------------------------------------------------

    private suspend fun tickLoop() {
        while (scope.isActive) {
            delay(TICK_MS)
            val now = nowMs()
            var ask = false
            var ws: WebSocket? = null
            synchronized(lock) {
                if (connected && socket != null) {
                    ws = socket
                    if (now >= askAtMs) {
                        askAtMs = now + ASK_EVERY_MS
                        ask = true
                    }
                    if (now >= pingAtMs) {
                        pingAtMs = now + PING_EVERY_MS
                    } else {
                        ws = null
                    }
                }
            }
            if (ask) askStatus()
            ws?.send("ping")
        }
    }

    // ---- Link ------------------------------------------------------------------

    /*
     * Pause, resume, stop: SDCP commands 129 (pause), 131 (resume) and 130
     * (stop); the numbers come from the OpenCentauri API notes, which Studio
     * itself flags as not confirmed on a CC1. Written from Tiger Studio's
     * protocol notes; this driver has not been run against a real printer, so
     * the command is not verified. No light command: Studio's SDCP driver has none.
     */
    override fun control(cmd: PvControl): Boolean {
        val code = when (cmd) {
            PvControl.PAUSE -> 129
            PvControl.RESUME -> 131
            PvControl.STOP -> 130
            else -> return false
        }
        val (ws, b) = synchronized(lock) { (if (connected) socket else null) to board }
        if (ws == null || b.isEmpty()) return false
        return ws.send(request(code, b))
    }

    override fun snapshot(): PrinterView = synchronized(lock) {
        val now = nowMs()
        val v = view
        v.notice = ""
        v.noticeError = false
        v.online = connected && got && lastMsgMs != 0L && (now - lastMsgMs) < ONLINE_WINDOW_MS
        v.canControl = v.online

        // PrintInfo.Status: 5 pausing, 6 paused, 7 stopping, 8 stopped, 9 complete.
        // While idle the printer keeps the LAST job's totals with Status 8 and an
        // empty Filename, so "a job" is decided from CurrentStatus and the file,
        // never from the totals.
        var running = false
        if (stPrinting) {
            when (sub) {
                5, 6 -> {
                    v.state = PvState.PAUSED
                    running = true
                }
                7 -> v.state = PvState.IDLE // being stopped
                else -> {
                    v.state = PvState.PRINTING
                    running = true
                }
            }
        } else if (stBusy) {
            v.state = PvState.PRINTING // calibrating before a print
        } else if (file.isNotEmpty() && sub == 9) {
            v.state = PvState.DONE
        } else {
            v.state = PvState.IDLE
        }

        var pct = 0
        if (v.state == PvState.DONE) {
            pct = 100
        } else if (running) {
            pct = if (progress >= 0) progress else (if (totalLayer > 0) layer * 100 / totalLayer else 0)
        }
        v.progress = pct.coerceIn(0, 100)
        v.layer = if (running) layer else 0
        v.totalLayer = if (running) totalLayer else 0
        v.leftS = if (running && totalTicks > ticks) totalTicks - ticks else 0
        v.nozzle = nozzle
        v.nozzleTarget = nozzleTarget
        v.bed = bed
        v.bedTarget = bedTarget
        v.chamber = box
        v.file = if (running || v.state == PvState.DONE) file else ""

        // The Canvas, when there is one, does not expose its slots over SDCP.
        for (u in v.unit) u.clear()
        v.copyDeep()
    }

    override fun stop() {
        try {
            job.cancel()
        } catch (_: Throwable) {
        }
        closeUdp()
        try {
            synchronized(lock) { socket }?.cancel()
        } catch (_: Throwable) {
        }
    }
}
