package com.damou.tigertouch.ui

import android.content.Context
import android.view.MotionEvent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar

/** How the screen stands. */
enum class ScreenMode {
    /** Normal brightness, the printers page. */
    AWAKE,
    /** Nobody touched the tablet for a while: the printers page, dimmed. */
    DIM,
    /** Night hours and nobody about: a black page with the prints at work and the alerts, dimmed. */
    NIGHT,
}

/** What Settings > Night decides. */
@Immutable
data class NightPrefs(
    /** Minutes without a touch before dimming; 0: never in the day (the night page still comes, after [Night.NIGHT_AFTER_MIN]). */
    val dimAfterMin: Int = 5,
    /** The dimmed brightness, 1..50 %. */
    val dimLevel: Int = 10,
    val night: Boolean = true,
    /** The night hours, from [fromHour] to [toHour] (23 to 7 crosses midnight). */
    val fromHour: Int = 23,
    val toHour: Int = 7,
) {
    /** Within the night hours at this hour of the day. Equal hours: no night. */
    fun isNight(hour: Int): Boolean = night && when {
        fromHour == toHour -> false
        fromHour < toHour -> hour in fromHour until toHour
        else -> hour >= fromHour || hour < toHour
    }

    /** The minutes without a touch before the screen leaves [ScreenMode.AWAKE], at night or in the day. */
    fun idleMin(night: Boolean): Int = if (dimAfterMin > 0) dimAfterMin else if (night) Night.NIGHT_AFTER_MIN else 0
}

/**
 * The screen saver: dims the screen when nobody touches the tablet, shows the
 * night page in the night hours, wakes on a touch or an event, and moves the
 * page by a few pixels now and then so nothing stays in one place for hours.
 *
 * Plain states read by the screens; [tick] every few seconds, [onTouch] from
 * the activity and the full-page dialogs, [wake] on an event. Main thread only.
 */
@Stable
class Night(context: Context) {

    companion object {
        /** With dimming off, the night page still comes after this long. */
        const val NIGHT_AFTER_MIN = 2
        /** How often the page moves, and how far (in pixels, at most). */
        const val SHIFT_EVERY_MS = 3 * 60_000L
        /** A small walk around the origin, each step a few pixels from the last. */
        private val SHIFTS = listOf(0 to 0, 3 to 2, -2 to 4, 4 to -3, -4 to -1, 1 to -4, -3 to 3, 2 to 0)
    }

    private val store = context.getSharedPreferences("night", Context.MODE_PRIVATE)

    var prefs by mutableStateOf(load())
        private set

    var mode by mutableStateOf(ScreenMode.AWAKE)
        private set

    /** Where the page stands, in pixels: read in a layer, a step moves it without recomposing. */
    var shiftX by mutableStateOf(0)
        private set
    var shiftY by mutableStateOf(0)
        private set

    private var lastTouchMs = System.currentTimeMillis()
    /** The night page shown on request from Settings, until the next touch. */
    private var preview = false
    /** The touch that woke the screen is not passed on: it would press whatever is under the finger. */
    private var swallowing = false
    private var shiftAtMs = System.currentTimeMillis()
    private var shiftStep = 0

    fun change(p: NightPrefs) {
        prefs = p
        store.edit().putInt("dim_after", p.dimAfterMin).putInt("dim_level", p.dimLevel)
            .putBoolean("night", p.night).putInt("from", p.fromHour).putInt("to", p.toHour).apply()
        tick()
    }

    /**
     * Every touch of the activity or of a full-page dialog. True when the
     * gesture woke the screen: it goes no further, down to the finger's lift.
     */
    fun onTouch(ev: MotionEvent): Boolean {
        lastTouchMs = System.currentTimeMillis()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && mode != ScreenMode.AWAKE) {
            swallowing = true
            preview = false
            mode = ScreenMode.AWAKE
        }
        val s = swallowing
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
        return s
    }

    /** Something happened: the printers page, bright, and the idle time starts again. */
    fun wake() {
        lastTouchMs = System.currentTimeMillis()
        preview = false
        mode = ScreenMode.AWAKE
    }

    /** Settings: shows the night page now, until the next touch. */
    fun showNight() {
        preview = true
        mode = ScreenMode.NIGHT
    }

    /** Called every few seconds: the mode for the time of day and the idle time, and the page's step. */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        if (!preview) {
            val night = prefs.isNight(Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY))
            val idleMin = prefs.idleMin(night)
            val idle = idleMin > 0 && nowMs - lastTouchMs >= idleMin * 60_000L
            mode = when {
                !idle -> ScreenMode.AWAKE
                night -> ScreenMode.NIGHT
                else -> ScreenMode.DIM
            }
        }
        if (nowMs - shiftAtMs >= SHIFT_EVERY_MS) {
            shiftAtMs = nowMs
            shiftStep = (shiftStep + 1) % SHIFTS.size
            shiftX = SHIFTS[shiftStep].first
            shiftY = SHIFTS[shiftStep].second
        }
    }

    /** The window's brightness for [mode]: -1 for the tablet's own. */
    fun brightness(): Float = if (mode == ScreenMode.AWAKE) -1f else prefs.dimLevel.coerceIn(1, 50) / 100f

    private fun load() = NightPrefs(
        dimAfterMin = store.getInt("dim_after", 5),
        dimLevel = store.getInt("dim_level", 10),
        night = store.getBoolean("night", true),
        fromHour = store.getInt("from", 23),
        toHour = store.getInt("to", 7),
    )
}
