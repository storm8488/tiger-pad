package com.damou.tigertouch.ui

import android.app.Activity
import android.content.Context
import android.os.PowerManager
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager

/** The tablet's screen, as far as the app can set it without a system permission. */
object Screen {
    /** The window's own brightness, 0..1, or -1 for the tablet's: no permission needed. */
    fun brightness(activity: Activity, level: Float) {
        val w = activity.window
        if (w.attributes.screenBrightness == level) return
        w.attributes = w.attributes.apply { screenBrightness = if (level < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else level }
    }

    /** Something happened: the screen lights up if it had gone off. */
    fun lightUp(activity: Activity) {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION") // the flags a wake lock needs to light the screen, still honoured
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "tigertouch:event",
            ).acquire(10_000)
        }
    }
}

/**
 * A dialog's window callback that tells [onTouch] of every touch first: a
 * dialog's touches never reach the activity, and the screen saver must see
 * them. [onTouch] returns true to keep the touch from the dialog.
 */
private class TouchWatch(private val d: Window.Callback, private val onTouch: (MotionEvent) -> Boolean) : Window.Callback by d {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = onTouch(event) || d.dispatchTouchEvent(event)
}

/**
 * Inside a full-page dialog: its window covers the whole screen and hides the
 * system bars, as the activity does, so a dialog never shows the navigation bar
 * of a locked tablet.
 */
@androidx.compose.runtime.Composable
fun ImmersiveDialog() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
            if (w.callback !is TouchWatch) {
                val night = (view.context.applicationContext as? com.damou.tigertouch.TouchApp)?.night
                if (night != null) w.callback = TouchWatch(w.callback, night::onTouch)
            }
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            // Laid out under the (hidden) status bar too, from the very top of the screen.
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
}
