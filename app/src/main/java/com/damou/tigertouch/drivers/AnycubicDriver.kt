package com.damou.tigertouch.drivers

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.damou.tigertouch.cloud.AnycubicIdentity
import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.COLOR_UNKNOWN
import com.damou.tigertouch.model.PV_LABEL_EXT
import com.damou.tigertouch.model.PV_MAX_UNITS
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvState
import com.damou.tigertouch.net.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Locale
import javax.crypto.Cipher
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Anycubic (Kobra 3 / Kobra X family, ACE Pro), in both modes. Port of the
 * firmware's drivers/drv_anycubic.c.
 *
 * - LAN: the printer's own MQTT broker over TLS on port 9883, reached with the
 *   broker credentials the slicer paired (reference: Tiger Studio's
 *   anycubic/PROTOCOL.md, sections 5 and 5b).
 * - Cloud: Anycubic's broker, one session for every cloud printer of the
 *   account (below, "Cloud mode"), as TigerSpool does.
 *
 * The messages are the same in both: the topics and the ACE layout are the
 * ones the TigerSpool reads, and everything after the connection is shared.
 *
 * Nothing about this printer can be read off the printer itself: the device id,
 * the user name, the password and the numeric model id exist only in the
 * slicer's own configuration, which Tiger Studio imports into the account. A
 * printer without them says so.
 *
 * The broker's certificate is self-signed and there is no authority to vouch
 * for it: it is accepted, the way a Bambu printer in LAN mode is, and trust
 * comes from the credentials. The handshake is TLS 1.2 (the broker asks for an
 * optional client certificate, which a 1.3 stack aborts on), so the socket is
 * held to TLS 1.2 here: Android 10 and later would otherwise offer 1.3.
 *
 * Reports arrive on `.../public/{model}/{device}/{family}/report`; one
 * subscription to the whole subtree brings all of them (LAN only: see the
 * cloud's rules below). At rest the printer does not push its temperatures, so
 * they are asked for.
 *
 * Pause / resume / stop and the light are written from Tiger Studio's protocol
 * notes; this driver has not been run against a real printer for them, so the
 * commands are not verified.
 */
object AnycubicDriver : PrinterDriver {
    override val brand: String = "anycubic"

    override fun support(p: AccountPrinter): DrvKind {
        if (p.cloud) return DrvKind.CLOUD
        return if (p.ip.isNotEmpty()) DrvKind.LAN else DrvKind.NONE
    }

    override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink = AcuLink(p, env)
}

private const val TAG = "anycubic"

private const val MAX_MSG = 48 * 1024
private const val TEMP_EVERY_MS = 10_000L
private const val INFO_EVERY_MS = 30_000L
private const val ONLINE_WINDOW_MS = 60_000L
private const val MAX_BOXES = 6
/** esp-mqtt's reconnect_timeout_ms. */
private const val RECONNECT_MS = 5_000L
/** After the cloud refused the token: retried slowly, not every 5 s. */
private const val REFUSED_RECONNECT_MS = 60_000L
private const val TICK_MS = 1_000L

private const val ACU_BROKER = "ssl://mqtt-universe.anycubic.com:8883"
private const val ACU_MAX_SUBS = 8
private val ACU_TYPES = arrayOf("android", "ios", "web", "pcf")
private const val KICK_MS = 10_000L

/*
 * The report families a cloud printer is followed on, one subscription each:
 * the broker closes the session on a subscription it does not allow, and the
 * whole subtree ("/#") is one of those. TigerSpool's two first.
 */
private val ACU_FAMILIES = arrayOf("multiColorBox", "extfilbox", "print", "tempature", "status", "light", "lastWill")

private val rng = SecureRandom()

private fun nowMs(): Long = SystemClock.elapsedRealtime()

/** time(NULL) * 1000, as the firmware stamps its requests: whole seconds, in ms. */
private fun stampMs(): Long = System.currentTimeMillis() / 1000L * 1000L

/**
 * The last three bytes of the Wi-Fi MAC, as the firmware puts in its LAN client
 * id. Android hides the MAC from apps on recent versions (it reads 02:00:00:...):
 * then three random bytes, the same for every link of this run.
 */
private val macTail: String by lazy {
    val mac: ByteArray? = try {
        NetworkInterface.getByName("wlan0")?.hardwareAddress
    } catch (e: Exception) {
        null
    }
    val b = if (mac != null && mac.size == 6 && (mac[3].toInt() or mac[4].toInt() or mac[5].toInt()) != 0) {
        mac.copyOfRange(3, 6)
    } else {
        ByteArray(3).also { rng.nextBytes(it) }
    }
    String.format(Locale.ROOT, "%02x%02x%02x", b[0].toInt() and 0xff, b[1].toInt() and 0xff, b[2].toInt() and 0xff)
}

private fun md5Hex(s: String): String {
    val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(32)
    for (x in d) sb.append(String.format(Locale.ROOT, "%02x", x.toInt() and 0xff))
    return sb.toString()
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

private fun publishOn(cl: MqttAsyncClient, topic: String, body: String): Boolean = try {
    cl.publish(topic, body.toByteArray(Charsets.UTF_8), 0, false)
    true
} catch (e: Exception) {
    false
}

/** A CONNACK refusal is 1..5 (protocol, client id, unavailable, bad user name or password, not authorised). */
private fun isConnackRefusal(code: Int) = code in 1..5

private class AcuBoxSlot {
    var present = false
    var color = 0
    var material = ""
}

private class AcuBox {
    /** -1 = the external box, 0.. = the ACE units */
    var id = 0
    var used = false
    val slot = Array(4) { AcuBoxSlot() }

    fun reset(newId: Int) {
        id = newId
        used = true
        for (i in slot.indices) slot[i] = AcuBoxSlot()
    }
}

private fun rgbOf(arr: Any?): Int {
    if (arr !is JSONArray || arr.length() < 3) return COLOR_UNKNOWN
    fun c(i: Int): Int {
        val v = arr.opt(i)
        return if (v is Number) v.toDouble().toInt() else 0
    }
    return ((c(0) and 0xff) shl 16) or ((c(1) and 0xff) shl 8) or (c(2) and 0xff)
}

/** A transition: the action says what happened, the state where it stands. null: a data tick, not a transition. */
private fun printState(action: String, state: String): String? {
    if (action == "pause" && (state == "pausing" || state == "paused")) return "paused"
    if (action == "resume" && (state == "resuming" || state == "resumed")) return "printing"
    if (action == "start" || action == "stop") {
        when (state) {
            "printing" -> return "printing"
            "downloading", "checking", "preheating" -> return "preparing"
            "finished" -> return "finished"
            "stoped", "stopping" -> return "idle" // (sic)
            "failed" -> return "failed"
        }
    }
    return null
}

// ---- One printer -----------------------------------------------------------------

private class AcuLink(p: AccountPrinter, private val env: DriverEnv) : PrinterLink {
    val lock = Any()
    private val job: Job = SupervisorJob(env.scope.coroutineContext[Job])
    private val scope = CoroutineScope(env.scope.coroutineContext + job + Dispatchers.IO)

    private val devId: String = p.devId
    private val model: String = p.acuModel

    var tSub = ""
    private var tCmdPrefix = ""
    /** "/public/{model}/{device}/": what routes a cloud message to it. */
    var prefix = ""
    /** 0 = fine, 1 = not paired, 2 = cloud identity missing from the account */
    private var reason = 0
    /** Rides on the account's one cloud session (AcuCloud), which it does not own. */
    @Volatile var shared = false
    /** Guarded by AcuCloud.lock: which round of subscriptions is the current one. */
    var subGen = 0

    /** LAN only: its own client. */
    @Volatile private var client: MqttAsyncClient? = null

    // Guarded by `lock`.
    private var connected = false
    private var subscribed = false
    private var reportedOffline = false
    var lastMsgMs = 0L
        private set
    private var lastTempMs = 0L
    private var lastInfoMs = 0L
    private var seq = 0L

    /** idle preparing printing paused finished failed */
    private var pstate = "idle"
    private var progress = 0
    private var remainMin = 0
    private var layer = 0
    private var totalLayer = 0
    private var file = ""
    private var bed = 0f
    private var bedTarget = 0f
    private var nozzle = 0f
    private var nozzleTarget = 0f

    private val box = Array(MAX_BOXES) { AcuBox() }
    /** the single external spool of a printer without ACE */
    private var extPresent = false
    private var extColor = 0
    private var extMaterial = ""
    /** the chamber light, from the `light` report */
    private var lightOn = false

    private val view = PrinterView(name = p.name, brand = "anycubic")

    init {
        if (p.cloud) {
            startCloud()
        } else {
            startLan(p)
        }
    }

    private fun startCloud() {
        val hasKey = devId.isNotEmpty()
        val hasType = model.isNotEmpty()
        if (!hasKey || !hasType) {
            reason = 1
            Log.w(TAG, "an Anycubic cloud printer lacks${if (hasKey) "" else " key"}${if (hasType) "" else " machineType"} in the account")
            return
        }
        // The first segment is not always "printer": a wildcard, as on TigerSpool.
        // The base of the topics; AcuCloud.topic() adds each family.
        tSub = "anycubic/anycubicCloud/v1/+/public/$model/$devId/"
        tCmdPrefix = "anycubic/anycubicCloud/v1/web/printer/$model/$devId"
        prefix = "/public/$model/$devId/"
        if (!AcuCloud.join(this, env)) {
            reason = 2 // alive, silent, and the notice says why
            return
        }
        scope.launch { tickLoop() }
    }

    private fun startLan(p: AccountPrinter) {
        if (p.devId.isEmpty() || p.user.isEmpty() || p.acuModel.isEmpty() || p.cc.isEmpty()) {
            reason = 1
            // Which of the four is missing, said by field name, never by value.
            val missing = buildString {
                if (p.devId.isEmpty()) append(" deviceId")
                if (p.user.isEmpty()) append(" username")
                if (p.acuModel.isEmpty()) append(" acuModelId")
                if (p.cc.isEmpty()) append(" password")
            }
            Log.w(TAG, "an Anycubic printer lacks$missing in the account")
            return
        }
        tSub = "anycubic/anycubicCloud/v1/printer/public/$model/$devId/#"
        tCmdPrefix = "anycubic/anycubicCloud/v1/web/printer/$model/$devId"

        val uri = "ssl://" + Addr.withPort(p.ip, 9883)
        // One id per printer: two on one account would otherwise kick each other
        // off the broker in turn.
        val cid = "tigertouch-$macTail-${devId.take(8)}"
        val opts = MqttConnectOptions().apply {
            setUserName(p.user)
            setPassword(p.cc.toCharArray())
            setKeepAliveInterval(30)
            setConnectionTimeout(8)
            setCleanSession(true)
            setAutomaticReconnect(false)
            setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1)
            // Self-signed, on the LAN: any certificate, no name check, TLS 1.2.
            setSocketFactory(AcuTls12Factory(Net.lanSslSocketFactory))
            setHttpsHostnameVerificationEnabled(false)
        }
        scope.launch { lanLoop(uri, cid, opts) }
        scope.launch { tickLoop() }
    }

    // ---- Requests --------------------------------------------------------------

    private fun publish(topic: String, body: String): Boolean =
        if (shared) AcuCloud.publish(topic, body) else client?.let { publishOn(it, topic, body) } ?: false

    private fun nextSeq(): Long = synchronized(lock) { ++seq }

    private fun sendRequest(endpoint: String, type: String, action: String) {
        val body = "{\"type\":\"$type\",\"action\":\"$action\",\"timestamp\":${stampMs()},\"msgid\":\"tt-${nextSeq()}\"}"
        publish("$tCmdPrefix/$endpoint", body)
    }

    /*
     * Over the cloud only the two getInfo TigerSpool sends are asked: the broker
     * closes the whole session - every cloud printer's - on a publish it does not
     * allow, and the light and temperature queries are refused (seen on the
     * bench, 2026-10-14).
     */
    fun askLayout() {
        sendRequest("multiColorBox", "multiColorBox", "getInfo")
        sendRequest("extfilbox", "extfilbox", "getInfo")
        if (!shared) sendRequest("light", "light", "query")
        synchronized(lock) { lastInfoMs = nowMs() }
    }

    fun askTemps() {
        if (!shared) sendRequest("tempature", "tempature", "query") // (sic) the firmware's spelling
        synchronized(lock) { lastTempMs = nowMs() }
    }

    // ---- State merge -----------------------------------------------------------

    /** Under `lock`. */
    private fun applyPrintFields(d: JSONObject) {
        val fn = d.opt("filename")
        if (fn is String && fn.isNotEmpty()) {
            var f = fn.substring(fn.lastIndexOf('/') + 1)
            val dot = f.lastIndexOf('.')
            if (dot >= 0 && f.substring(dot).equals(".gcode", ignoreCase = true)) f = f.substring(0, dot)
            file = f
        }
        d.numOrNull("progress")?.let { progress = it.toInt() }
        d.numOrNull("remain_time")?.let { remainMin = it.toInt() } // minutes
        d.numOrNull("curr_layer")?.let { layer = it.toInt() }
        d.numOrNull("total_layers")?.let { totalLayer = it.toInt() }
        d.numOrNull("curr_hotbed_temp")?.let { bed = it.toFloat() }
        d.numOrNull("curr_nozzle_temp")?.let { nozzle = it.toFloat() }
        d.optJSONObject("settings")?.let { st ->
            st.numOrNull("target_hotbed_temp")?.let { bedTarget = it.toFloat() }
            st.numOrNull("target_nozzle_temp")?.let { nozzleTarget = it.toFloat() }
        }
    }

    /** Under `lock`. */
    private fun applyLayout(boxes: JSONArray) {
        for (bi in 0 until boxes.length()) {
            val b = boxes.optJSONObject(bi) ?: continue
            val id = b.opt("id")
            val slots = b.opt("slots")
            // A partial report (drying, feeding...) has no slots: it is not a
            // layout, and treating it as one would empty the cards.
            if (id !is Number || slots !is JSONArray) continue
            val idv = id.toDouble().toInt()
            var bx: AcuBox? = box.firstOrNull { it.used && it.id == idv }
            if (bx == null) {
                bx = box.firstOrNull { !it.used }?.also { it.reset(idv) }
            }
            if (bx == null) continue
            for (si in 0 until slots.length()) {
                val s = slots.optJSONObject(si) ?: continue
                val ix = s.opt("index")
                val i = if (ix is Number) ix.toDouble().toInt() else -1
                if (i < 0 || i > 3) continue
                val type = s.opt("type")
                val has = type is String && type.isNotEmpty()
                bx.slot[i].present = has
                bx.slot[i].material = if (has) type as String else ""
                bx.slot[i].color = rgbOf(s.opt("color"))
            }
        }
    }

    /** A whole report, from either connection. */
    fun onMessage(text: String) {
        // Any whole message is proof of life, parsed or not.
        synchronized(lock) { lastMsgMs = nowMs() }
        val root = parseObject(text) ?: return
        val type = root.str("type")
        val action = root.str("action")
        val state = root.str("state")
        val data = root.optJSONObject("data")

        synchronized(lock) {
            when (type) {
                "print" -> {
                    printState(action, state)?.let { pstate = it }
                    if (data != null) applyPrintFields(data)
                    if (pstate == "finished" || pstate == "idle" || pstate == "failed") remainMin = 0
                }
                "tempature" -> if (data != null) {
                    data.numOrNull("curr_hotbed_temp")?.let { bed = it.toFloat() }
                    data.numOrNull("curr_nozzle_temp")?.let { nozzle = it.toFloat() }
                    data.numOrNull("target_hotbed_temp")?.let { bedTarget = it.toFloat() }
                    data.numOrNull("target_nozzle_temp")?.let { nozzleTarget = it.toFloat() }
                }
                "light" -> {
                    // Shape not fully known (Tiger Studio parses it defensively too): a
                    // `status` of 0 or 1 beside the brightness.
                    data?.numOrNull("status")?.let { lightOn = it != 0.0 }
                }
                "status" -> {
                    // The coarse busy / free heartbeat: it lifts "idle" to "preparing"
                    // while the printer works before its first print tick, and drops a
                    // lingering active state when the printer reports free (the end of
                    // a job that was missed).
                    if (action == "workReport") {
                        if (state == "busy" && pstate == "idle") {
                            pstate = "preparing"
                        } else if (state == "free" &&
                            (pstate == "printing" || pstate == "preparing" || pstate == "paused")
                        ) {
                            pstate = "idle"
                        }
                    }
                }
                "multiColorBox" -> data?.optJSONArray("multi_color_box")?.let { applyLayout(it) }
                "extfilbox" -> if (action == "reportInfo" && data != null) {
                    val t = data.opt("type")
                    extPresent = t is String && t.isNotEmpty()
                    extMaterial = if (t is String) t else ""
                    extColor = rgbOf(data.opt("color"))
                }
                "lastWill" -> if (action == "onlineReport") reportedOffline = state == "offline"
            }
            Unit
        }
    }

    // ---- Connection state, set by the LAN loop or by the cloud session ----------

    fun setConnected(on: Boolean) {
        synchronized(lock) {
            connected = on
            if (!on) subscribed = false
        }
    }

    fun setSubscribed(on: Boolean) {
        synchronized(lock) { subscribed = on }
    }

    // ---- LAN MQTT --------------------------------------------------------------

    private suspend fun lanLoop(uri: String, cid: String, opts: MqttConnectOptions) {
        val cl = try {
            MqttAsyncClient(uri, cid, MemoryPersistence())
        } catch (e: Exception) {
            Log.w(TAG, "bad broker address: ${e.javaClass.simpleName}")
            return
        }
        client = cl
        Log.i(TAG, "connecting to the printer's broker")
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
                            val b = message?.payload ?: return
                            if (b.isEmpty() || b.size > MAX_MSG) return
                            onMessage(String(b, Charsets.UTF_8))
                        } catch (e: Exception) {
                            Log.w(TAG, "report not handled", e)
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                try {
                    cl.connectAwait(opts)
                    Log.i(TAG, "connected to the printer's broker")
                    synchronized(lock) {
                        connected = true
                        subscribed = false
                    }
                    // The questions go out once the subscription is acknowledged:
                    // asked earlier, the answers could be missed.
                    cl.subscribe(tSub, 0, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            setSubscribed(true)
                            askLayout()
                            askTemps()
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            Log.w(TAG, "subscription refused")
                        }
                    })
                    lost.await()
                    Log.w(TAG, "disconnected")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MqttException) {
                    if (isConnackRefusal(e.reasonCode)) {
                        Log.w(TAG, "broker refused the session (code ${e.reasonCode})")
                    } else {
                        Log.w(TAG, "connection failed (reason ${e.reasonCode})")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "connection failed: ${e.javaClass.simpleName}")
                }
                setConnected(false)
                delay(RECONNECT_MS)
            }
        } finally {
            setConnected(false)
            client = null
            closeQuietly(cl)
        }
    }

    // ---- Timers ----------------------------------------------------------------

    /** What the firmware does in its snapshot, once a second. */
    private suspend fun tickLoop() {
        while (scope.isActive) {
            delay(TICK_MS)
            val now = nowMs()
            var temps = false
            var info = false
            synchronized(lock) {
                if (connected && subscribed) {
                    temps = now - lastTempMs > TEMP_EVERY_MS
                    info = now - lastInfoMs > INFO_EVERY_MS
                }
            }
            // Outside `lock`: a cloud publish takes the session's lock, which is
            // always taken before a printer's.
            if (temps) askTemps()
            if (info) askLayout()
        }
    }

    // ---- Link ------------------------------------------------------------------

    /*
     * Pause, resume, stop: a print request with action pause / resume / stop and
     * taskid -1, on the family topic. Written from Tiger Studio's protocol notes;
     * this driver has not been run against a real printer, so the command is not
     * verified.
     */
    override fun control(cmd: PvControl): Boolean {
        if (shared) return false // no command over the cloud until each one is seen allowed (askLayout)
        val cl = client ?: return false
        if (!synchronized(lock) { connected && subscribed }) return false
        return when (cmd) {
            PvControl.LIGHT_ON, PvControl.LIGHT_OFF -> {
                // `light` family, type 3 is the chamber light (type 1 is the camera's).
                val on = cmd == PvControl.LIGHT_ON
                val body = "{\"type\":\"light\",\"action\":\"control\",\"timestamp\":${stampMs()}," +
                    "\"msgid\":\"tt-${nextSeq()}\",\"data\":{\"type\":3,\"status\":${if (on) 1 else 0}," +
                    "\"brightness\":${if (on) 100 else 0}}}"
                publishOn(cl, "$tCmdPrefix/light", body)
            }
            PvControl.PAUSE, PvControl.RESUME, PvControl.STOP -> {
                val action = when (cmd) {
                    PvControl.PAUSE -> "pause"
                    PvControl.RESUME -> "resume"
                    else -> "stop"
                }
                val body = "{\"type\":\"print\",\"action\":\"$action\",\"timestamp\":${stampMs()}," +
                    "\"msgid\":\"tt-${nextSeq()}\",\"data\":{\"taskid\":\"-1\"}}"
                publishOn(cl, "$tCmdPrefix/print", body)
            }
        }
    }

    override fun snapshot(): PrinterView = synchronized(lock) {
        val v = view
        v.notice = ""
        v.noticeError = false
        if (reason != 0) {
            v.online = false
            v.canControl = false
            v.canLight = false
            v.notice = if (reason == 2) {
                Tr.t(
                    "Its cloud sign-in is missing: refresh this printer in Tiger Studio.",
                    "Ses identifiants cloud manquent : actualise cette imprimante dans Tiger Studio.",
                )
            } else {
                Tr.t(
                    "Pair it once in AnycubicSlicerNext, then sync in Tiger Studio.",
                    "Appaire-la une fois dans AnycubicSlicerNext, puis synchronise dans Tiger Studio.",
                )
            }
            v.noticeError = true
            return@synchronized v.copyDeep()
        }
        if (shared && AcuCloud.refused) {
            v.notice = Tr.t(
                "Anycubic refused the sign-in: refresh this printer in Tiger Studio.",
                "Anycubic a refusé la connexion : actualise cette imprimante dans Tiger Studio.",
            )
            v.noticeError = true
        }

        val now = nowMs()
        v.online = connected && subscribed && !reportedOffline && lastMsgMs != 0L &&
            (now - lastMsgMs) < ONLINE_WINDOW_MS
        v.canControl = v.online && !shared
        v.canLight = v.online && !shared
        v.lightOn = lightOn

        v.state = when (pstate) {
            "printing", "preparing" -> PvState.PRINTING
            "paused" -> PvState.PAUSED
            "finished" -> PvState.DONE
            "failed" -> PvState.ERROR
            else -> PvState.IDLE
        }

        v.progress = when (v.state) {
            PvState.IDLE -> 0
            PvState.DONE -> 100
            else -> if (progress > 100) 100 else progress
        }
        v.layer = layer
        v.totalLayer = totalLayer
        v.leftS = if (v.state == PvState.PRINTING || v.state == PvState.PAUSED) remainMin * 60 else 0
        v.nozzle = nozzle
        v.nozzleTarget = nozzleTarget
        v.bed = bed
        v.bedTarget = bedTarget
        v.chamber = 0f
        v.file = file

        for (u in v.unit) u.clear()
        var n = 0
        if (extPresent) {
            // A printer with no ACE: one spool, on its own compact card.
            val u = v.unit[n++]
            u.present = true
            u.slots = 1
            u.label = PV_LABEL_EXT
            u.slot[0].present = true
            u.slot[0].color = extColor
            u.slot[0].percent = -1
            u.slot[0].material = extMaterial
        }
        val sorted = box.filter { it.used }.sortedBy { it.id }
        val aces = sorted.count { it.id >= 0 }
        var letter = 0
        for (b in sorted) {
            if (n >= PV_MAX_UNITS) break
            val u = v.unit[n++]
            u.present = true
            u.slots = 4
            u.label = when {
                // The external box of an ACE printer has four slots of its own,
                // so it is not the compact single-spool card.
                b.id < 0 -> "EXT"
                aces > 1 -> "ACE " + ('A' + (letter++ % 26))
                else -> "ACE"
            }
            for (k in 0 until 4) {
                u.slot[k].present = b.slot[k].present
                u.slot[k].color = b.slot[k].color
                u.slot[k].percent = -1
                u.slot[k].material = b.slot[k].material
            }
        }
        v.copyDeep()
    }

    override fun stop() {
        try {
            job.cancel()
        } catch (_: Throwable) {
        }
        try {
            if (shared) AcuCloud.leave(this)
        } catch (e: Throwable) {
            Log.w(TAG, "leaving the cloud session: ${e.javaClass.simpleName}")
        }
        client?.let { closeQuietly(it) }
    }
}

// ---- Cloud mode: one session for every cloud Anycubic of the account -------------

/*
 * After TigerSpool's anycubic_cloud.cpp, measured there on the bench:
 *
 * - ONE session. The broker fixes the client id per account - md5(email +
 *   type) - and refuses any other: two sessions for one account would kick
 *   each other off in turn. Every cloud printer is a subscription on it, and
 *   messages are routed by topic, as for Bambu Lab.
 * - Signed in as the slicer does: the password is the workbench token,
 *   RSA-encrypted (PKCS#1 v1.5) to the key of Anycubic's CA and base64'd; the
 *   username is user|type|email|md5(cid + password + cid); and the client
 *   certificate is presented for mutual TLS. Tiger Studio writes the token, the
 *   CA and the client certificate on every cloud printer (cloud/Cloud.kt).
 * - As "android", beside the others: the broker keeps one session per account
 *   AND per type, and the newest takes the place of an older one. Tiger Studio
 *   and the slicer sign in as "pcf", TigerSpool as "web"; on the bench a Tiger
 *   Touch signed in as "web" beside a TigerSpool following the same printer, and
 *   each kicked the other off every few seconds (2026-10-14). TigerSpool found
 *   "web", "android" and "ios" accepted. A type that is refused, or whose
 *   sessions keep being taken (three in a row lasting under KICK_MS), is left
 *   for the next one.
 * - The broker's certificate comes from Anycubic's own "AC Root CA" and is
 *   signed with SHA-1. The connection is verified against that CA only (AcuTls);
 *   every other connection keeps the system's trust. The broker also answers
 *   "insufficient security" to a client hello that does not offer RSA with
 *   SHA-1: Android's TLS stack (BoringSSL) still offers it in TLS 1.2.
 *
 * Paho fixes the client id when a client is made, so where the firmware hands a
 * new configuration to its client (another type), a new client is made for the
 * next attempt.
 */
private object AcuCloud {
    val lock = Any()

    // Guarded by `lock`.
    private var job: Job? = null
    private var client: MqttAsyncClient? = null
    private var connected = false
    private var type = 0
    private var typeFound = false
    /** when the session last came up */
    private var upMs = 0L
    /** sessions in a row that lasted under KICK_MS */
    private var kicks = 0
    private val subs = ArrayList<AcuLink>()
    private var reconnectMs = RECONNECT_MS

    private var id: AnycubicIdentity? = null
    private var ca: X509Certificate? = null
    private var ssl: SSLSocketFactory? = null
    private var cid = ""
    private var user = ""
    private var pass = ""

    /** The token was refused: every cloud printer says so. */
    @Volatile var refused = false
        private set

    /** The identity from the account, the CA parsed, the login built. Under `lock`. */
    private fun prepare(env: DriverEnv): Boolean {
        val ident = env.cloud.anycubicIdentity()
        if (ident == null) {
            Log.w(TAG, "the account holds no complete Anycubic cloud identity")
            return false
        }
        return try {
            val der = Base64.decode(ident.caDerB64, Base64.DEFAULT)
            val caCert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            id = ident
            ca = caCert
            ssl = AcuTls.socketFactory(caCert, ident.certPem, ident.keyPem)
            if (!buildLogin()) throw GeneralSecurityException("the login could not be built")
            true
        } catch (e: Exception) {
            Log.w(TAG, "the Anycubic cloud identity in the account could not be used (${e.javaClass.simpleName}: ${e.message})")
            release()
            false
        }
    }

    /** The three login fields for the current type, a fresh encryption each time. Under `lock`. */
    private fun buildLogin(): Boolean {
        val ident = id ?: return false
        val caCert = ca ?: return false
        val t = ACU_TYPES[type]
        return try {
            val newCid = md5Hex(ident.email + t)
            val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            c.init(Cipher.ENCRYPT_MODE, caCert.publicKey, rng)
            val p = Base64.encodeToString(c.doFinal(ident.token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            val sig = md5Hex(newCid + p + newCid)
            cid = newCid
            pass = p
            user = "user|$t|${ident.email}|$sig"
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Everything the session holds, once its client is gone. Under `lock`. */
    private fun release() {
        id = null
        ca = null
        ssl = null
        cid = ""
        user = ""
        pass = ""
    }

    private fun topic(c: AcuLink, family: String) = "${c.tSub}$family/#"

    /** Under `lock`. */
    private fun subscribe(c: AcuLink, myJob: Job) {
        c.setSubscribed(false)
        val cl = client ?: return
        val gen = ++c.subGen
        for ((i, f) in ACU_FAMILIES.withIndex()) {
            val last = i == ACU_FAMILIES.size - 1
            try {
                Log.i(TAG, "subscribing to $f")
                cl.subscribe(topic(c, f), 0, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        // The last one acknowledged: the questions can go out.
                        if (last) onSubscribed(c, gen, myJob)
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.w(TAG, "subscription to $f failed")
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "subscribe to $f failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun onSubscribed(c: AcuLink, gen: Int, myJob: Job): Unit = synchronized(lock) {
        if (job !== myJob || c.subGen != gen || !subs.contains(c)) return@synchronized
        c.setSubscribed(true)
        c.askLayout()
        c.askTemps()
    }

    fun publish(topic: String, body: String): Boolean = synchronized(lock) {
        val cl = client ?: return@synchronized false
        publishOn(cl, topic, body)
    }

    /**
     * Joins the account's cloud session, opening it when this is the first cloud
     * printer. false when the account holds no usable identity.
     */
    fun join(c: AcuLink, env: DriverEnv): Boolean = synchronized(lock) {
        if (subs.size >= ACU_MAX_SUBS) return@synchronized false
        if (job == null) {
            type = 0
            typeFound = false
            refused = false
            kicks = 0
            reconnectMs = RECONNECT_MS
            if (!prepare(env)) return@synchronized false
            connected = false
            val j = SupervisorJob(env.scope.coroutineContext[Job])
            job = j
            CoroutineScope(env.scope.coroutineContext + j + Dispatchers.IO).launch { loop(j) }
            Log.i(TAG, "opening the Anycubic cloud session")
        }
        c.shared = true
        subs.add(c)
        if (connected) {
            c.setConnected(true)
            job?.let { subscribe(c, it) }
        }
        true
    }

    fun leave(c: AcuLink) {
        var closingJob: Job? = null
        var closingClient: MqttAsyncClient? = null
        synchronized(lock) {
            subs.remove(c)
            val cl = client
            if (connected && subs.isNotEmpty() && cl != null) {
                for (f in ACU_FAMILIES) {
                    try {
                        cl.unsubscribe(topic(c, f))
                    } catch (_: Exception) {
                    }
                }
            }
            if (subs.isEmpty()) {
                closingJob = job
                closingClient = cl
                job = null
                client = null
                connected = false
                release()
            }
        }
        // Outside the lock: Paho's callbacks take it.
        closingJob?.cancel()
        closingClient?.let {
            closeQuietly(it)
            Log.i(TAG, "Anycubic cloud session closed")
        }
    }

    private suspend fun loop(myJob: Job) {
        while (currentCoroutineContext().isActive) {
            var opts: MqttConnectOptions? = null
            var clientId = ""
            synchronized(lock) {
                if (job !== myJob) return
                val f = ssl
                if (f != null && cid.isNotEmpty()) {
                    clientId = cid
                    opts = MqttConnectOptions().apply {
                        setUserName(this@AcuCloud.user)
                        setPassword(this@AcuCloud.pass.toCharArray())
                        setKeepAliveInterval(30)
                        setConnectionTimeout(8)
                        setCleanSession(true)
                        setAutomaticReconnect(false)
                        setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1)
                        setSocketFactory(f)
                        // The name is checked by AcuHostVerifier: Paho's own check relies on
                        // an endpoint identification Android 7 may not apply.
                        setHttpsHostnameVerificationEnabled(false)
                        setSSLHostnameVerifier(AcuHostVerifier)
                    }
                }
            }
            val o = opts
            if (o == null) return
            val cl = try {
                MqttAsyncClient(ACU_BROKER, clientId, MemoryPersistence())
            } catch (e: Exception) {
                Log.w(TAG, "cloud client not made: ${e.javaClass.simpleName}")
                return
            }
            val lost = CompletableDeferred<Unit>()
            cl.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    lost.complete(Unit)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    // Nothing may escape this callback: Paho drops the session when it throws.
                    try {
                        onData(myJob, topic ?: return, message?.payload ?: return)
                    } catch (e: Exception) {
                        Log.w(TAG, "cloud report not handled", e)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            synchronized(lock) {
                if (job !== myJob) {
                    closeQuietly(cl)
                    return
                }
                client = cl
            }
            try {
                cl.connectAwait(o)
                onConnected(myJob)
                lost.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MqttException) {
                onError(myJob, e.reasonCode)
            } catch (e: Exception) {
                Log.w(TAG, "Anycubic cloud connection failed: ${e.javaClass.simpleName}")
            } finally {
                onDisconnected(myJob, cl)
                closeQuietly(cl)
            }
            delay(synchronized(lock) { reconnectMs })
        }
    }

    private fun onConnected(myJob: Job): Unit = synchronized(lock) {
        if (job !== myJob) return@synchronized
        Log.i(TAG, "connected to the Anycubic cloud as '${ACU_TYPES[type]}' (${subs.size} printer(s) on the session)")
        connected = true
        refused = false
        typeFound = true
        upMs = nowMs()
        for (c in subs) {
            c.setConnected(true)
            subscribe(c, myJob)
        }
    }

    private fun onError(myJob: Job, rc: Int): Unit = synchronized(lock) {
        if (job !== myJob) return@synchronized
        if (!isConnackRefusal(rc)) {
            Log.w(TAG, "Anycubic cloud connection failed (reason $rc)")
            return@synchronized
        }
        val creds = rc == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
            rc == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
        Log.w(TAG, "the Anycubic cloud refused '${ACU_TYPES[type]}' (return code $rc)")
        if (creds && !typeFound && type + 1 < ACU_TYPES.size) {
            type++ // not yet a verdict on the token: try the next type
        } else if (creds) {
            // The token was revoked by a newer slicer sign-in; only Tiger
            // Studio can fetch a new one. Retried slowly, not every 5 s.
            refused = true
            type = 0
            typeFound = false
            reconnectMs = REFUSED_RECONNECT_MS
        }
        buildLogin() // the next attempt uses it
        Unit
    }

    private fun onDisconnected(myJob: Job, cl: MqttAsyncClient): Unit = synchronized(lock) {
        if (job !== myJob) return@synchronized
        if (client === cl) client = null
        if (connected) {
            Log.w(TAG, "Anycubic cloud session disconnected")
            // Taken over within seconds, again and again: another client signs
            // in with this type. Leave it the type and take the next one.
            kicks = if (nowMs() - upMs < KICK_MS) kicks + 1 else 0
            if (kicks >= 3 && id != null) {
                kicks = 0
                type = (type + 1) % ACU_TYPES.size
                Log.w(TAG, "another client keeps taking the session: signing in as '${ACU_TYPES[type]}'")
                buildLogin()
            }
        }
        connected = false
        for (c in subs) c.setConnected(false)
    }

    /** A whole message: routed by topic to the printer it is for. */
    private fun onData(myJob: Job, topic: String, payload: ByteArray): Unit = synchronized(lock) {
        if (job !== myJob) return@synchronized
        val c = subs.firstOrNull { topic.contains(it.prefix) } ?: return@synchronized
        if (payload.isEmpty() || payload.size > MAX_MSG) return@synchronized
        if (c.lastMsgMs == 0L) Log.i(TAG, "first report from a cloud printer (${payload.size} bytes)")
        c.onMessage(String(payload, Charsets.UTF_8))
    }
}

// ---- TLS for the cloud: Anycubic's CA, the client identity, TLS 1.2 ---------------

private object AcuTls {
    /** Mutual TLS: the client certificate and key from the account, the server checked against Anycubic's CA alone. */
    fun socketFactory(ca: X509Certificate, certPem: String, keyPem: String): SSLSocketFactory {
        val cf = CertificateFactory.getInstance("X.509")
        val chain = pemBlocks(certPem)
            .filter { it.first == "CERTIFICATE" }
            .map { cf.generateCertificate(ByteArrayInputStream(it.second)) as X509Certificate }
        if (chain.isEmpty()) throw GeneralSecurityException("no certificate in the client certificate PEM")
        val key = privateKey(keyPem)

        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setCertificateEntry("anycubic-ca", ca)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf<KeyManager>(AcuKeyManager(chain.toTypedArray(), key)), tmf.trustManagers, SecureRandom())
        return AcuTls12Factory(ctx.socketFactory)
    }

    /** (label, DER) for every PEM block of [pem]. */
    private fun pemBlocks(pem: String): List<Pair<String, ByteArray>> {
        val re = Regex("-----BEGIN ([A-Z0-9 ]+)-----([\\s\\S]*?)-----END \\1-----")
        return re.findAll(pem).map { m ->
            m.groupValues[1] to Base64.decode(m.groupValues[2].trim(), Base64.DEFAULT)
        }.toList()
    }

    /**
     * The client key: PKCS#8 ("PRIVATE KEY"), PKCS#1 RSA ("RSA PRIVATE KEY") or
     * SEC1 EC ("EC PRIVATE KEY"); the last two are wrapped into PKCS#8, the only
     * form KeyFactory reads. An encrypted key is not supported.
     */
    private fun privateKey(pem: String): PrivateKey {
        val blocks = pemBlocks(pem)
        blocks.firstOrNull { it.first == "PRIVATE KEY" }?.let { (_, body) ->
            return pkcs8(body, "RSA") ?: pkcs8(body, "EC")
                ?: throw GeneralSecurityException("unreadable PKCS#8 client key")
        }
        blocks.firstOrNull { it.first == "RSA PRIVATE KEY" }?.let { (_, body) ->
            // PrivateKeyInfo { version 0, { rsaEncryption, NULL }, OCTET STRING { RSAPrivateKey } }
            val alg = der(0x30, RSA_OID + byteArrayOf(0x05, 0x00))
            return pkcs8(der(0x30, VERSION_0 + alg + der(0x04, body)), "RSA")
                ?: throw GeneralSecurityException("unreadable RSA client key")
        }
        blocks.firstOrNull { it.first == "EC PRIVATE KEY" }?.let { (_, body) ->
            // PrivateKeyInfo { version 0, { ecPublicKey, namedCurve }, OCTET STRING { ECPrivateKey } }:
            // the curve is the [0] parameters of the ECPrivateKey itself.
            val curve = ecCurveOid(body) ?: throw GeneralSecurityException("EC client key without a named curve")
            val alg = der(0x30, EC_OID + curve)
            return pkcs8(der(0x30, VERSION_0 + alg + der(0x04, body)), "EC")
                ?: throw GeneralSecurityException("unreadable EC client key")
        }
        if (blocks.any { it.first == "ENCRYPTED PRIVATE KEY" } || pem.contains("Proc-Type: 4,ENCRYPTED")) {
            throw GeneralSecurityException("the client key is encrypted")
        }
        throw GeneralSecurityException("no private key in the client key PEM")
    }

    private fun pkcs8(der: ByteArray, alg: String): PrivateKey? = try {
        KeyFactory.getInstance(alg).generatePrivate(PKCS8EncodedKeySpec(der))
    } catch (e: Exception) {
        null
    }

    /** 1.2.840.113549.1.1.1 */
    private val RSA_OID = byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01)
    /** 1.2.840.10045.2.1 */
    private val EC_OID = byteArrayOf(0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01)
    private val VERSION_0 = byteArrayOf(0x02, 0x01, 0x00)

    /** A DER TLV. */
    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> {
                out.write(0x81); out.write(n)
            }
            n < 0x10000 -> {
                out.write(0x82); out.write(n shr 8); out.write(n and 0xff)
            }
            else -> {
                out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    /** (tag, start of content, content length) of the TLV at [off]; null when it does not fit. */
    private fun tlv(b: ByteArray, off: Int): Triple<Int, Int, Int>? {
        if (off + 2 > b.size) return null
        val tag = b[off].toInt() and 0xff
        var len = b[off + 1].toInt() and 0xff
        var p = off + 2
        if (len and 0x80 != 0) {
            val k = len and 0x7f
            if (k == 0 || k > 3 || p + k > b.size) return null
            len = 0
            repeat(k) { len = (len shl 8) or (b[p++].toInt() and 0xff) }
        }
        if (p + len > b.size) return null
        return Triple(tag, p, len)
    }

    /** ECPrivateKey ::= SEQUENCE { version, privateKey, [0] parameters OPTIONAL, [1] publicKey OPTIONAL }: the OID TLV inside [0]. */
    private fun ecCurveOid(sec1: ByteArray): ByteArray? {
        val seq = tlv(sec1, 0) ?: return null
        if (seq.first != 0x30) return null
        var p = seq.second
        val end = seq.second + seq.third
        while (p < end) {
            val e = tlv(sec1, p) ?: return null
            if (e.first == 0xa0) {
                val oid = tlv(sec1, e.second) ?: return null
                if (oid.first != 0x06) return null
                return sec1.copyOfRange(e.second, oid.second + oid.third)
            }
            p = e.second + e.third
        }
        return null
    }
}

/** Presents the one client identity, whatever the server asks. */
private class AcuKeyManager(
    private val chain: Array<X509Certificate>,
    private val key: PrivateKey,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = ALIAS

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

    override fun getPrivateKey(alias: String?): PrivateKey = key

    private companion object {
        const val ALIAS = "anycubic-client"
    }
}

/**
 * Holds every socket to TLS 1.2: the firmware's mbedTLS speaks 1.2, the LAN
 * broker aborts a 1.3 handshake on its optional client-certificate request,
 * and the cloud's SHA-1 signature is a TLS 1.2 matter.
 */
private class AcuTls12Factory(private val d: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        only12(d.createSocket(s, host, port, autoClose))

    override fun createSocket(): Socket = only12(d.createSocket())

    override fun createSocket(host: String?, port: Int): Socket = only12(d.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        only12(d.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket = only12(d.createSocket(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        only12(d.createSocket(address, port, localAddress, localPort))

    private fun only12(s: Socket): Socket {
        if (s is SSLSocket && s.supportedProtocols.contains("TLSv1.2")) {
            s.enabledProtocols = arrayOf("TLSv1.2")
        }
        return s
    }
}

/**
 * The broker's name, checked as mbedTLS does: the subject alternative names,
 * or the common name when there are none (Android's own verifier ignores the
 * common name, and a private CA's certificate may carry nothing else).
 */
private object AcuHostVerifier : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        if (hostname == null || session == null) return false
        try {
            if (HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)) return true
        } catch (_: Exception) {
        }
        val cert = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (e: Exception) {
            null
        } ?: return false
        val names = ArrayList<String>()
        try {
            cert.subjectAlternativeNames?.forEach { san ->
                if (san.size >= 2 && san[0] == 2) (san[1] as? String)?.let { names.add(it) }
            }
        } catch (_: Exception) {
        }
        if (names.isEmpty()) {
            Regex("(?:^|,)\\s*CN=([^,]+)").find(cert.subjectX500Principal.name)?.let { names.add(it.groupValues[1].trim()) }
        }
        return names.any { matches(hostname, it) }
    }

    private fun matches(host: String, pattern: String): Boolean {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        val p = pattern.lowercase(Locale.ROOT).trimEnd('.')
        if (p.startsWith("*.")) {
            val rest = p.substring(1) // ".anycubic.com"
            return h.endsWith(rest) && h.length > rest.length && !h.substring(0, h.length - rest.length).contains('.')
        }
        return h == p
    }
}
