package com.damou.tigertouch.cloud

import android.content.Context
import android.util.Base64
import android.util.Log
import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.Brands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/*
 * The TigerTag account over Firebase REST: the firmware's net/cloud.c.
 *
 * Same Firebase project and Firestore layout as Tiger Studio, TigerScale and
 * TigerSpool. The password is only ever used for one request and is never
 * stored: the refresh token is what is kept.
 */

enum class CloudErr {
    OK,
    /** No answer / TLS / timeout. */
    NETWORK,
    /** Wrong e-mail or password, or session revoked. */
    CREDENTIALS,
    /** Account created with Google: pair instead. */
    NO_PASSWORD,
    /** Too many attempts. */
    RATE,
    DISABLED,
    /** The document does not exist. */
    NOT_FOUND,
    OTHER;

    fun text(): String = when (this) {
        OK -> ""
        NETWORK -> tr("The account does not answer. Check the Wi-Fi.", "Le compte ne répond pas. Vérifiez le Wi-Fi.")
        CREDENTIALS -> tr("Wrong e-mail or password.", "E-mail ou mot de passe incorrect.")
        NO_PASSWORD -> tr(
            "This account uses Google: sign in with the QR code.",
            "Ce compte utilise Google : connectez-vous avec le QR code.",
        )
        RATE -> tr("Too many attempts, try again later.", "Trop d'essais, réessayez plus tard.")
        DISABLED -> tr("This account is disabled.", "Ce compte est désactivé.")
        NOT_FOUND -> tr("Not found.", "Introuvable.")
        OTHER -> tr("Something went wrong.", "Une erreur est survenue.")
    }
}

/** What the account icon says. */
enum class CloudLink { NONE, CONNECTING, OK }

/** Bambu Lab's cloud session, written to the account by Tiger Studio. */
data class BambuSession(val user: String, val token: String, val region: String)

/** Anycubic in cloud mode: the account and the shared client identity Tiger Studio writes. */
data class AnycubicIdentity(
    val email: String,
    val token: String,
    val caDerB64: String,
    val certPem: String,
    val keyPem: String,
)

data class PairStart(val verifyUrl: String, val code: String, val pollToken: String, val intervalS: Int)

enum class PairStatus { PENDING, APPROVED, DENIED, EXPIRED, ERROR }

/** Result of [Cloud.fetchPrinters]. */
data class FetchResult(val err: CloudErr, val printers: List<AccountPrinter>, val failedBrands: Int)

/** The device's presence in the account: users/{uid}/tigertouches/{mac}. */
data class Presence(
    /** 12 lowercase hex digits: the document id. */
    val mac: String,
    val host: String,
    val fw: String,
    val rssi: Int,
    val ip: String,
    val screenOff: Boolean,
    val battery: Boolean,
    val demo: Boolean,
    val ids: List<String>,
    val online: List<Boolean>,
    /** Identity fields too (at start and after a sign-in). */
    val full: Boolean,
)

private fun tr(en: String, fr: String) = com.damou.tigertouch.drivers.Tr.t(en, fr)

class Cloud(context: Context, private val http: OkHttpClient) {

    private companion object {
        const val TAG = "cloud"

        /** TigerTag's public Firebase client key: served without authentication at
         * https://tigertag-cdn.web.app/__/firebase/init.json, not a secret. */
        const val API_KEY = "AIzaSyCkxPTs_Cv0KVLqsZj-UKWWqIY0OtfVpnw"
        const val PROJECT = "tigertag-connect"
        const val FS = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents"
        const val PAIR_START_URL = "https://us-central1-tigertag-connect.cloudfunctions.net/pairStart"
        const val PAIR_POLL_URL = "https://us-central1-tigertag-connect.cloudfunctions.net/pairPoll"

        const val TOKEN_FRESH_MS = 50L * 60 * 1000 // a token is reused this long
        const val KEEPALIVE_AGE_MS = 45L * 60 * 1000 // renewed before it can lapse
        const val KEEPALIVE_RETRY_MS = 30L * 1000

        val JSON = "application/json".toMediaType()

        /** Server-side field mask: Firestore sends only these. */
        val MASK = listOf(
            "printerName", "name", "ip", "broker", "updatedAt", "ipAddress", "lanIp", "host",
            "mode", "printerModelId", "modelId", "discovery.ip", "discovery.transport",
            "serial", "serialNumber", "sn", "deviceId", "discovery.deviceSn",
            "password", "dev_access_code", "accessCode", "access_code", "checkCode", "mqttPassword",
            "username", "acuModelId", "apiKey", "protocol", "mainboardId",
            "discovery.protocol", "discovery.mainboardId",
        )

        /** Anycubic in cloud mode, asked of that brand only. */
        val MASK_ACU_CLOUD = listOf(
            "key", "machineType", "cloudPrinterId", "cloudEmail", "cloudToken",
            "acuCloudCaDerB64", "acuCloudClientCertPem", "acuCloudClientKeyPem",
        )

        /** "broker": where Tiger Studio writes a Bambu Lab printer's LAN address. */
        val IP_KEYS = listOf("ip", "broker", "ipAddress", "lanIp", "host")
        val MODEL_KEYS = listOf("printerModelId", "modelId")
        val SN_KEYS = listOf("serial", "serialNumber", "sn", "deviceId")
        val CC_KEYS = listOf("password", "dev_access_code", "accessCode", "access_code", "checkCode", "mqttPassword")
    }

    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val tokenLock = Mutex()

    @Volatile private var idToken = ""
    @Volatile private var refresh = prefs.getString("refresh", "") ?: ""
    @Volatile private var uid = prefs.getString("uid", "") ?: ""
    @Volatile var email: String = prefs.getString("email", "") ?: ""
        private set
    @Volatile private var tokenAt = 0L
    @Volatile private var linkOk = false
    @Volatile private var lastTry = 0L

    @Volatile private var acu: AnycubicIdentity? = null

    // ---- HTTPS ----------------------------------------------------------------

    private class Rx(val status: Int, val body: String?)

    /** The HTTP status, or -1 when the request itself failed. */
    private suspend fun httpDo(url: String, method: String, body: String?, bearer: String?): Rx =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
            if (bearer != null) b.header("Authorization", "Bearer $bearer")
            val rb = body?.toRequestBody(JSON)
            when (method) {
                "GET" -> b.get()
                else -> b.method(method, rb ?: "".toRequestBody(JSON))
            }
            val rx = try {
                http.newCall(b.build()).execute().use { r -> Rx(r.code, r.body?.string()) }
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.javaClass.simpleName}")
                Rx(-1, null)
            }
            // Reachable and answering is what the account icon reports.
            if (rx.status in 200..299) linkOk = true
            else if (rx.status <= 0 || rx.status >= 500) linkOk = false
            rx
        }

    private fun mapError(root: JSONObject?): CloudErr {
        val m = root?.optJSONObject("error")?.optString("message") ?: ""
        Log.w(TAG, "server said: $m")
        return when {
            listOf("EMAIL_NOT_FOUND", "INVALID_PASSWORD", "INVALID_LOGIN_CREDENTIALS", "TOKEN_EXPIRED",
                "INVALID_REFRESH_TOKEN", "INVALID_EMAIL").any { m.contains(it) } -> CloudErr.CREDENTIALS
            m.contains("MISSING_PASSWORD") -> CloudErr.NO_PASSWORD
            m.contains("TOO_MANY_ATTEMPTS") -> CloudErr.RATE
            m.contains("USER_DISABLED") -> CloudErr.DISABLED
            else -> CloudErr.OTHER
        }
    }

    private fun parse(s: String?): JSONObject? = try {
        if (s == null) null else JSONObject(s)
    } catch (e: Exception) {
        null
    }

    private fun saveSession() {
        prefs.edit().putString("refresh", refresh).putString("uid", uid).putString("email", email).apply()
    }

    // ---- Session ----------------------------------------------------------------

    val hasSession: Boolean get() = refresh.isNotEmpty()

    val link: CloudLink
        get() = when {
            refresh.isEmpty() -> CloudLink.NONE
            linkOk -> CloudLink.OK
            else -> CloudLink.CONNECTING
        }

    fun signOut() {
        idToken = ""; refresh = ""; uid = ""; email = ""
        linkOk = false
        prefs.edit().clear().apply()
    }

    suspend fun signIn(mail: String, password: String): CloudErr {
        val body = JSONObject()
            .put("email", mail).put("password", password).put("returnSecureToken", true).toString()
        val rx = httpDo("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$API_KEY",
            "POST", body, null)
        if (rx.status <= 0 || rx.body == null) return CloudErr.NETWORK
        val r = parse(rx.body) ?: return CloudErr.OTHER
        if (rx.status != 200) return mapError(r)
        val tok = r.optString("idToken"); val ref = r.optString("refreshToken"); val id = r.optString("localId")
        if (tok.isEmpty() || ref.isEmpty() || id.isEmpty()) return CloudErr.OTHER
        idToken = tok; refresh = ref; uid = id; email = mail
        tokenAt = System.currentTimeMillis()
        saveSession()
        Log.i(TAG, "signed in")
        return CloudErr.OK
    }

    private suspend fun refreshToken(): CloudErr {
        if (refresh.isEmpty()) return CloudErr.CREDENTIALS
        val body = JSONObject().put("grant_type", "refresh_token").put("refresh_token", refresh).toString()
        val rx = httpDo("https://securetoken.googleapis.com/v1/token?key=$API_KEY", "POST", body, null)
        if (rx.status <= 0 || rx.body == null) return CloudErr.NETWORK
        val r = parse(rx.body) ?: return CloudErr.OTHER
        if (rx.status != 200) {
            val e = mapError(r)
            if (e == CloudErr.CREDENTIALS || e == CloudErr.DISABLED) signOut()
            return e
        }
        idToken = r.optString("id_token")
        val fresh = r.optString("refresh_token")
        if (fresh.isNotEmpty() && fresh != refresh) {
            refresh = fresh
            prefs.edit().putString("refresh", refresh).apply()
        }
        val u = r.optString("user_id")
        if (u.isNotEmpty()) uid = u
        tokenAt = System.currentTimeMillis()
        return if (idToken.isNotEmpty()) CloudErr.OK else CloudErr.OTHER
    }

    suspend fun resume(): CloudErr {
        if (!hasSession) return CloudErr.CREDENTIALS
        return tokenLock.withLock { refreshToken() }
    }

    private fun tokenFresh() = idToken.isNotEmpty() && System.currentTimeMillis() - tokenAt < TOKEN_FRESH_MS

    private suspend fun ensureToken(): CloudErr {
        if (tokenFresh()) return CloudErr.OK
        // Whoever held the lock may have renewed it while we waited.
        return tokenLock.withLock { if (tokenFresh()) CloudErr.OK else refreshToken() }
    }

    /**
     * Keeps the session honest while nothing else uses it: renews the token
     * before it expires and, after a failure, probes again every 30 s.
     */
    suspend fun keepalive() {
        if (refresh.isEmpty()) return
        val now = System.currentTimeMillis()
        val stale = idToken.isEmpty() || now - tokenAt >= KEEPALIVE_AGE_MS
        if (!stale && linkOk) return
        if (lastTry != 0L && now - lastTry < KEEPALIVE_RETRY_MS) return
        lastTry = now
        tokenLock.withLock {
            val justRenewed = idToken.isNotEmpty() && System.currentTimeMillis() - tokenAt < KEEPALIVE_RETRY_MS
            if (!justRenewed) refreshToken()
        }
    }

    // ---- Printers -----------------------------------------------------------------

    /** Firestore REST wraps every value: {"ip": {"stringValue": "1.2.3.4"}}. Integers travel as strings. */
    private fun fsStr(fields: JSONObject?, key: String): String? {
        val f = fields?.optJSONObject(key) ?: return null
        f.opt("stringValue")?.let { if (it is String) return it }
        f.opt("integerValue")?.let { if (it is String) return it }
        return null
    }

    private fun fsMap(fields: JSONObject?, key: String): JSONObject? =
        fields?.optJSONObject(key)?.optJSONObject("mapValue")?.optJSONObject("fields")

    private fun firstOf(fields: JSONObject, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { k -> fsStr(fields, k)?.takeIf { it.isNotEmpty() } }

    /** A time field as milliseconds since 1970, whichever way it was stored. 0 when unreadable. */
    private fun fsMillis(fields: JSONObject, key: String): Long {
        val f = fields.optJSONObject(key) ?: return 0
        (f.opt("integerValue") as? String)?.let { return it.toLongOrNull() ?: 0 }
        (f.opt("doubleValue") as? Number)?.let { return it.toLong() }
        (f.opt("timestampValue") as? String)?.let { return timestampMillis(it) }
        return 0
    }

    /**
     * "2026-09-10T21:20:06.421Z" (always UTC) to milliseconds. java.time is not
     * there before Android 8, so days are counted as the firmware does
     * (Howard Hinnant's days_from_civil).
     */
    private fun timestampMillis(s: String): Long {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?""").find(s) ?: return 0
        val g = m.groupValues
        var y = g[1].toLong(); val mo = g[2].toLong(); val d = g[3].toLong()
        val ms = g[7].take(3).padEnd(3, '0').toLongOrNull() ?: 0
        y -= if (mo <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097 + doe - 719468
        return (days * 86400 + g[4].toLong() * 3600 + g[5].toLong() * 60 + g[6].toLong()) * 1000 + ms
    }

    /** The serial that identifies a machine; FlashForge writes it with or without its "SN" prefix. */
    private fun idSerial(sn: String, real: Boolean) = if (!real) "" else sn.removePrefix("SN")

    /**
     * Two documents describe the same machine, as TigerSpool decides it: the
     * same serial, and either the same address or the same mode. Without a
     * serial, the address is all there is.
     */
    private fun samePrinter(a: AccountPrinter, aSn: Boolean, b: AccountPrinter, bSn: Boolean): Boolean {
        if (a.brand != b.brand) return false
        val sa = idSerial(a.sn, aSn); val sb = idSerial(b.sn, bSn)
        if (sa.isNotEmpty() || sb.isNotEmpty()) return sa == sb && (a.ip == b.ip || a.cloud == b.cloud)
        return a.ip.isNotEmpty() && a.ip == b.ip
    }

    /**
     * Reads every brand. A brand that does not answer keeps the printers it had
     * in [previous]: one slow request must not make printers vanish.
     */
    suspend fun fetchPrinters(previous: List<AccountPrinter>): FetchResult {
        val te = ensureToken()
        if (te != CloudErr.OK) return FetchResult(te, previous, 0)
        val out = ArrayList<AccountPrinter>()
        var failed = 0

        for (brand in Brands.ALL) {
            val acuBrand = brand == "anycubic"
            val mask = (MASK + if (acuBrand) MASK_ACU_CLOUD else emptyList())
                .joinToString("&", prefix = "?") { "mask.fieldPaths=$it" }
            val rx = httpDo("$FS/users/$uid/printers/$brand/devices$mask", "GET", null, idToken)
            Log.i(TAG, "$brand: http ${rx.status}")
            val root = if (rx.status == 200) parse(rx.body) else null
            if (root == null) {
                failed++
                out += previous.filter { it.brand == brand }
                continue
            }
            val docs = root.optJSONArray("documents") ?: JSONArray()
            val mine = ArrayList<Triple<AccountPrinter, Long, Boolean>>() // printer, updatedAt, real serial
            for (i in 0 until docs.length()) {
                val doc = docs.optJSONObject(i) ?: continue
                val fields = doc.optJSONObject("fields") ?: continue
                val id = doc.optString("name").substringAfterLast('/')
                val name = fsStr(fields, "printerName") ?: fsStr(fields, "name") ?: brand
                val disc = fsMap(fields, "discovery")
                val ip = firstOf(fields, IP_KEYS) ?: disc?.let { fsStr(it, "ip") } ?: ""
                val snRaw = firstOf(fields, SN_KEYS) ?: disc?.let { fsStr(it, "deviceSn") }
                val hasSn = !snRaw.isNullOrEmpty()
                val key = fsStr(fields, "apiKey")?.takeIf { it.isNotEmpty() } ?: "" // cleared = absent
                val proto = fsStr(fields, "protocol") ?: disc?.let { fsStr(it, "protocol") } ?: ""
                val board = fsStr(fields, "mainboardId") ?: disc?.let { fsStr(it, "mainboardId") } ?: ""
                val cloud = fsStr(fields, "mode") == "cloud"
                var p = AccountPrinter(
                    brand = brand,
                    id = id,
                    name = name,
                    ip = ip,
                    modelId = firstOf(fields, MODEL_KEYS) ?: "",
                    sn = if (hasSn) snRaw!! else id,
                    cc = firstOf(fields, CC_KEYS) ?: "",
                    user = fsStr(fields, "username") ?: "",
                    // acuModelId, not modelId: Anycubic's own number and half of every topic.
                    devId = fsStr(fields, "deviceId") ?: "",
                    acuModel = fsStr(fields, "acuModelId") ?: "",
                    key = key,
                    proto = proto,
                    boardId = board,
                    cloud = cloud,
                )
                if (acuBrand && cloud) {
                    // The same two halves of the topics, under their cloud names.
                    p = p.copy(
                        devId = fsStr(fields, "key") ?: "",
                        acuModel = fsStr(fields, "machineType") ?: p.acuModel,
                        sn = fsStr(fields, "cloudPrinterId")?.takeIf { it.isNotEmpty() } ?: p.sn,
                    )
                    keepAnycubic(fields)
                }
                // The same printer twice: the document updated last describes it as it is now.
                val upd = fsMillis(fields, "updatedAt")
                val dup = mine.indexOfFirst { samePrinter(it.first, it.third, p, hasSn) }
                if (dup >= 0) {
                    if (upd > mine[dup].second) mine[dup] = Triple(p, upd, hasSn)
                    Log.i(TAG, "$brand: two documents for one printer, the newer kept")
                    continue
                }
                mine += Triple(p, upd, hasSn)
            }
            out += mine.map { it.first }
        }
        return FetchResult(CloudErr.OK, out, failed)
    }

    private fun keepAnycubic(fields: JSONObject) {
        val old = acu
        fun pick(k: String, prev: String?) = fsStr(fields, k)?.takeIf { it.isNotEmpty() } ?: prev ?: ""
        acu = AnycubicIdentity(
            email = pick("cloudEmail", old?.email),
            token = pick("cloudToken", old?.token),
            caDerB64 = pick("acuCloudCaDerB64", old?.caDerB64),
            certPem = pick("acuCloudClientCertPem", old?.certPem),
            keyPem = pick("acuCloudClientKeyPem", old?.keyPem),
        )
    }

    /** The Anycubic account and client identity from the last read; null when any part is missing. */
    fun anycubicIdentity(): AnycubicIdentity? = acu?.takeIf {
        it.email.isNotEmpty() && it.token.isNotEmpty() && it.caDerB64.isNotEmpty() &&
            it.certPem.isNotEmpty() && it.keyPem.isNotEmpty()
    }

    /**
     * Bambu Lab's cloud session, written to the account by Tiger Studio (this
     * app never signs in to Bambu itself). NOT_FOUND when Studio has not stored one.
     */
    suspend fun bambuSession(): Pair<CloudErr, BambuSession?> {
        val te = ensureToken()
        if (te != CloudErr.OK) return te to null
        val rx = httpDo("$FS/users/$uid/printers/bambulab/secrets/cloud_session", "GET", null, idToken)
        if (rx.status == 404) return CloudErr.NOT_FOUND to null
        if (rx.status != 200 || rx.body == null) return CloudErr.NETWORK to null
        val f = parse(rx.body)?.optJSONObject("fields") ?: return CloudErr.OTHER to null
        val u = fsStr(f, "mqttUsername"); val t = fsStr(f, "accessToken"); val r = fsStr(f, "region")
        if (u.isNullOrEmpty() || t.isNullOrEmpty()) return CloudErr.NOT_FOUND to null
        return CloudErr.OK to BambuSession(u, t, if (r.isNullOrEmpty()) "us" else r)
    }

    /** The language kept in the account (users/{uid}/prefs/app { lang }). */
    suspend fun accountLang(): String? {
        if (ensureToken() != CloudErr.OK) return null
        val rx = httpDo("$FS/users/$uid/prefs/app?mask.fieldPaths=lang", "GET", null, idToken)
        if (rx.status != 200) return null
        return fsStr(parse(rx.body)?.optJSONObject("fields"), "lang")?.takeIf { it.isNotEmpty() }
    }

    /** Writes users/{uid}/prefs/app { lang }, touching that one field only. */
    suspend fun pushLang(code: String): CloudErr {
        val te = ensureToken()
        if (te != CloudErr.OK) return te
        val body = JSONObject().put("fields", JSONObject().put("lang", JSONObject().put("stringValue", code)))
        val rx = httpDo("$FS/users/$uid/prefs/app?updateMask.fieldPaths=lang", "PATCH", body.toString(), idToken)
        return when {
            rx.status == 200 -> CloudErr.OK
            rx.status <= 0 -> CloudErr.NETWORK
            else -> CloudErr.OTHER
        }
    }

    // ---- Pairing (Google accounts) ---------------------------------------------------

    suspend fun pairStart(device: String, fw: String): Pair<CloudErr, PairStart?> {
        val body = JSONObject()
            .put("device", device)
            .put("model", "Tiger Touch")
            .put("kind", "display") // what the approval page calls it: "your screen"
            .put("fw", fw)
        val rx = httpDo(PAIR_START_URL, "POST", body.toString(), null)
        if (rx.status != 200 || rx.body == null) {
            Log.w(TAG, "pairStart HTTP ${rx.status}")
            return (if (rx.status <= 0) CloudErr.NETWORK else CloudErr.OTHER) to null
        }
        val r = parse(rx.body) ?: return CloudErr.OTHER to null
        val iv = r.optInt("interval", 5).let { if (it in 1..59) it else 5 }
        val ps = PairStart(r.optString("verify_url"), r.optString("code"), r.optString("poll_token"), iv)
        return if (ps.verifyUrl.isNotEmpty() && ps.pollToken.isNotEmpty()) CloudErr.OK to ps else CloudErr.OTHER to null
    }

    /** The account id is the user_id claim of the ID token (a JWT), read from its base64url payload. */
    private fun uidFromJwt(jwt: String): String {
        val parts = jwt.split('.')
        if (parts.size < 2) return ""
        return try {
            val json = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            JSONObject(json).optString("user_id")
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun signInCustom(customToken: String, mail: String): CloudErr {
        val body = JSONObject().put("token", customToken).put("returnSecureToken", true).toString()
        val rx = httpDo("https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=$API_KEY",
            "POST", body, null)
        if (rx.status <= 0 || rx.body == null) return CloudErr.NETWORK
        val r = parse(rx.body) ?: return CloudErr.OTHER
        if (rx.status != 200) return mapError(r)
        val tok = r.optString("idToken"); val ref = r.optString("refreshToken")
        val id = uidFromJwt(tok)
        if (tok.isEmpty() || ref.isEmpty() || id.isEmpty()) return CloudErr.OTHER
        idToken = tok; refresh = ref; uid = id; email = mail
        tokenAt = System.currentTimeMillis()
        saveSession()
        Log.i(TAG, "signed in by pairing")
        return CloudErr.OK
    }

    /**
     * Polls the pairing. On "approved" the custom token is exchanged on the spot:
     * it is handed out once, so a failed exchange spends the pairing - start again,
     * never retry the exchange.
     */
    suspend fun pairPoll(pollToken: String): PairStatus {
        val rx = httpDo(PAIR_POLL_URL, "POST", JSONObject().put("poll_token", pollToken).toString(), null)
        if (rx.status != 200 || rx.body == null) {
            Log.w(TAG, "pairPoll HTTP ${rx.status}")
            return PairStatus.ERROR
        }
        val r = parse(rx.body) ?: return PairStatus.ERROR
        return when (r.optString("status")) {
            "pending" -> PairStatus.PENDING
            "denied" -> PairStatus.DENIED
            "expired" -> PairStatus.EXPIRED
            "approved" -> {
                val ct = r.optString("custom_token")
                if (ct.isEmpty()) PairStatus.ERROR
                else if (signInCustom(ct, r.optString("email")) == CloudErr.OK) PairStatus.APPROVED
                else PairStatus.ERROR
            }
            else -> PairStatus.ERROR
        }
    }

    // ---- Presence ---------------------------------------------------------------------

    private suspend fun hasDisplayName(docUrl: String): Boolean {
        val rx = httpDo("$docUrl?mask.fieldPaths=display_name", "GET", null, idToken)
        if (rx.status != 200) return false
        return !fsStr(parse(rx.body)?.optJSONObject("fields"), "display_name").isNullOrEmpty()
    }

    /**
     * Writes users/{uid}/tigertouches/{mac} in one Firestore commit, the same
     * contract and field names as the firmware and TigerSpool. A value the device
     * cannot know is written as null, never left out.
     */
    suspend fun heartbeat(p: Presence): CloudErr {
        val te = ensureToken()
        if (te != CloudErr.OK) return te
        val path = "projects/$PROJECT/databases/(default)/documents/users/$uid/tigertouches/${p.mac}"
        val docUrl = "https://firestore.googleapis.com/v1/$path"
        val named = p.full && hasDisplayName(docUrl)

        val fields = JSONObject()
        val mask = JSONArray()
        fun str(k: String, v: String) { fields.put(k, JSONObject().put("stringValue", v)); mask.put(k) }
        fun int(k: String, v: Int) { fields.put(k, JSONObject().put("integerValue", v.toString())); mask.put(k) }
        fun nul(k: String) { fields.put(k, JSONObject().put("nullValue", "NULL_VALUE")); mask.put(k) }
        fun bool(k: String, v: Boolean) { fields.put(k, JSONObject().put("booleanValue", v)); mask.put(k) }
        fun ids(k: String, list: List<String>) {
            val vals = JSONArray()
            list.forEach { vals.put(JSONObject().put("stringValue", it)) }
            fields.put(k, JSONObject().put("arrayValue", JSONObject().put("values", vals))); mask.put(k)
        }

        if (p.full) {
            str("mac", p.mac)
            str("fw_version", p.fw)
            str("mdns_hostname", p.host)
            if (!named) str("display_name", "TigerTouch-" + p.mac.takeLast(4).uppercase())
            nul("hardware_revision")
        }
        if (p.rssi != 0) int("wifi_signal_dbm", p.rssi) else nul("wifi_signal_dbm")
        str("ip_address", p.ip)
        str("power_state", if (p.screenOff) "screen_off" else "active")
        bool("battery_present", p.battery)
        bool("demo", p.demo)
        nul("power_source")
        nul("battery_percent")
        nul("is_charging")
        int("printers_active", p.ids.size)
        ids("printer_ids", p.ids)
        ids("printers_online", p.ids.filterIndexed { i, _ -> p.online.getOrElse(i) { false } })

        val write = JSONObject()
            .put("update", JSONObject().put("name", path).put("fields", fields))
            .put("updateMask", JSONObject().put("fieldPaths", mask))
            .put("updateTransforms", JSONArray().put(
                JSONObject().put("fieldPath", "last_heartbeat_at").put("setToServerValue", "REQUEST_TIME")))
        val body = JSONObject().put("writes", JSONArray().put(write))
        val rx = httpDo("$FS:commit", "POST", body.toString(), idToken)
        return when {
            rx.status == 200 -> CloudErr.OK
            rx.status <= 0 -> CloudErr.NETWORK
            else -> CloudErr.OTHER
        }
    }
}
