package com.damou.tigertouch

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.damou.tigertouch.cloud.Cloud
import com.damou.tigertouch.cloud.CloudErr
import com.damou.tigertouch.cloud.PairStart
import com.damou.tigertouch.cloud.PairStatus
import com.damou.tigertouch.cloud.Presence
import com.damou.tigertouch.drivers.DriverEnv
import com.damou.tigertouch.links.Links
import com.damou.tigertouch.model.AccountPrinter
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.net.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** Where the application stands: what the screen shows. */
sealed interface Phase {
    /** Reading the stored session at start. */
    data object Starting : Phase
    /** No account: e-mail and password, or the QR code for a Google account. */
    data class SignIn(val error: String = "", val busy: Boolean = false) : Phase
    data class Pairing(val pair: PairStart?, val error: String = "") : Phase
    /** Signed in: the printers. */
    data object Dashboard : Phase
}

/**
 * The application: the firmware's main.c without the screens. Owns the account,
 * the links and the timed work (printers re-read, token kept alive, presence
 * written to the account).
 */
class TouchApp : Application() {

    private companion object {
        const val TAG = "app"
        /** A failed read of the printers, tried again. */
        const val PRINTERS_RETRY_MS = 30_000L
        /** The account is re-read now and then: printers added in Tiger Studio appear on their own. */
        const val PRINTERS_REREAD_MS = 10 * 60_000L
        const val HEARTBEAT_MS = 30_000L
        /** A pairing nobody approves is dropped after this long. */
        const val PAIR_GIVE_UP_MS = 11 * 60_000L
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var cloud: Cloud
        private set
    lateinit var links: Links
        private set

    private val _phase = MutableStateFlow<Phase>(Phase.Starting)
    val phase: StateFlow<Phase> = _phase

    private val _accountError = MutableStateFlow("")
    /** Why the printers list may be stale, empty when the last read worked. */
    val accountError: StateFlow<String> = _accountError

    private var printers: List<AccountPrinter> = emptyList()
    private var sessionJob: Job? = null
    private var pairJob: Job? = null

    /** A stable id for this device in the account (Android hides the Wi-Fi MAC). */
    val deviceId: String by lazy {
        val p = getSharedPreferences("device", Context.MODE_PRIVATE)
        p.getString("id", null) ?: ByteArray(6).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { p.edit().putString("id", it).apply() }
    }

    override fun onCreate() {
        super.onCreate()
        cloud = Cloud(this, Net.http)
        val env = DriverEnv(Net.http, Net.lanHttp, cloud, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        links = Links(this, env)
        links.run(scope)
        scope.launch { start() }
    }

    private suspend fun start() {
        if (!cloud.hasSession) {
            _phase.value = Phase.SignIn()
            return
        }
        when (val r = cloud.resume()) {
            CloudErr.OK, CloudErr.NETWORK, CloudErr.OTHER -> {
                // No answer is not a refused session: keep it and try again from the dashboard.
                if (r != CloudErr.OK) _accountError.value = r.text()
                enterDashboard()
            }
            else -> _phase.value = Phase.SignIn(r.text())
        }
    }

    // ---- Sign-in ----------------------------------------------------------------

    fun signIn(email: String, password: String) {
        _phase.value = Phase.SignIn(busy = true)
        scope.launch {
            val r = cloud.signIn(email.trim(), password)
            if (r == CloudErr.OK) enterDashboard() else _phase.value = Phase.SignIn(r.text())
        }
    }

    fun startPairing() {
        pairJob?.cancel()
        _phase.value = Phase.Pairing(null)
        pairJob = scope.launch {
            val (err, pair) = cloud.pairStart("tigertouch-${deviceId.takeLast(4)}", BuildConfig.VERSION_NAME)
            if (pair == null) {
                _phase.value = Phase.Pairing(null, err.text())
                return@launch
            }
            _phase.value = Phase.Pairing(pair)
            val giveUp = System.currentTimeMillis() + PAIR_GIVE_UP_MS
            while (isActive && System.currentTimeMillis() < giveUp) {
                delay(pair.intervalS * 1000L)
                when (cloud.pairPoll(pair.pollToken)) {
                    PairStatus.PENDING -> Unit
                    PairStatus.APPROVED -> { enterDashboard(); return@launch }
                    PairStatus.DENIED -> {
                        _phase.value = Phase.Pairing(null, tr("The request was refused.", "La demande a été refusée.")); return@launch
                    }
                    PairStatus.EXPIRED -> {
                        _phase.value = Phase.Pairing(null, tr("The code expired.", "Le code a expiré.")); return@launch
                    }
                    // The custom token is handed out once: a failed exchange spends the pairing.
                    PairStatus.ERROR -> {
                        _phase.value = Phase.Pairing(null, tr("Pairing failed, start again.", "L'appairage a échoué, recommencez.")); return@launch
                    }
                }
            }
            if (isActive) _phase.value = Phase.Pairing(null, tr("The code expired.", "Le code a expiré."))
        }
    }

    fun backToSignIn() {
        pairJob?.cancel()
        _phase.value = Phase.SignIn()
    }

    fun signOut() {
        sessionJob?.cancel()
        links.setNetworkReady(false)
        links.closeAll()
        links.setPrinters(emptyList())
        printers = emptyList()
        cloud.signOut()
        _phase.value = Phase.SignIn()
    }

    // ---- Signed in ----------------------------------------------------------------

    private fun enterDashboard() {
        _phase.value = Phase.Dashboard
        sessionJob?.cancel()
        sessionJob = scope.launch {
            launch { printersLoop() }
            launch { keepAliveLoop() }
            launch { presenceLoop() }
        }
    }

    /** Re-reads the account's printers now (the refresh button). */
    fun refreshPrinters() {
        scope.launch { fetchPrinters() }
    }

    private suspend fun fetchPrinters(): Boolean {
        val r = cloud.fetchPrinters(printers)
        if (r.err != CloudErr.OK) {
            _accountError.value = r.err.text()
            if (!cloud.hasSession) { signOut(); _phase.value = Phase.SignIn(r.err.text()) }
            return false
        }
        _accountError.value = if (r.failedBrands > 0)
            tr("Some brands did not answer; their printers are kept as they were.",
                "Certaines marques n'ont pas répondu ; leurs imprimantes sont gardées telles quelles.")
        else ""
        printers = r.printers
        links.setPrinters(printers)
        links.setNetworkReady(true)
        return true
    }

    private suspend fun printersLoop() {
        while (true) {
            val ok = fetchPrinters()
            delay(if (ok) PRINTERS_REREAD_MS else PRINTERS_RETRY_MS)
        }
    }

    private suspend fun keepAliveLoop() {
        while (true) {
            delay(30_000)
            cloud.keepalive()
        }
    }

    /** The device says it is there, in users/{uid}/tigertouches/{id}, every 30 s. */
    private suspend fun presenceLoop() {
        var full = true
        while (true) {
            val entries = links.entries.value.filter { it.enabled }
            val p = Presence(
                mac = deviceId,
                host = "tigertouch-${deviceId.takeLast(4)}",
                fw = "android-" + BuildConfig.VERSION_NAME,
                rssi = wifiRssi(),
                ip = wifiIp(),
                screenOff = false,
                battery = true, // a tablet has one
                demo = false,
                ids = entries.map { it.printer.id },
                online = entries.map { it.view?.online == true },
                full = full,
            )
            if (cloud.heartbeat(p) == CloudErr.OK) full = false
            delay(HEARTBEAT_MS)
        }
    }

    fun control(id: String, cmd: PvControl) {
        scope.launch(Dispatchers.IO) {
            if (!links.control(id, cmd)) Log.i(TAG, "command not taken")
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiInfo() = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo

    private fun wifiRssi(): Int = try { wifiInfo()?.rssi?.takeIf { it > -127 && it < 0 } ?: 0 } catch (e: Exception) { 0 }

    private fun wifiIp(): String = try {
        val a = wifiInfo()?.ipAddress ?: 0
        if (a == 0) "" else "${a and 0xff}.${a shr 8 and 0xff}.${a shr 16 and 0xff}.${a shr 24 and 0xff}"
    } catch (e: Exception) {
        ""
    }

    private fun tr(en: String, fr: String) = com.damou.tigertouch.drivers.Tr.t(en, fr)
}
