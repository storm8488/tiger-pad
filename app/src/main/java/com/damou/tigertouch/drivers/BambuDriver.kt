package com.damou.tigertouch.drivers

import android.os.SystemClock
import android.util.Log
import com.damou.tigertouch.cloud.BambuSession
import com.damou.tigertouch.cloud.CloudErr
import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.COLOR_UNKNOWN
import com.damou.tigertouch.model.PV_LABEL_EXT
import com.damou.tigertouch.model.PV_LABEL_HT
import com.damou.tigertouch.model.PV_MAX_NOZZLES
import com.damou.tigertouch.model.PV_MAX_UNITS
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvState
import com.damou.tigertouch.model.PvUnit
import com.damou.tigertouch.net.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.json.JSONTokener
import java.security.SecureRandom
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Bambu Lab, on the LAN or through Bambu's cloud: the firmware's
 * drivers/drv_bambu.c. Both are MQTT over TLS and share the report parser.
 *
 *  - LAN: the printer is its own broker, ssl://<ip>:8883, user "bblp", the
 *    access code as password. Its certificate is self-signed.
 *  - Cloud (the way a printer not on this network is reached):
 *    ssl://<region>.mqtt.bambulab.com:8883. The app never signs in to Bambu.
 *    Tiger Studio does, and stores the session in the TigerTag account
 *    (printers/bambulab/secrets/cloud_session); this reads it from there,
 *    exactly as the TigerSpool does.
 *
 * Reports are JSON and a full one is tens of kilobytes. The firmware looks the
 * few scalar fields it wants up in the raw text (a tree parser would spend its
 * scarce RAM on thousands of nodes) and hands only the small objects that need
 * real parsing (device, ams, the external spool) to a JSON parser. RAM is not
 * scarce here, but the raw lookups are kept as they are: they decide which
 * occurrence of a key wins (the first in the text, wherever it sits), and that
 * is part of the driver's behaviour.
 */

private const val TAG = "bambu"

/** Longer messages are dropped, as the firmware's reassembly does. */
private const val MAX_REPORT = 192 * 1024
private const val ONLINE_WINDOW_MS = 75_000L
private const val PUSHALL_EVERY_MS = 40_000L
/** Joined in the middle of a print, the first full report can come without the AMS. */
private const val AMS_RETRY_AFTER_MS = 20_000L
private const val AMS_RETRIES = 3
/** esp-mqtt's reconnect_timeout_ms: a new attempt 5 s after a failure or a drop. */
private const val RECONNECT_MS = 5_000L
private const val KEEPALIVE_S = 30
private const val CONNECT_TIMEOUT_S = 10

private const val PUSHALL = "{\"pushing\":{\"sequence_id\":\"0\",\"command\":\"pushall\"}}"

private fun now(): Long = SystemClock.elapsedRealtime()

// ---- Screen text (i18n_table.h, English and French columns) ---------------------

private fun txtRefused() = Tr.t(
    "Bambu session refused: renew it in Tiger Studio.",
    "Session Bambu refusée : renouvelle-la dans Tiger Studio.",
)

private fun txtMissing() = Tr.t(
    "No Bambu session: sign in to Bambu in Tiger Studio.",
    "Session Bambu absente : connecte-toi à Bambu dans Tiger Studio.",
)

private fun txtAccountSilent() = Tr.t(
    "The account isn't answering. Check your Wi-Fi.",
    "Le compte ne répond pas. Vérifie la connexion Wi-Fi.",
)

private fun txtCloudFail() = Tr.t("Can't reach the cloud.", "Connexion cloud impossible.")

// ---- Raw JSON lookups ----------------------------------------------------------

/*
 * The A1, A1 mini, P1P and P1S have no chamber sensor, yet report a
 * chamber_temper (5 on an A1 seen on the bench, 2026-10-15). They are told by
 * the first three characters of their serial, the model code Bambu Lab puts
 * there.
 */
private val NO_CHAMBER = arrayOf("039", "030", "01S", "01P")

private fun hasChamberSensor(sn: String): Boolean = NO_CHAMBER.none { sn.startsWith(it) }

private fun isCSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'

/**
 * The index just after `"key":` (key given with its quotes), or -1. Only the
 * first occurrence of the key is looked at, as strstr() does.
 */
private fun findValue(buf: String, key: String): Int {
    var p = buf.indexOf(key)
    if (p < 0) return -1
    p += key.length
    while (p < buf.length && (buf[p] == ' ' || buf[p] == '\t')) p++
    if (p >= buf.length || buf[p] != ':') return -1
    p++
    while (p < buf.length && (buf[p] == ' ' || buf[p] == '\t')) p++
    return p
}

/** strtod(): the longest number at [start] (leading white space skipped), or null when there is none. */
private fun strtod(s: String, start: Int): Double? {
    var i = start
    while (i < s.length && isCSpace(s[i])) i++
    val b = i
    if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
    var digits = 0
    while (i < s.length && s[i] in '0'..'9') { i++; digits++ }
    if (i < s.length && s[i] == '.') {
        i++
        while (i < s.length && s[i] in '0'..'9') { i++; digits++ }
    }
    if (digits == 0) return null
    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
        var j = i + 1
        if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
        val e = j
        while (j < s.length && s[j] in '0'..'9') j++
        if (j > e) i = j
    }
    return s.substring(b, i).toDoubleOrNull()
}

/** atoi(): leading white space, a sign, digits; 0 when there are none. */
private fun atoi(s: String): Int {
    var i = 0
    while (i < s.length && isCSpace(s[i])) i++
    var neg = false
    if (i < s.length && (s[i] == '+' || s[i] == '-')) {
        neg = s[i] == '-'
        i++
    }
    var v = 0L
    while (i < s.length && s[i] in '0'..'9' && v < 10_000_000_000L) {
        v = v * 10 + (s[i] - '0')
        i++
    }
    if (neg) v = -v
    return v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

private fun getNum(buf: String, key: String): Double? {
    var v = findValue(buf, key)
    if (v < 0) return null
    if (v < buf.length && buf[v] == '"') v++ // numbers sometimes arrive as strings
    return strtod(buf, v)
}

/**
 * The string after `"key":`, escapes undone; null when the value is not a
 * string. An unterminated string gives what was read, as the firmware's does.
 */
private fun getStr(buf: String, key: String): String? {
    var v = findValue(buf, key)
    if (v < 0 || v >= buf.length || buf[v] != '"') return null
    v++
    val out = StringBuilder()
    while (v < buf.length && buf[v] != '"') {
        var c = buf[v]
        if (c == '\\' && v + 1 < buf.length) {
            v++
            c = buf[v]
            when (c) {
                'n' -> c = '\n'
                't' -> c = '\t'
                'r' -> c = '\r'
                'b' -> c = '\b'
                'f' -> c = '\u000C'
                'u' -> if (v + 4 < buf.length) {
                    val code = buf.substring(v + 1, v + 5).toIntOrNull(16)
                    if (code != null) {
                        c = code.toChar()
                        v += 4
                    }
                }
            }
        }
        out.append(c)
        v++
    }
    return out.toString()
}

/** The object or array that follows `key`, as text; null when there is none or it is not closed. */
private fun extractBlock(buf: String, key: String): String? {
    val v = findValue(buf, key)
    if (v < 0 || v >= buf.length) return null
    val open = buf[v]
    if (open != '{' && open != '[') return null
    val close = if (open == '{') '}' else ']'
    var depth = 0
    var inStr = false
    var p = v
    while (p < buf.length) {
        val c = buf[p]
        if (inStr) {
            if (c == '\\' && p + 1 < buf.length) p++
            else if (c == '"') inStr = false
            p++
            continue
        }
        if (c == '"') {
            inStr = true
        } else if (c == open) {
            depth++
        } else if (c == close && --depth == 0) {
            return buf.substring(v, p + 1)
        }
        p++
    }
    return null
}

/** A block from [extractBlock] parsed: a JSONObject or a JSONArray, null when it does not parse. */
private fun parseValue(text: String): Any? = try {
    val v = JSONTokener(text).nextValue()
    if (v is JSONObject || v is JSONArray) v else null
} catch (e: Exception) {
    null
}

/** A number, or a string read as atoi() does; [def] otherwise. */
private fun jsonInt(it: Any?, def: Int): Int = when (it) {
    is Number -> it.toDouble().toInt()
    is String -> atoi(it)
    else -> def
}

/** Newer firmwares pack current and target into one integer. null when [raw] is not a number. */
private fun unpackTemp(raw: Any?): Pair<Float, Float>? {
    if (raw !is Number) return null
    val v = raw.toDouble().toLong() and 0xFFFFFFFFL
    return (v and 0xFFFF).toFloat() to ((v shr 16) and 0xFFFF).toFloat()
}

/**
 * RRGGBBAA: the alpha dropped. Not [colorFromHex]: the firmware takes the
 * first six characters as strtoul() reads them, and anything shorter than six
 * is "unknown".
 */
private fun parseRgb(s: String?): Int {
    if (s == null || s.length < 6) return COLOR_UNKNOWN
    val six = s.substring(0, 6)
    var i = 0
    while (i < six.length && isCSpace(six[i])) i++
    var neg = false
    if (i < six.length && (six[i] == '+' || six[i] == '-')) {
        neg = six[i] == '-'
        i++
    }
    if (i + 2 < six.length && six[i] == '0' && (six[i + 1] == 'x' || six[i + 1] == 'X') &&
        Character.digit(six[i + 2], 16) >= 0
    ) i += 2
    var v = 0L
    while (i < six.length) {
        val d = Character.digit(six[i], 16)
        if (d < 0) break
        v = v * 16 + d
        i++
    }
    if (neg) v = -v
    return (v and 0xffffff).toInt()
}

private fun asciiUpper(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in 'a'..'z') c - 32 else c)
    return sb.toString()
}

// ---- MQTT ----------------------------------------------------------------------

/** Stands in for the MAC the firmware puts in its client id: the same for every link of this run. */
private val rng = SecureRandom()
private val deviceTag: Int by lazy { rng.nextInt(0x1000000) }

/** One id per connection: a shared one makes the broker drop the other session. */
private fun newClientId(): String =
    String.format(Locale.ROOT, "tigertouch-%06x-%02x", deviceTag, rng.nextInt(256))

/**
 * One MQTT session that keeps itself up, as an esp-mqtt client does: on a
 * failed attempt or a dropped session, a new attempt 5 s later, for ever, until
 * [close]. Paho's own automatic reconnect (an exponential backoff up to two
 * minutes) is not used, so the timing is the firmware's.
 */
private class MqttConn private constructor(
    private val client: MqttAsyncClient,
    private val options: MqttConnectOptions,
    parent: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(c: MqttConn)
        fun onLost(c: MqttConn)
        /** The broker answered the CONNECT with a refusal (bad credentials, ...). */
        fun onRefused(c: MqttConn, code: Int)
        fun onMessage(c: MqttConn, topic: String, text: String)
    }

    private val scope = CoroutineScope(SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO)
    @Volatile private var closed = false
    @Volatile private var lost: CompletableDeferred<Unit>? = null

    init {
        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "disconnected: ${cause?.javaClass?.simpleName ?: "?"}")
                lost?.complete(Unit)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                // Nothing may escape this callback: Paho drops the session when it throws.
                val bytes = message?.payload ?: return
                if (bytes.isEmpty() || bytes.size > MAX_REPORT) return
                try {
                    listener.onMessage(this@MqttConn, topic ?: "", String(bytes, Charsets.UTF_8))
                } catch (e: Exception) {
                    Log.w(TAG, "report not handled", e)
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
    }

    fun start() {
        scope.launch { loop() }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val gone = CompletableDeferred<Unit>()
            lost = gone
            try {
                connectAwait()
                listener.onConnected(this)
                gone.await()
                listener.onLost(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MqttException) {
                val code = e.reasonCode
                // A CONNACK refusal is 1..5 (protocol, client id, unavailable,
                // bad user name or password, not authorised): esp-mqtt's
                // MQTT_ERROR_TYPE_CONNECTION_REFUSED.
                if (code in 1..5) {
                    Log.w(TAG, "broker refused the session (code $code)")
                    listener.onRefused(this, code)
                } else {
                    Log.w(TAG, "connection failed (reason $code)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "connection failed: ${e.javaClass.simpleName}")
            }
            delay(RECONNECT_MS)
        }
    }

    private suspend fun connectAwait() = suspendCancellableCoroutine<Unit> { cont ->
        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    // Connected after close(): drop it at once.
                    if (closed) shutdown()
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

    fun subscribe(topic: String) {
        try {
            client.subscribe(topic, 0)
        } catch (e: Exception) {
            Log.w(TAG, "subscribe failed: ${e.javaClass.simpleName}")
        }
    }

    fun unsubscribe(topic: String) {
        try {
            client.unsubscribe(topic)
        } catch (e: Exception) {
            Log.w(TAG, "unsubscribe failed: ${e.javaClass.simpleName}")
        }
    }

    /** true when the message was handed to the client. */
    fun publish(topic: String, payload: String, qos: Int): Boolean = try {
        client.publish(topic, payload.toByteArray(Charsets.UTF_8), qos, false)
        true
    } catch (e: Exception) {
        false
    }

    /** Never throws. The disconnect waits on Paho's threads, so it runs on a thread of its own. */
    fun close() {
        closed = true
        try {
            scope.cancel()
        } catch (e: Exception) {
        }
        shutdown()
    }

    private fun shutdown() {
        Thread({
            try {
                client.disconnectForcibly(0, 1000)
            } catch (e: Throwable) {
            }
            try {
                client.close()
            } catch (e: Throwable) {
            }
        }, "bambu-mqtt-close").start()
    }

    companion object {
        /**
         * null when the client cannot be made (a malformed address): the
         * firmware's failed esp_mqtt_client_init().
         *
         * [lan]: the cloud broker has a public certificate, checked the normal
         * way. A printer in LAN mode presents its own, which no store holds: it
         * is reached by address, on the local network, with its access code.
         */
        fun create(uri: String, user: String, password: String, lan: Boolean, parent: CoroutineScope, listener: MqttConn.Listener): MqttConn? =
            try {
                val client = MqttAsyncClient(uri, newClientId(), MemoryPersistence())
                val o = MqttConnectOptions()
                o.setUserName(user)
                o.setPassword(password.toCharArray())
                o.setKeepAliveInterval(KEEPALIVE_S)
                o.setConnectionTimeout(CONNECT_TIMEOUT_S)
                o.setCleanSession(true)
                o.setAutomaticReconnect(false)
                // Paho's own check goes through SSLParameters' endpoint
                // identification, which Android does not apply everywhere; the
                // host name is checked after the handshake by the verifier instead.
                o.setHttpsHostnameVerificationEnabled(false)
                if (lan) {
                    o.setSocketFactory(Net.lanSslSocketFactory)
                } else {
                    o.setSocketFactory(SSLSocketFactory.getDefault())
                    o.setSSLHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
                }
                MqttConn(client, o, parent, listener)
            } catch (e: Exception) {
                Log.w(TAG, "no MQTT client for $uri: ${e.javaClass.simpleName}")
                null
            }
    }
}

// ---- One cloud session for every cloud printer of the account --------------------

/*
 * Every Bambu Lab reached through the cloud talks to the same regional broker
 * with the same credentials, so they share ONE MQTT session, as on TigerSpool
 * and the firmware: the first printer opens it, each further one is a
 * subscription on it. Messages are routed to their printer by topic. A printer
 * in LAN mode keeps a client of its own.
 */
private object CloudSession : MqttConn.Listener {
    private val subs = ArrayList<BambuLink>()
    private var conn: MqttConn? = null
    private var connected = false

    /**
     * Joins the account's cloud session, opening it with these credentials
     * when this is the first cloud printer. false when it could not be opened.
     */
    fun join(b: BambuLink, s: BambuSession, env: DriverEnv): Boolean {
        val (c, already) = synchronized(this) {
            // stop() raced the session read: stay out (leave() takes this lock too).
            if (b.stopped) return true
            var cur = conn
            if (cur == null) {
                cur = MqttConn.create(
                    "ssl://${s.region}.mqtt.bambulab.com:8883", s.user, s.token, false, env.scope, this,
                ) ?: return false
                conn = cur
                connected = false
                Log.i(TAG, "opening the cloud session")
                cur.start()
            }
            b.conn = cur
            subs += b
            cur to connected
        }
        if (already) {
            b.subConnected(true)
            c.subscribe(b.topicReport)
            b.pushall()
        }
        return true
    }

    fun leave(b: BambuLink) {
        val closing: MqttConn? = synchronized(this) {
            if (!subs.remove(b)) return
            if (connected && subs.isNotEmpty()) conn?.unsubscribe(b.topicReport)
            if (subs.isEmpty()) {
                val last = conn
                conn = null
                connected = false
                last
            } else {
                null
            }
        }
        // Outside the lock: closing waits for the client's threads.
        if (closing != null) {
            closing.close()
            Log.i(TAG, "cloud session closed")
        }
    }

    /** The printers on [c], or null when [c] is a session already closed. */
    private fun subsOf(c: MqttConn): List<BambuLink>? = synchronized(this) {
        if (c !== conn) null else subs.toList()
    }

    override fun onConnected(c: MqttConn) {
        val list = synchronized(this) {
            if (c !== conn) return
            connected = true
            subs.toList()
        }
        Log.i(TAG, "connected to the cloud broker (${list.size} printer(s) on the session)")
        for (b in list) {
            // A session starts clean: every subscription is asked for again.
            b.subConnected(true)
            c.subscribe(b.topicReport)
            b.pushall()
        }
    }

    override fun onLost(c: MqttConn) {
        val list = synchronized(this) {
            if (c !== conn) return
            connected = false
            subs.toList()
        }
        Log.w(TAG, "cloud session disconnected")
        for (b in list) b.subConnected(false)
    }

    override fun onRefused(c: MqttConn, code: Int) {
        val list = subsOf(c) ?: return
        for (b in list) b.setNotice(txtRefused())
    }

    override fun onMessage(c: MqttConn, topic: String, text: String) {
        val list = subsOf(c) ?: return
        list.firstOrNull { it.topicReport == topic }?.handleReport(text)
    }
}

// ---- One printer -----------------------------------------------------------------

private class BambuLink(p: AccountPrinter, env: DriverEnv) : PrinterLink, MqttConn.Listener {
    private val lock = Any()
    private val sn = p.sn
    val topicReport = "device/$sn/report"
    private val topicRequest = "device/$sn/request"

    /** The local broker: the only connection that takes pause / resume / stop. */
    private val lan = !p.cloud

    /** Own (LAN) or the account's shared one (cloud). */
    @Volatile var conn: MqttConn? = null
    @Volatile var stopped = false

    private val scope = CoroutineScope(SupervisorJob(env.scope.coroutineContext[Job]) + Dispatchers.IO)

    // Everything below is guarded by [lock].
    private var connected = false
    /** "chamber_light", "work_light"...: what lights_report names. */
    private var lightNode = ""
    private var lightOn = false
    /** sequence_id of the commands: a new one each time, as Tiger Studio's. */
    private var seq = 0L
    private var lastReportMs = 0L
    private var lastPushallMs = 0L
    private var notice = ""

    private var nozzle = 0f
    private var nozzleTarget = 0f
    private var bed = 0f
    private var bedTarget = 0f
    private var chamber = 0f

    /*
     * The extruders a dual-head printer reports (device.extruder.info[]), kept
     * by their own id: 0 is the right nozzle, 1 the left.
     */
    /** bit i: id i has been read */
    private var nzSeen = 0
    /** The id at work, -1 when not said. */
    private var nzActive = -1
    private val nzTemp = FloatArray(PV_MAX_NOZZLES)
    private val nzTarget = FloatArray(PV_MAX_NOZZLES)

    private var state = 0
    private var progress = 0
    private var layer = 0
    private var totalLayer = 0
    private var leftS = 0
    private var file = ""
    private val units = Array(PV_MAX_UNITS) { PvUnit() }
    /** The AMS id behind each AMS unit, -1 for none / the external spool. */
    private val unitUid = IntArray(PV_MAX_UNITS) { -1 }
    /** Extra full reports asked for while no AMS has been seen. */
    private var amsRetries = 0

    private val view = PrinterView(name = p.name, brand = p.brand)

    fun begin(p: AccountPrinter, env: DriverEnv) {
        if (lan) {
            // In LAN mode the printer is its own broker: fixed user, the access
            // code is the password.
            val c = MqttConn.create("ssl://" + Addr.withPort(p.ip, 8883), "bblp", p.cc, true, scope, this)
            if (c == null) {
                setNotice(txtCloudFail())
            } else {
                conn = c
                Log.i(TAG, "connecting to a Bambu Lab broker")
                c.start()
            }
        } else {
            scope.launch {
                val (e, s) = try {
                    env.cloud.bambuSession()
                } catch (x: CancellationException) {
                    throw x
                } catch (x: Exception) {
                    CloudErr.NETWORK to null
                }
                if (e != CloudErr.OK || s == null) {
                    // Alive, but silent: the notice tells why.
                    Log.w(TAG, "no cloud session ($e)")
                    setNotice(if (e == CloudErr.NOT_FOUND) txtMissing() else txtAccountSilent())
                    return@launch
                }
                if (!CloudSession.join(this@BambuLink, s, env)) setNotice(txtCloudFail())
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                housekeeping()
            }
        }
    }

    fun setNotice(text: String) {
        synchronized(lock) { notice = text }
    }

    /** The cloud session went up or down. */
    fun subConnected(on: Boolean) {
        synchronized(lock) {
            connected = on
            if (on) {
                amsRetries = 0
                notice = ""
            }
        }
    }

    fun pushall() {
        val c = conn ?: return
        synchronized(lock) { if (!connected) return }
        c.publish(topicRequest, PUSHALL, 0)
        synchronized(lock) { lastPushallMs = now() }
    }

    /** The firmware does this at the top of its snapshot; here it runs once a second on its own. */
    private fun housekeeping() {
        val t = now()
        // A printer that went quiet is asked again, not left on stale numbers.
        val quiet = synchronized(lock) {
            connected && t - lastReportMs > PUSHALL_EVERY_MS && t - lastPushallMs > PUSHALL_EVERY_MS
        }
        if (quiet) pushall()

        // Joined in the middle of a print, the first full report can come without
        // the AMS: ask a few more times before concluding there is none.
        val again = synchronized(lock) {
            if (connected && lastReportMs != 0L && amsRetries < AMS_RETRIES && t - lastPushallMs > AMS_RETRY_AFTER_MS) {
                val any = units.any { it.present && it.label != PV_LABEL_EXT }
                if (!any) amsRetries++
                !any
            } else {
                false
            }
        }
        if (again) pushall()
    }

    // ---- MqttConn.Listener (LAN only: the cloud goes through CloudSession) ----

    override fun onConnected(c: MqttConn) {
        Log.i(TAG, "connected to the printer's own broker")
        synchronized(lock) {
            connected = true
            amsRetries = 0
            notice = ""
        }
        // A session starts clean: the subscription must be asked for again, and
        // a first full report is what the partial ones apply to.
        c.subscribe(topicReport)
        pushall()
    }

    override fun onLost(c: MqttConn) {
        synchronized(lock) { connected = false }
    }

    override fun onRefused(c: MqttConn, code: Int) {
        setNotice(txtRefused())
    }

    override fun onMessage(c: MqttConn, topic: String, text: String) {
        handleReport(text)
    }

    // ---- Report handling ----

    /** The temperatures being read, starting from the kept ones. */
    private class Temps(var nz: Float, var nzt: Float, var bd: Float, var bdt: Float, var ch: Float)

    fun handleReport(buf: String) {
        if (!buf.contains("\"print\"")) return // info, system, ...

        var st = -1
        var prog = -1
        var lay = -1
        var total = -1
        var left = -1
        var name = ""
        var haveFile = false

        getStr(buf, "\"gcode_state\"")?.let { s ->
            st = when (asciiUpper(s)) {
                "RUNNING", "PREPARE", "SLICING" -> 1
                "PAUSE" -> 2
                "FINISH" -> 3
                "FAILED" -> 4
                else -> 0
            }
        }
        // mc_percent counts the preparation phase too.
        getNum(buf, "\"mc_percent\"")?.let { prog = it.toInt() }
        getNum(buf, "\"layer_num\"")?.let { lay = it.toInt() }
        getNum(buf, "\"total_layer_num\"")?.let { total = it.toInt() }
        (getNum(buf, "\"mc_remaining_time\"") ?: getNum(buf, "\"remaining_time\""))?.let {
            left = it.toInt() * 60 // minutes on the wire
        }
        val sub = getStr(buf, "\"subtask_name\"")
        if (!sub.isNullOrEmpty()) {
            name = sub
            haveFile = true
        } else {
            val g = getStr(buf, "\"gcode_file\"")
            if (!g.isNullOrEmpty()) {
                name = g.substringAfterLast('/')
                haveFile = true
            }
        }
        if (haveFile) {
            var dot = name.indexOf(".gcode")
            if (dot < 0) dot = name.indexOf(".3mf")
            if (dot >= 0) name = name.substring(0, dot)
        }

        // The light: `lights_report` is a list of {"node": ..., "mode": ...}, in
        // either key order; the chamber's own light when there is one, else the
        // first. Each element is read on its own: reading a "mode" that follows a
        // node would take the next element's when the keys come the other way round.
        var node = ""
        var light = -1
        val lr = buf.indexOf("\"lights_report\"")
        val arr = if (lr >= 0) buf.indexOf('[', lr) else -1
        val arrEnd = if (arr >= 0) buf.indexOf(']', arr) else -1
        var cur = arr
        while (cur >= 0 && arrEnd >= 0 && cur < arrEnd) {
            val open = buf.indexOf('{', cur)
            val close = if (open >= 0) buf.indexOf('}', open) else -1
            if (open < 0 || close < 0 || close > arrEnd) break
            val obj = buf.substring(open, close + 1)
            val n = getStr(obj, "\"node\"")
            val m = getStr(obj, "\"mode\"")
            if (n != null && m != null) {
                val chamberLight = n == "chamber_light"
                if (light < 0 || chamberLight) {
                    node = n
                    light = if (m == "on") 1 else 0
                }
                if (chamberLight) break
            }
            cur = close + 1
        }

        synchronized(lock) {
            val t = Temps(nozzle, nozzleTarget, bed, bedTarget, chamber)

            // Older firmwares: plain keys.
            getNum(buf, "\"nozzle_temper\"")?.let { t.nz = it.toFloat() }
            getNum(buf, "\"nozzle_target_temper\"")?.let { t.nzt = it.toFloat() }
            getNum(buf, "\"bed_temper\"")?.let { t.bd = it.toFloat() }
            getNum(buf, "\"bed_target_temper\"")?.let { t.bdt = it.toFloat() }
            getNum(buf, "\"chamber_temper\"")?.let { t.ch = it.toFloat() }

            // Newer firmwares (the P2S among them): packed in `device`. Read
            // after the plain keys so that it wins when both are present.
            extractBlock(buf, "\"device\"")?.let { applyDevice(it, t) }
            extractBlock(buf, "\"ams\"")?.let { applyAms(it) }
            applyExt(buf)

            nozzle = t.nz
            nozzleTarget = t.nzt
            bed = t.bd
            bedTarget = t.bdt
            chamber = if (hasChamberSensor(sn)) t.ch else 0f // 0 is drawn "--"
            if (light >= 0) {
                if (lightOn != (light == 1) || lightNode != node) {
                    Log.i(TAG, "light report: $node is ${if (light == 1) "on" else "off"}")
                }
                lightOn = light == 1
                lightNode = node
            }
            if (st >= 0) state = st
            if (prog >= 0) progress = prog
            if (lay >= 0) layer = lay
            if (total >= 0) totalLayer = total
            if (left >= 0) leftS = left
            if (haveFile) file = name
            lastReportMs = now()
        }
    }

    /** Under [lock]. */
    private fun applyDevice(text: String, t: Temps) {
        val dev = parseValue(text) as? JSONObject ?: return
        val ext = dev.opt("extruder")
        if (ext != null) {
            val extObj = ext as? JSONObject
            val active = (jsonInt(extObj?.opt("state"), 0) shr 4) and 0xF
            val info = extObj?.opt("info") as? JSONArray
            var pick: Any? = null
            if (info != null) {
                for (i in 0 until info.length()) {
                    val e = info.opt(i)
                    if (pick == null) pick = e
                    val eo = e as? JSONObject
                    val id = jsonInt(eo?.opt("id"), -1)
                    if (id in 0 until PV_MAX_NOZZLES) {
                        val (tc, tg) = unpackTemp(eo?.opt("temp")) ?: (0f to 0f)
                        nzTemp[id] = tc
                        nzTarget[id] = tg
                        nzSeen = nzSeen or (1 shl id)
                        nzActive = active
                    }
                    if (id == active) pick = e
                }
            }
            if (pick != null) {
                unpackTemp((pick as? JSONObject)?.opt("temp"))?.let { (c, g) ->
                    t.nz = c
                    t.nzt = g
                }
            }
        }
        val bedObj = dev.opt("bed")
        if (bedObj != null) {
            unpackTemp(((bedObj as? JSONObject)?.opt("info") as? JSONObject)?.opt("temp"))?.let { (c, g) ->
                t.bd = c
                t.bdt = g
            }
        }
        val ctc = dev.opt("ctc")
        if (ctc != null) {
            unpackTemp(((ctc as? JSONObject)?.opt("info") as? JSONObject)?.opt("temp"))?.let { (c, _) ->
                t.ch = c
            }
        }
    }

    /**
     * The `ams` block, under [lock]. While a print runs the printer sends
     * partial updates: a block with only `tray_now`, or one unit with one tray.
     * So the units are kept and only what the block carries is changed: a block
     * that lists no units (no `ams` array) must never empty the cards.
     */
    private fun applyAms(text: String) {
        val root = parseValue(text) ?: return
        val obj = root as? JSONObject
        val list = obj?.opt("ams") as? JSONArray
        val hasNow = obj != null && obj.has("tray_now")
        val nowId = when (val n = obj?.opt("tray_now")) {
            is String -> atoi(n)
            is Number -> n.toInt()
            else -> -1
        }

        // What is known: the AMS units (without the external spool).
        val keep = ArrayList<PvUnit>()
        val keepUid = ArrayList<Int>()
        var ext: PvUnit? = null
        for (j in 0 until PV_MAX_UNITS) {
            if (!units[j].present) continue
            if (units[j].label == PV_LABEL_EXT) {
                ext = units[j].copy()
            } else {
                keep += units[j].copy()
                keepUid += unitUid[j]
            }
        }

        if (list != null) {
            for (i in 0 until list.length()) {
                val u = list.opt(i) as? JSONObject ?: continue
                val uid = jsonInt(u.opt("id"), -1)
                if (uid < 0) continue
                var idx = keepUid.indexOf(uid)
                if (idx < 0) {
                    if (keep.size >= PV_MAX_UNITS - (if (ext != null) 1 else 0)) continue
                    keep += PvUnit()
                    keepUid += uid
                    idx = keep.size - 1
                }
                val pu = keep[idx]
                pu.present = true
                pu.slots = if (uid >= 128) 1 else 4 // one heated slot, numbered from 128

                val trays = u.opt("tray") as? JSONArray ?: continue
                for (k in 0 until trays.length()) {
                    val tr = trays.opt(k) as? JSONObject ?: continue
                    val id = jsonInt(tr.opt("id"), -1)
                    if (id < 0 || id > 3) continue
                    val sl = pu.slot[id]
                    val type = tr.opt("tray_type")
                    val color = tr.opt("tray_color")
                    val remain = tr.opt("remain")
                    // A tray in a partial update carries only what changed.
                    if (type != null) {
                        sl.present = type is String && type.isNotEmpty()
                        sl.material = if (type is String) type else ""
                    }
                    if (color is String) sl.color = parseRgb(color)
                    if (remain != null) {
                        val r = jsonInt(remain, -1)
                        sl.percent = if (r < 0 || r > 100) -1 else r
                    }
                }
            }
        }

        // Labels: "AMS" alone, "AMS A".. when there are several, "AMS HT".
        val plain = keepUid.count { it < 128 }
        for (j in keep.indices) {
            keep[j].label = when {
                keepUid[j] >= 128 -> PV_LABEL_HT
                plain > 1 -> "AMS " + ('A' + (keepUid[j] % 26))
                else -> "AMS"
            }
        }

        // The tray in use, when this block says which.
        if (hasNow) {
            for (j in keep.indices) {
                for (i in 0 until 4) {
                    keep[j].slot[i].selected = if (keepUid[j] >= 128) {
                        i == 0 && nowId == keepUid[j]
                    } else {
                        nowId == keepUid[j] * 4 + i
                    }
                }
            }
        }

        for (j in 0 until PV_MAX_UNITS) {
            units[j] = PvUnit()
            unitUid[j] = -1
        }
        var n = 0
        for (j in keep.indices) {
            if (n >= PV_MAX_UNITS) break
            units[n] = keep[j]
            unitUid[n] = keepUid[j]
            n++
        }
        val e = ext
        if (e != null && n < PV_MAX_UNITS) units[n] = e
    }

    /**
     * The external spool, under [lock]: `vt_tray` on older firmwares, the first
     * `vir_slot` on newer ones. It becomes the "Ext." unit, after the AMS units.
     */
    private fun applyExt(buf: String) {
        var ext: Any? = null
        val vt = extractBlock(buf, "\"vt_tray\"")
        if (vt != null) {
            ext = parseValue(vt)
        } else {
            val vs = extractBlock(buf, "\"vir_slot\"")
            if (vs != null) ext = (parseValue(vs) as? JSONArray)?.opt(0)
        }
        if (ext == null) return

        val nowId = getStr(buf, "\"tray_now\"")?.let { atoi(it) } ?: -1
        val o = ext as? JSONObject
        val type = o?.opt("tray_type")
        val color = o?.opt("tray_color")

        val u = PvUnit(present = true, label = PV_LABEL_EXT, slots = 1)
        u.slot[0].present = type is String && type.isNotEmpty()
        u.slot[0].color = parseRgb(color as? String)
        u.slot[0].percent = -1
        u.slot[0].selected = nowId == 254
        if (type is String) u.slot[0].material = type

        var at = -1
        for (j in 0 until PV_MAX_UNITS) {
            if (units[j].present && units[j].label == PV_LABEL_EXT) {
                at = j
                break
            }
        }
        // No Ext. yet: the last free place (the firmware's loop does not stop at
        // the first); the next `ams` block moves it after the AMS units.
        if (at < 0) {
            for (j in 0 until PV_MAX_UNITS) if (!units[j].present) at = j
        }
        if (at >= 0) {
            units[at] = u
            unitUid[at] = -1
        }
    }

    // ---- PrinterLink ----

    override fun snapshot(): PrinterView = synchronized(lock) {
        val t = now()
        val v = view
        v.online = connected && lastReportMs != 0L && (t - lastReportMs) < ONLINE_WINDOW_MS
        v.canControl = v.online && lan
        v.canLight = v.online
        v.lightOn = lightOn
        v.state = when (state) {
            1 -> PvState.PRINTING
            2 -> PvState.PAUSED
            3 -> PvState.DONE
            4 -> PvState.ERROR
            else -> PvState.IDLE
        }
        v.progress = if (v.state == PvState.IDLE) 0 else progress
        v.layer = layer
        v.totalLayer = totalLayer
        v.leftS = if (v.state == PvState.PRINTING || v.state == PvState.PAUSED) leftS else 0
        v.nozzle = nozzle
        v.nozzleTarget = nozzleTarget
        // Two extruders or more: one tile each. With two, the tiles read left to
        // right, which is id 1 then id 0 (id 0 is the right nozzle).
        v.nozzleCount = 0
        v.nozzleActive = -1
        val seen = Integer.bitCount(nzSeen and ((1 shl PV_MAX_NOZZLES) - 1))
        if (seen >= 2) {
            val n = if (seen > PV_MAX_NOZZLES) PV_MAX_NOZZLES else seen
            v.nozzleCount = n
            for (pos in 0 until n) {
                val id = if (n == 2) 1 - pos else pos
                v.nozzles[pos].temp = nzTemp[id]
                v.nozzles[pos].target = nzTarget[id]
                v.nozzles[pos].label = if (n == 2) {
                    if (pos == 0) Tr.t("Left", "Gauche") else Tr.t("Right", "Droite")
                } else {
                    Tr.t("Nozzle ${pos + 1}", "Buse ${pos + 1}")
                }
                if (id == nzActive) v.nozzleActive = pos
            }
            if (v.nozzleActive >= 0) {
                v.nozzle = v.nozzles[v.nozzleActive].temp
                v.nozzleTarget = v.nozzles[v.nozzleActive].target
            }
        }
        v.bed = bed
        v.bedTarget = bedTarget
        v.chamber = chamber
        v.file = file
        v.notice = notice
        v.noticeError = notice.isNotEmpty()
        for (j in 0 until PV_MAX_UNITS) v.unit[j] = units[j].copy()
        v.copyDeep()
    }

    /*
     * Pause, resume, stop: the frames Tiger Studio sends on device/<sn>/request.
     * Refused on a cloud connection, as Studio does: the cloud broker takes no
     * such command from this session, and a refused command is never reported
     * back, so it is better not offered. Written from the protocol notes; not
     * run against a printer in LAN mode.
     */
    override fun control(cmd: PvControl): Boolean {
        val c = conn
        val isConnected = synchronized(lock) { connected }
        // The light goes through the cloud broker as well as the local one, as in
        // Tiger Studio; a pause, a resume and a stop only through the local one.
        if (cmd == PvControl.LIGHT_ON || cmd == PvControl.LIGHT_OFF) {
            if (c == null || !isConnected) return false
            val on = cmd == PvControl.LIGHT_ON
            val (node, s) = synchronized(lock) {
                lightNode.ifEmpty { "chamber_light" } to seq++ % 4087
            }
            val req = "{\"system\":{\"sequence_id\":\"$s\",\"command\":\"ledctrl\",\"led_node\":\"$node\"," +
                "\"led_mode\":\"${if (on) "on" else "off"}\",\"led_on_time\":500,\"led_off_time\":500,\"loop_times\":0," +
                "\"interval_time\":0}}"
            // QoS 1, as Tiger Studio publishes to the cloud broker: at QoS 0 the
            // broker may drop it without a word.
            val ok = c.publish(topicRequest, req, 1)
            Log.i(
                TAG,
                "light ${if (on) "on" else "off"} on $node (${if (lan) "local broker" else "cloud broker"}): " +
                    if (ok) "published" else "refused",
            )
            return ok
        }
        if (!lan || !isConnected || c == null) return false
        val req = when (cmd) {
            PvControl.PAUSE -> "{\"print\":{\"sequence_id\":\"1\",\"command\":\"pause\"}}"
            PvControl.RESUME -> "{\"print\":{\"sequence_id\":\"2\",\"command\":\"resume\"}}"
            PvControl.STOP -> "{\"print\":{\"sequence_id\":\"3\",\"command\":\"stop\"}}"
            else -> return false
        }
        return c.publish(topicRequest, req, 0)
    }

    override fun stop() {
        stopped = true
        try {
            scope.cancel()
        } catch (e: Exception) {
        }
        try {
            if (lan) conn?.close() else CloudSession.leave(this)
        } catch (e: Exception) {
        }
    }
}

// ---- Driver --------------------------------------------------------------------

object BambuDriver : PrinterDriver {
    override val brand: String = "bambulab"

    override fun support(p: AccountPrinter): DrvKind {
        if (p.sn.isEmpty()) return DrvKind.NONE
        if (p.cloud) return DrvKind.CLOUD
        // In LAN mode the printer is its own broker: its address and its access
        // code are what is needed.
        return if (p.ip.isNotEmpty() && p.cc.isNotEmpty()) DrvKind.LAN else DrvKind.NONE
    }

    override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink {
        val link = BambuLink(p, env)
        link.begin(p, env)
        return link
    }
}
