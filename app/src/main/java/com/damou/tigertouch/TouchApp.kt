package com.damou.tigertouch

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.damou.tigertouch.cam.CameraHub
import com.damou.tigertouch.cam.Cameras
import com.damou.tigertouch.cam.DemoCams
import com.damou.tigertouch.cloud.Cloud
import com.damou.tigertouch.cloud.CloudErr
import com.damou.tigertouch.cloud.PairStart
import com.damou.tigertouch.cloud.PairStatus
import com.damou.tigertouch.cloud.Presence
import com.damou.tigertouch.demo.Demo
import com.damou.tigertouch.demo.DemoConfig
import com.damou.tigertouch.drivers.DriverEnv
import com.damou.tigertouch.events.Events
import com.damou.tigertouch.fleet.FleetFilter
import com.damou.tigertouch.fleet.FleetMapper
import com.damou.tigertouch.fleet.FleetPage
import com.damou.tigertouch.fleet.page
import com.damou.tigertouch.fleet.PrinterUi
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /**
     * The switched-on printers as the home page draws them, made off the main
     * thread. A refresh that changes nothing on screen emits nothing.
     */
    lateinit var fleet: StateFlow<List<PrinterUi>>
        private set

    /** What happened to the printers: the journal, the blinking tiles, the beep. */
    lateinit var events: Events
        private set

    /** The screen saver: dimming, the night page, the page's small moves. */
    lateinit var night: com.damou.tigertouch.ui.Night
        private set

    /** Which printers the home page shows, in which order: set by the screen. */
    val filter = MutableStateFlow(FleetFilter())

    /** The home page's order and summary, made off the main thread; emits only when they change. */
    lateinit var page: StateFlow<FleetPage>
        private set

    private val _phase = MutableStateFlow<Phase>(Phase.Starting)
    val phase: StateFlow<Phase> = _phase

    private val _accountError = MutableStateFlow("")
    /** Why the printers list may be stale, empty when the last read worked. */
    val accountError: StateFlow<String> = _accountError

    private var printers: List<AccountPrinter> = emptyList()

    private val _cameras = MutableStateFlow<Map<String, String>>(emptyMap())
    /** MJPEG stream URL by printer id, for the printers that have a camera this app can show. */
    val cameras: StateFlow<Map<String, String>> = _cameras
    /** The printer as it was when its camera was looked for: looked for again when the account changes it. */
    private val camerasFor = HashMap<String, AccountPrinter>()
    private val _demo = MutableStateFlow(DemoConfig())
    /** Demo mode: made-up printers in place of the account's, none of them reached. */
    val demo: StateFlow<DemoConfig> = _demo
    private var sessionJob: Job? = null
    private var pairJob: Job? = null

    /** The live thumbnails of the cards. */
    val camHub by lazy { CameraHub(Net.lanHttp, scope) }

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
        _demo.value = Demo.load(this)
        links.run(scope)
        val mapper = FleetMapper()
        fleet = links.entries.map { mapper.map(it) }.flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
        page = combine(fleet, filter) { all, f -> page(all, f) }.flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, FleetPage())
        events = Events(this, scope)
        night = com.damou.tigertouch.ui.Night(this)
        scope.launch(Dispatchers.Default) { fleet.collect { events.observe(it) } }
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
        camerasFor.clear()
        _cameras.value = emptyMap()
        cloud.signOut()
        _phase.value = Phase.SignIn()
    }

    // ---- Signed in ----------------------------------------------------------------

    /** Switches demo mode, or changes its printers: every link is closed and opened again. */
    fun setDemo(c: DemoConfig) {
        val was = _demo.value
        Demo.save(this, c)
        _demo.value = c
        if (was != c && (c.on || was.on)) applyDemo(c)
    }

    private fun applyDemo(c: DemoConfig) {
        links.setPrinters(emptyList())
        if (c.on) {
            camerasFor.clear()
            val demoPrinters = Demo.printers(c)
            // Every demo printer has a camera: made-up pictures, decoded for real.
            _cameras.value = demoPrinters.associate { it.id to DemoCams.url(it.id) }
            links.setPrinters(demoPrinters)
            links.setNetworkReady(true)
        } else {
            links.setPrinters(printers)
            findCameras()
        }
    }

    private fun enterDashboard() {
        _phase.value = Phase.Dashboard
        if (_demo.value.on) applyDemo(_demo.value)
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
        if (!_demo.value.on) {
            links.setPrinters(printers)
            findCameras()
        }
        links.setNetworkReady(true)
        return true
    }

    /** Looks for the camera of every printer that is new or changed since the last look. */
    private fun findCameras() {
        val todo = printers.filter { camerasFor[it.id] != it }
        todo.forEach { camerasFor[it.id] = it }
        val ids = printers.map { it.id }.toSet()
        _cameras.value = _cameras.value.filterKeys { it in ids }
        for (p in todo) scope.launch {
            val url = Cameras.find(p, Net.lanHttp)
            if (_demo.value.on) return@launch
            _cameras.value = if (url != null) _cameras.value + (p.id to url) else _cameras.value - p.id
        }
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
            val demoOn = _demo.value.on
            // The account lists no made-up printer.
            val entries = if (demoOn) emptyList() else links.entries.value.filter { it.enabled }
            val p = Presence(
                mac = deviceId,
                host = "tigertouch-${deviceId.takeLast(4)}",
                fw = "android-" + BuildConfig.VERSION_NAME,
                rssi = wifiRssi(),
                ip = wifiIp(),
                screenOff = false,
                battery = true, // a tablet has one
                demo = demoOn,
                ids = entries.map { it.printer.id },
                online = entries.map { it.view?.online == true },
                full = full,
            )
            if (cloud.heartbeat(p) == CloudErr.OK) full = false
            delay(HEARTBEAT_MS)
        }
    }

    fun control(id: String, cmd: PvControl) {
        if (cmd == PvControl.PAUSE) events.ownPause(id)
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
