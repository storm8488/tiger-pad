package com.damou.tigertouch.drivers

import com.damou.tigertouch.model.AccountPrinter
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
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/*
 * Prusa, through PrusaLink: HTTP on port 80 with Digest authentication
 * (reference: Tiger Studio's prusa/PROTOCOL.md, itself written against Prusa's
 * published OpenAPI and still to be confirmed on hardware). Port of the
 * firmware's drivers/drv_prusa.c.
 *
 * GET /api/v1/status every two seconds gives the state, the temperatures and
 * the job's progress; GET /api/v1/job gives the name of the file, asked again
 * only when the job changes. PrusaLink does not report the loaded filament, so
 * there are no filament cards for this brand.
 *
 * The credentials are the user (maker, unless the account says otherwise) and
 * the password shown by the printer under Settings > Network > PrusaLink. An
 * older account may hold an `apiKey` instead: it is sent as X-Api-Key, with no
 * Digest, as Tiger Studio does. When the printer says something is wrong
 * (status_printer.ok false), its own message is shown.
 */
object PrusaDriver : PrinterDriver {
    override val brand: String = "prusa"

    override fun support(p: AccountPrinter): DrvKind =
        if (p.ip.isNotEmpty() && !p.cloud) DrvKind.LAN else DrvKind.NONE

    override fun start(p: AccountPrinter, env: DriverEnv): PrinterLink = PrusaLink(p, env)
}

private const val POLL_MS = 2000L
private const val ONLINE_WINDOW_NS = 10_000_000_000L
private const val REQ_TIMEOUT_MS = 4000L
private const val CONTROL_TIMEOUT_MS = 5000L

private class PrusaLink(p: AccountPrinter, env: DriverEnv) : PrinterLink {
    private val lock = Any()
    private val job: Job = SupervisorJob(env.scope.coroutineContext[Job])
    private val scope = CoroutineScope(env.scope.coroutineContext + job)

    /** host:port, 80 unless the address names one. */
    private val hp: String = Addr.withPort(p.ip, 80)
    private val user: String = p.user.ifEmpty { "maker" }
    private val pass: String = p.cc
    private val key: String = p.key

    /** Digest only when there is no API key: the key is sent alone, as Tiger Studio does. */
    private val http: OkHttpClient = env.lanHttp.newBuilder()
        .connectTimeout(REQ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(REQ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(REQ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .apply { if (key.isEmpty()) authenticator(DigestAuthenticator(user, pass)) }
        .build()

    @Volatile private var inFlight: Call? = null
    @Volatile private var stopped = false

    // ---- guarded by lock ----
    private var lastOkNs: Long? = null
    /** 0 ok, 401/403 refused, -1 no answer, 404 old API. */
    private var httpErr = 0
    /** The job whose file name is known. */
    private var jobId = 0
    /** status_printer.message while status_printer.ok is false. */
    private var message = ""
    private var pstate = ""
    private var nozzle = 0f
    private var nozzleTarget = 0f
    private var bed = 0f
    private var bedTarget = 0f
    private var chamber = 0f
    private var progress = 0
    private var leftS = 0
    private var file = ""
    private val view = PrinterView(name = p.name, brand = "prusa")

    init {
        scope.launch(Dispatchers.IO) { pollLoop() }
    }

    /** Status code and body; -1 when the printer did not answer. */
    private fun request(client: OkHttpClient, track: Boolean, build: Request.Builder.() -> Unit): Pair<Int, String> {
        return try {
            // Built inside the try: an address OkHttp cannot read is "no answer".
            val b = Request.Builder().apply(build)
            if (key.isNotEmpty()) b.header("X-Api-Key", key)
            val call = client.newCall(b.build())
            if (track) inFlight = call
            call.execute().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: Exception) {
            -1 to ""
        } finally {
            if (track) inFlight = null
        }
    }

    private suspend fun pollLoop() {
        while (scope.isActive && !stopped) {
            val (st, reply) = request(http, true) { url("http://$hp/api/v1/status").get() }
            var wantJob = 0
            synchronized(lock) { httpErr = if (st == 200) 0 else st }

            if (st == 200) {
                val root = parseObject(reply)
                if (root != null) synchronized(lock) {
                    val pr = root.optJSONObject("printer")
                    if (pr != null) {
                        val s = pr.opt("state")
                        if (s is String) pstate = s
                        pr.numOrNull("temp_nozzle")?.let { nozzle = it.toFloat() }
                        pr.numOrNull("target_nozzle")?.let { nozzleTarget = it.toFloat() }
                        pr.numOrNull("temp_bed")?.let { bed = it.toFloat() }
                        pr.numOrNull("target_bed")?.let { bedTarget = it.toFloat() }
                        pr.numOrNull("temp_chamber")?.let { chamber = it.toFloat() }
                        val sp = pr.optJSONObject("status_printer")
                        // Only a JSON `false` counts: a missing "ok" is not a fault.
                        message = if (sp != null && sp.opt("ok") == false) sp.str("message") else ""
                    }
                    val jb = root.optJSONObject("job")
                    if (jb != null) {
                        val pg = jb.numOrNull("progress")
                        progress = if (pg != null) (pg + 0.5).toInt() else 0
                        leftS = jb.numOrNull("time_remaining")?.toInt() ?: 0
                        val id = jb.numOrNull("id")?.toInt()
                        if (id != null && id != jobId) wantJob = id
                    } else if (jobId != 0) {
                        jobId = 0 // no job any more
                        file = ""
                    }
                    lastOkNs = System.nanoTime()
                }
            }

            if (wantJob != 0 && !stopped) {
                val (js, jr) = request(http, true) { url("http://$hp/api/v1/job").get() }
                if (js == 200) {
                    val root = parseObject(jr)
                    if (root != null) {
                        val f = root.optJSONObject("file")
                        // display_name first (the long name), then name.
                        val dn = f?.opt("display_name")
                        val nm = f?.opt("name")
                        val name: String? = if (dn is String) dn else if (nm is String) nm else null
                        synchronized(lock) {
                            if (name != null) file = stripExt(name, ".gcode", ".bgcode")
                            jobId = wantJob
                        }
                    }
                }
            }

            delay(POLL_MS)
        }
    }

    override fun snapshot(): PrinterView = synchronized(lock) {
        val v = view
        // What the firmware's link manager does before each snapshot.
        v.notice = ""
        v.noticeError = false

        val last = lastOkNs
        v.online = last != null && System.nanoTime() - last < ONLINE_WINDOW_NS
        v.canControl = v.online
        if (httpErr == 401 || httpErr == 403) {
            v.notice = if (key.isNotEmpty()) {
                Tr.t(
                    "The printer refused its API key: check it in Tiger Studio.",
                    "L'imprimante refuse sa clé API : vérifie-la dans Tiger Studio.",
                )
            } else {
                Tr.t("Wrong email or password.", "E-mail ou mot de passe incorrect.")
            }
            v.noticeError = true
        } else if (message.isNotEmpty()) {
            v.notice = message // the printer's own words
            v.noticeError = true
        }

        v.state = when (pstate) {
            "PRINTING", "BUSY", "ATTENTION" -> PvState.PRINTING
            "PAUSED" -> PvState.PAUSED
            "FINISHED" -> PvState.DONE
            "ERROR" -> PvState.ERROR
            else -> PvState.IDLE // IDLE, READY, STOPPED
        }
        v.progress = if (v.state == PvState.IDLE) 0 else minOf(progress, 100)
        v.layer = 0
        v.totalLayer = 0
        v.leftS = if (v.state == PvState.PRINTING || v.state == PvState.PAUSED) leftS else 0
        v.nozzle = nozzle
        v.nozzleTarget = nozzleTarget
        v.bed = bed
        v.bedTarget = bedTarget
        v.chamber = chamber
        v.file = file
        for (u in v.unit) u.clear() // PrusaLink does not report the filament
        v.copyDeep()
    }

    /*
     * Pause, resume, stop: PrusaLink PUT /api/v1/job/<id>/pause and /resume,
     * DELETE /api/v1/job/<id>, with Digest authentication. Written from Tiger
     * Studio's protocol notes; this driver has not been run against a real
     * printer, so the command is not verified.
     *
     * Blocks the caller until the printer answers (up to 5 s), as the firmware
     * does: the request itself runs on Dispatchers.IO, so calling this from the
     * main thread does not trip Android's network check, but it should be called
     * from a background coroutine.
     */
    override fun control(cmd: PvControl): Boolean {
        if (!cmd.isJob || stopped) return false
        val id = synchronized(lock) { jobId }
        if (id <= 0) return false // the job id is not known yet
        val build: Request.Builder.() -> Unit = {
            when (cmd) {
                PvControl.PAUSE -> url("http://$hp/api/v1/job/$id/pause").put(ByteArray(0).toRequestBody(null))
                PvControl.RESUME -> url("http://$hp/api/v1/job/$id/resume").put(ByteArray(0).toRequestBody(null))
                else -> url("http://$hp/api/v1/job/$id").delete()
            }
        }
        val client = http.newBuilder()
            .connectTimeout(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val st = try {
            runBlocking(Dispatchers.IO) { request(client, false, build).first }
        } catch (e: Exception) {
            -1
        }
        return st in 200..299
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

/** Drops a trailing extension among [exts], compared without case. */
private fun stripExt(name: String, vararg exts: String): String {
    val dot = name.lastIndexOf('.')
    if (dot < 0) return name
    val ext = name.substring(dot)
    return if (exts.any { it.equals(ext, ignoreCase = true) }) name.substring(0, dot) else name
}

/*
 * HTTP Digest authentication (RFC 2617 / 7616, MD5 and MD5-sess, qop=auth or no
 * qop), which PrusaLink asks for and OkHttp does not provide. The firmware gets
 * it from esp_http_client: the request goes out without credentials, the 401
 * carries the challenge, and the request is sent again with the answer. A new
 * exchange each time (nonce count 1), as the firmware does with keep-alive off.
 * A server that asks for Basic gets Basic.
 */
private class DigestAuthenticator(private val user: String, private val pass: String) : Authenticator {
    private val random = SecureRandom()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Already answered once: the 401 stands (wrong user or password).
        if (response.request.header("Authorization") != null) return null
        val challenges = response.headers("WWW-Authenticate")
        for (h in challenges) {
            val t = h.trim()
            if (t.length > 6 && t.substring(0, 6).equals("Digest", ignoreCase = true)) {
                val auth = digest(response.request, parseParams(t.substring(6))) ?: continue
                return response.request.newBuilder().header("Authorization", auth).build()
            }
        }
        for (h in challenges) {
            if (h.trim().startsWith("Basic", ignoreCase = true)) {
                return response.request.newBuilder().header("Authorization", Credentials.basic(user, pass)).build()
            }
        }
        return null
    }

    private fun digest(req: Request, prm: Map<String, String>): String? {
        val realm = prm["realm"] ?: return null
        val nonce = prm["nonce"] ?: return null
        val algorithm = prm["algorithm"] ?: "MD5"
        val sess = algorithm.equals("MD5-sess", ignoreCase = true)
        if (!sess && !algorithm.equals("MD5", ignoreCase = true)) return null
        val qopOffered = prm["qop"]?.split(',')?.map { it.trim() }
        val qop = if (qopOffered == null) null else if (qopOffered.any { it.equals("auth", true) }) "auth" else return null

        val url = req.url
        val uri = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val nc = "00000001"
        val cnonce = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        var ha1 = md5("$user:$realm:$pass")
        if (sess) ha1 = md5("$ha1:$nonce:$cnonce")
        val ha2 = md5("${req.method}:$uri")
        val resp = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")

        val sb = StringBuilder("Digest ")
        sb.append("username=\"").append(quote(user)).append('"')
        sb.append(", realm=\"").append(quote(realm)).append('"')
        sb.append(", nonce=\"").append(quote(nonce)).append('"')
        sb.append(", uri=\"").append(uri).append('"')
        sb.append(", algorithm=").append(algorithm)
        if (qop != null) sb.append(", qop=").append(qop).append(", nc=").append(nc)
            .append(", cnonce=\"").append(cnonce).append('"')
        sb.append(", response=\"").append(resp).append('"')
        prm["opaque"]?.let { sb.append(", opaque=\"").append(quote(it)).append('"') }
        return sb.toString()
    }

    private fun quote(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** `realm="a, b", nonce="x", qop="auth,auth-int", algorithm=MD5`: quoted values may hold commas. */
    private fun parseParams(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && (s[i] == ',' || s[i].isWhitespace())) i++
            val ks = i
            while (i < n && s[i] != '=' && s[i] != ',') i++
            val k = s.substring(ks, i).trim().lowercase()
            if (i >= n || s[i] != '=') continue
            i++ // '='
            while (i < n && s[i].isWhitespace()) i++
            val v = StringBuilder()
            if (i < n && s[i] == '"') {
                i++
                while (i < n && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < n) i++
                    v.append(s[i]); i++
                }
                i++ // closing quote
            } else {
                while (i < n && s[i] != ',') { v.append(s[i]); i++ }
            }
            if (k.isNotEmpty()) out[k] = v.toString().trim()
        }
        return out
    }
}
