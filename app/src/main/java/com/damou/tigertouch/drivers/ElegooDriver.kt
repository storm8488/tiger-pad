package com.damou.tigertouch.drivers

import android.os.SystemClock
import android.util.Log
import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Elegoo Centauri Carbon 2, over its own MQTT broker: plain TCP on port 1883,
 * user "elegoo", the printer's access code as password (reference: Tiger
 * Studio's elegoo/PROTOCOL.md). Port of the firmware's drivers/drv_elegoo.c.
 *
 * The device registers itself under a client id, asks for a full snapshot
 * (method 1002) and the Canvas filament list (method 2005), then keeps
 * polling the snapshot: the continuous push (method 6000) only carries the
 * values that changed, so the poll is what keeps the state whole.
 *
 * The CC1 speaks another protocol (SDCP): see ElegooSdcpDriver.kt.
 */
object ElegooDriver : PrinterDriver {
    override val brand: String = "elegoo"

    override fun support(p: AccountPrinter): DrvKind {
        // A Centauri Carbon 1 speaks SDCP: that is ElegooSdcpDriver.
        if (p.proto == "sdcp") return DrvKind.NONE
        return if (p.ip.isNotEmpty() && p.sn.isNotEmpty() && !p.cloud) DrvKind.LAN else DrvKind.NONE
    }

    override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink = ElegooLink(p, env)
}

private const val TAG = "elegoo"

private const val MAX_MSG = 32 * 1024
private const val POLL_MS = 5_000L
private const val PING_MS = 10_000L
private const val CANVAS_MS = 30_000L
private const val ONLINE_WINDOW_MS = 30_000L
private const val REGISTER_WAIT_MS = 1_200L
/** esp-mqtt's reconnect_timeout_ms: a new attempt 5 s after a failure or a drop. */
private const val RECONNECT_MS = 5_000L
private const val TICK_MS = 1_000L

private val rng = SecureRandom()

private fun nowMs(): Long = SystemClock.elapsedRealtime()

/** The file name as the dashboard shows it: no folder, no ".gcode". */
private fun shortFileName(name: String): String {
    var f = name.substring(name.lastIndexOf('/') + 1)
    val dot = f.lastIndexOf('.')
    if (dot >= 0 && f.substring(dot).equals(".gcode", ignoreCase = true)) f = f.substring(0, dot)
    return f
}

private suspend fun MqttAsyncClient.connectAwait(o: MqttConnectOptions) = suspendCancellableCoroutine<Unit> { cont ->
    try {
        connect(o, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        exception ?: MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt()),
                    )
                }
            }
        })
    } catch (e: Exception) {
        if (cont.isActive) cont.resumeWithException(e)
    }
}

/** Off the caller's thread: Paho's disconnect waits for its own threads. Never throws. */
private fun closeQuietly(cl: MqttAsyncClient) {
    try {
        Thread {
            try {
                cl.disconnectForcibly(0, 1000)
            } catch (_: Throwable) {
            }
            try {
                cl.close()
            } catch (_: Throwable) {
            }
        }.start()
    } catch (_: Throwable) {
    }
}

private class ElegooTray {
    var present = false
    var color = 0
    var material = ""
}

private class ElegooLink(p: AccountPrinter, env: DriverEnv) : PrinterLink {
    private val lock = Any()
    private val job: Job = SupervisorJob(env.scope.coroutineContext[Job])
    private val scope = CoroutineScope(env.scope.coroutineContext + job + Dispatchers.IO)

    private val sn: String = p.sn
    private val cid: String = "TTG_${1000 + rng.nextInt(9000)}"
    private val tRegister = "elegoo/$sn/api_register"
    private val tRequest = "elegoo/$sn/$cid/api_request"
    private val tStatus = "elegoo/$sn/api_status"
    private val tResponse = "elegoo/$sn/$cid/api_response"
    private val tRegResp = "elegoo/$sn/${cid}_req/register_response"

    private val uri = "tcp://" + Addr.withPort(p.ip, 1883)
    private val brokerPass: String = p.cc.ifEmpty { "123456" }

    @Volatile private var client: MqttAsyncClient? = null

    // Everything below is guarded by `lock`.
    private var connected = false
    private var burstSent = false
    private var connectedAtMs = 0L
    private var lastMsgMs = 0L
    private var lastPollMs = 0L
    private var lastPingMs = 0L
    private var lastCanvasMs = 0L
    private var seq = 0L

    private var nozzle = 0f
    private var nozzleTarget = 0f
    private var bed = 0f
    private var bedTarget = 0f
    private var chamber = 0f
    private var status = 0
    private var subStatus = 0
    private var progress = 0
    private var layer = 0
    private var leftS = 0
    private var pstate = ""
    private var file = ""
    /** led.status of the live push */
    private var lightOn = false

    private var canvas = false
    private val tray = Array(4) { ElegooTray() }

    private val view = PrinterView(name = p.name, brand = "elegoo")

    init {
        scope.launch { connectLoop() }
        scope.launch { tickLoop() }
    }

    // ---- Requests --------------------------------------------------------------

    private fun publish(topic: String, body: String): Boolean {
        val cl = client ?: return false
        return try {
            cl.publish(topic, body.toByteArray(Charsets.UTF_8), 0, false)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sendMethod(method: Int, paramsJson: String?) {
        val id = synchronized(lock) { ++seq }
        publish(tRequest, "{\"id\":$id,\"method\":$method,\"params\":${paramsJson ?: "{}"}}")
    }

    private fun sendBurst() {
        synchronized(lock) {
            if (burstSent) return
            burstSent = true
        }
        // The first command a client sends is its hostname; the printer ignores
        // the rest until it has it.
        sendMethod(1043, "{\"hostname\":\"Tiger Pad\"}")
        sendMethod(1002, "{}")
        sendMethod(2005, "{}")
        synchronized(lock) {
            val now = nowMs()
            lastPollMs = now
            lastCanvasMs = now
        }
    }

    // ---- State merge -----------------------------------------------------------

    /** Under `lock`. */
    private fun applyCanvas(info: JSONObject) {
        val list = info.optJSONArray("canvas_list") ?: return
        val cv = list.optJSONObject(0) ?: return
        val conn = cv.opt("connected")
        canvas = !(conn is Number && conn.toDouble().toInt() == 0)
        val trays = cv.optJSONArray("tray_list")
        for (i in 0 until 4) {
            val t = trays?.optJSONObject(i) ?: continue
            val type = t.opt("filament_type")
            val col = t.opt("filament_color")
            val st = t.numOrNull("status")?.toInt() ?: 0
            tray[i].present = st != 0 || (type is String && type.isNotEmpty())
            tray[i].material = if (type is String) type else ""
            tray[i].color = colorFromHex(if (col is String) col else null)
        }
    }

    private fun applyResult(r: JSONObject) = synchronized(lock) {
        r.optJSONObject("extruder")?.let { ex ->
            ex.numOrNull("temperature")?.let { nozzle = it.toFloat() }
            ex.numOrNull("target")?.let { nozzleTarget = it.toFloat() }
        }
        r.optJSONObject("heater_bed")?.let { hb ->
            hb.numOrNull("temperature")?.let { bed = it.toFloat() }
            hb.numOrNull("target")?.let { bedTarget = it.toFloat() }
        }
        r.optJSONObject("ztemperature_sensor")?.let { zt ->
            zt.numOrNull("temperature")?.let { chamber = it.toFloat() }
        }
        r.optJSONObject("led")?.let { led ->
            led.numOrNull("status")?.let { lightOn = it != 0.0 }
        }
        r.optJSONObject("machine_status")?.let { ms ->
            ms.numOrNull("progress")?.let { progress = it.toInt() }
            ms.numOrNull("status")?.let { status = it.toInt() }
            ms.numOrNull("sub_status")?.let { subStatus = it.toInt() }
        }
        r.optJSONObject("print_status")?.let { ps ->
            val s = ps.opt("state")
            if (s is String) pstate = s
            val fn = ps.opt("filename")
            if (fn is String && fn.isNotEmpty()) file = shortFileName(fn)
            ps.numOrNull("current_layer")?.let { layer = it.toInt() }
            ps.numOrNull("remaining_time_sec")?.let { leftS = it.toInt() }
        }
        r.optJSONObject("canvas_info")?.let { applyCanvas(it) }
    }

    private fun handleMessage(text: String) {
        val root = parseObject(text) ?: return
        root.optJSONObject("result")?.let { applyResult(it) }
    }

    // ---- MQTT ------------------------------------------------------------------

    private fun options() = MqttConnectOptions().apply {
        setUserName("elegoo")
        setPassword(brokerPass.toCharArray())
        setKeepAliveInterval(60)
        setConnectionTimeout(10)
        setCleanSession(true)
        setAutomaticReconnect(false)
        setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1)
    }

    private fun onConnected(cl: MqttAsyncClient) {
        Log.i(TAG, "connected to the printer's broker")
        synchronized(lock) {
            connected = true
            burstSent = false
            connectedAtMs = nowMs()
        }
        try {
            cl.subscribe(tStatus, 0)
            cl.subscribe(tResponse, 0)
            cl.subscribe(tRegResp, 0)
            val reg = "{\"client_id\":\"$cid\",\"request_id\":\"${cid}_req\"}"
            cl.publish(tRegister, reg.toByteArray(Charsets.UTF_8), 0, false)
        } catch (e: Exception) {
            Log.w(TAG, "register failed: ${e.javaClass.simpleName}")
        }
    }

    private fun onMessage(topic: String, payload: ByteArray) {
        if (payload.isEmpty() || payload.size > MAX_MSG) return
        synchronized(lock) { lastMsgMs = nowMs() }
        if (topic.endsWith("register_response")) {
            sendBurst() // registered: the questions can start
        } else {
            handleMessage(String(payload, Charsets.UTF_8))
        }
    }

    private suspend fun connectLoop() {
        val cl = try {
            MqttAsyncClient(uri, cid, MemoryPersistence())
        } catch (e: Exception) {
            Log.w(TAG, "bad broker address: ${e.javaClass.simpleName}")
            return
        }
        client = cl
        try {
            while (scope.isActive) {
                val lost = CompletableDeferred<Unit>()
                cl.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        lost.complete(Unit)
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        // Nothing may escape this callback: Paho drops the session when it throws.
                        try {
                            onMessage(topic ?: "", message?.payload ?: return)
                        } catch (e: Exception) {
                            Log.w(TAG, "report not handled", e)
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                try {
                    cl.connectAwait(options())
                    onConnected(cl)
                    lost.await()
                    Log.w(TAG, "disconnected")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MqttException) {
                    Log.w(TAG, "connection failed (reason ${e.reasonCode})")
                } catch (e: Exception) {
                    Log.w(TAG, "connection failed: ${e.javaClass.simpleName}")
                }
                synchronized(lock) { connected = false }
                delay(RECONNECT_MS)
            }
        } finally {
            synchronized(lock) { connected = false }
            client = null
            closeQuietly(cl)
        }
    }

    /** What the firmware does in its snapshot, once a second: the questions on their timers. */
    private suspend fun tickLoop() {
        while (scope.isActive) {
            delay(TICK_MS)
            val now = nowMs()
            // The register answer is waited for a moment, then the questions go out
            // anyway: a firmware that does not answer it still answers the rest.
            val burst = synchronized(lock) { connected && !burstSent && now - connectedAtMs > REGISTER_WAIT_MS }
            if (burst) sendBurst()
            var poll = false
            var canvasAsk = false
            var ping = false
            synchronized(lock) {
                if (connected && burstSent) {
                    if (now - lastPollMs > POLL_MS) {
                        lastPollMs = now; poll = true
                    }
                    if (now - lastCanvasMs > CANVAS_MS) {
                        lastCanvasMs = now; canvasAsk = true
                    }
                    if (now - lastPingMs > PING_MS) {
                        lastPingMs = now; ping = true
                    }
                }
            }
            if (poll) sendMethod(1002, "{}")
            if (canvasAsk) sendMethod(2005, "{}")
            if (ping) publish(tRequest, "{\"type\":\"PING\"}")
        }
    }

    // ---- Link ------------------------------------------------------------------

    /*
     * Pause, resume, stop: methods 1021 (pause), 1023 (resume) and 1022 (cancel)
     * on the request topic. Written from Tiger Studio's protocol notes; this
     * driver has not been run against a real printer, so the command is not
     * verified.
     */
    override fun control(cmd: PvControl): Boolean {
        if (client == null || !synchronized(lock) { connected }) return false
        when (cmd) {
            // Method 1029, power 0 / 1 (Tiger Studio's notes, section 17.1).
            PvControl.LIGHT_ON -> sendMethod(1029, "{\"power\":1}")
            PvControl.LIGHT_OFF -> sendMethod(1029, "{\"power\":0}")
            PvControl.PAUSE -> sendMethod(1021, null)
            PvControl.RESUME -> sendMethod(1023, null)
            PvControl.STOP -> sendMethod(1022, null)
        }
        return true
    }

    override fun snapshot(): PrinterView = synchronized(lock) {
        val now = nowMs()
        val v = view
        v.notice = ""
        v.noticeError = false
        v.online = connected && lastMsgMs != 0L && (now - lastMsgMs) < ONLINE_WINDOW_MS
        v.canControl = v.online
        v.canLight = v.online
        v.lightOn = lightOn

        // print_status.state is the empty string at rest, not "standby".
        v.state = when (pstate) {
            "printing", "running", "busy", "preparing", "heating" -> PvState.PRINTING
            "paused" -> PvState.PAUSED
            "complete", "completed" -> PvState.DONE
            "error", "failed" -> PvState.ERROR
            else -> when {
                status == 14 -> PvState.ERROR
                status == 2 || status == 3 ->
                    if (subStatus == 2501 || subStatus == 2502) PvState.PAUSED else PvState.PRINTING
                else -> PvState.IDLE
            }
        }

        v.progress = if (v.state == PvState.IDLE) 0 else (if (progress > 100) 100 else progress)
        v.layer = layer
        v.totalLayer = 0 // not in the snapshot: only the layer in progress is
        v.leftS = if (v.state == PvState.PRINTING || v.state == PvState.PAUSED) leftS else 0
        v.nozzle = nozzle
        v.nozzleTarget = nozzleTarget
        v.bed = bed
        v.bedTarget = bedTarget
        v.chamber = chamber
        v.file = file

        for (u in v.unit) u.clear()
        if (canvas) {
            val u = v.unit[0]
            u.present = true
            u.slots = 4
            u.label = "Canvas"
            for (i in 0 until 4) {
                val s = u.slot[i]
                s.present = tray[i].present
                s.color = tray[i].color
                s.percent = -1
                s.material = tray[i].material
            }
        }
        v.copyDeep()
    }

    override fun stop() {
        try {
            job.cancel()
        } catch (_: Throwable) {
        }
        // The loop's own `finally` closes the client too; a second close is harmless.
        client?.let { closeQuietly(it) }
    }
}
