package com.damou.tigertouch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.damou.tigertouch.kiosk.Kiosk
import com.damou.tigertouch.ui.DashboardScreen
import com.damou.tigertouch.fleet.SortBy
import com.damou.tigertouch.ui.Density
import com.damou.tigertouch.ui.NightPage
import com.damou.tigertouch.ui.Screen
import com.damou.tigertouch.ui.ScreenMode
import com.damou.tigertouch.ui.rememberAlertView
import com.damou.tigertouch.ui.rememberFleetView
import com.damou.tigertouch.ui.KioskAction
import com.damou.tigertouch.ui.KioskInfo
import com.damou.tigertouch.ui.PairingScreen
import com.damou.tigertouch.ui.SettingsScreen
import com.damou.tigertouch.ui.SignInScreen
import com.damou.tigertouch.ui.StartingScreen
import com.damou.tigertouch.ui.TouchTheme
import kotlinx.coroutines.delay
import java.security.MessageDigest

/** The only activity: HOME of the tablet, everything is drawn in Compose. */
class MainActivity : ComponentActivity() {

    private val app get() = application as TouchApp
    private val kioskPrefs by lazy { getSharedPreferences("kiosk", Context.MODE_PRIVATE) }
    private val uiPrefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Kiosk.enter(this)
        // The sort is remembered; the filters last until the app restarts.
        if (savedInstanceState == null) app.filter.value = app.filter.value.copy(
            sort = runCatching { SortBy.valueOf(uiPrefs.getString("sort", "")!!) }.getOrDefault(SortBy.SMART),
        )
        setContent {
            TouchTheme {
                val phase by app.phase.collectAsStateWithLifecycle()
                // Read whole only by the settings page: it changes at every refresh.
                val entries = app.links.entries.collectAsStateWithLifecycle()
                val accountHasPrinters by remember { derivedStateOf { entries.value.isNotEmpty() } }
                // The account link is a plain property: looked at every 2 s, a change recomposes the header.
                var link by remember { mutableStateOf(app.cloud.link) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    while (true) { link = app.cloud.link; delay(2000) }
                }
                val accountError by app.accountError.collectAsStateWithLifecycle()
                val cameras by app.cameras.collectAsStateWithLifecycle()
                val demo by app.demo.collectAsStateWithLifecycle()
                val fleet = rememberFleetView(app.fleet)
                val filter by app.filter.collectAsStateWithLifecycle()
                val page by app.page.collectAsStateWithLifecycle()
                val alerts = rememberAlertView(app.events.alerting.collectAsStateWithLifecycle())
                val events = app.events.events.collectAsStateWithLifecycle()
                val unacked by remember { derivedStateOf { events.value.count { !it.acked } } }
                val alertPrefs by app.events.alertPrefs.collectAsStateWithLifecycle()
                // An event wakes the screen up.
                LaunchedEffect(Unit) {
                    app.events.wake.collect { app.night.wake(); Screen.lightUp(this@MainActivity) }
                }
                // The screen saver looks at the clock and the idle time every few seconds: no frame unless it changes.
                LaunchedEffect(Unit) { while (true) { app.night.tick(); delay(5000) } }
                LaunchedEffect(Unit) {
                    snapshotFlow { app.night.brightness() }.collect { Screen.brightness(this@MainActivity, it) }
                }
                var density by remember {
                    mutableStateOf(runCatching { Density.valueOf(uiPrefs.getString("density", "")!!) }.getOrDefault(Density.AUTO))
                }
                var settings by remember { mutableStateOf(false) }
                var kiosk by remember { mutableStateOf(kioskInfo()) }
                var showBattery by remember { mutableStateOf(uiPrefs.getBoolean("show_battery", true)) }
                // The back key never leaves HOME: it closes the settings page, nothing else.
                BackHandler { settings = false }

                when (val p = phase) {
                    Phase.Starting -> StartingScreen()
                    is Phase.SignIn -> SignInScreen(p, onSignIn = app::signIn, onPair = app::startPairing)
                    is Phase.Pairing -> PairingScreen(p, onRetry = app::startPairing, onBack = app::backToSignIn)
                    // The night page in place of the others: nothing else is drawn under it.
                    Phase.Dashboard -> if (app.night.mode == ScreenMode.NIGHT) {
                        NightPage(fleet, events, app.night, demo.on)
                    } else if (settings) {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            while (true) { kiosk = kioskInfo(); delay(1000) }
                        }
                        SettingsScreen(
                            email = app.cloud.email,
                            entries = entries.value,
                            kiosk = kiosk,
                            onBack = { settings = false },
                            onToggle = { id, on -> app.links.setEnabled(id, on) },
                            onSignOut = { settings = false; app.signOut() },
                            onSetPin = { pin -> kioskPrefs.edit().putString("pin", hash(pin)).apply(); kiosk = kioskInfo() },
                            checkPin = { pin -> hash(pin) == kioskPrefs.getString("pin", null) },
                            onKiosk = { action ->
                                when (action) {
                                    KioskAction.LEAVE -> {
                                        Kiosk.leave(this@MainActivity)
                                        startActivity(Intent(Settings.ACTION_SETTINGS))
                                    }
                                    KioskAction.RELEASE -> Kiosk.release(this@MainActivity)
                                }
                                kiosk = kioskInfo()
                            },
                            showBattery = showBattery,
                            onShowBattery = { on ->
                                showBattery = on
                                uiPrefs.edit().putBoolean("show_battery", on).apply()
                            },
                            demo = demo,
                            onDemo = app::setDemo,
                            onIncident = { com.damou.tigertouch.demo.Demo.incident() },
                            alertPrefs = alertPrefs,
                            onAlertPrefs = app.events::setPrefs,
                            onTryBeep = { app.events.beep(it) },
                            nightPrefs = app.night.prefs,
                            onNightPrefs = app.night::change,
                            onShowNight = app.night::showNight,
                        )
                    } else {
                        DashboardScreen(
                            fleet = fleet,
                            accountHasPrinters = accountHasPrinters,
                            link = link,
                            accountError = accountError,
                            onRefresh = app::refreshPrinters,
                            onSettings = { settings = true },
                            onControl = app::control,
                            cameras = cameras,
                            showBattery = showBattery,
                            demo = demo.on,
                            density = density,
                            onDensity = { d ->
                                density = d
                                uiPrefs.edit().putString("density", d.name).apply()
                            },
                            filter = filter,
                            onFilter = { f ->
                                app.filter.value = f
                                uiPrefs.edit().putString("sort", f.sort.name).apply()
                            },
                            page = page,
                            alerts = alerts,
                            unacked = unacked,
                            events = app.events.events,
                            onAck = app.events::ack,
                            onAckAll = app.events::ackAll,
                        )
                    }
                }
            }
        }
    }

    /** Every touch goes by the screen saver first: the one that wakes the screen goes no further. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = app.night.onTouch(ev) || super.dispatchTouchEvent(ev)

    override fun onResume() {
        super.onResume()
        Kiosk.hideSystemBars(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Kiosk.hideSystemBars(this)
    }

    private fun kioskInfo() = KioskInfo(
        deviceOwner = Kiosk.isDeviceOwner(this),
        locked = Kiosk.isLocked(this),
        hasPin = kioskPrefs.getString("pin", null) != null,
    )

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("tigertouch:" + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
