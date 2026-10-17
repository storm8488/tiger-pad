package com.damou.tigertouch.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

/** Makes the app the device owner's only app. Declared in the manifest with res/xml/device_admin.xml. */
class KioskAdmin : DeviceAdminReceiver()

/**
 * The tablet as a dedicated control screen.
 *
 * Two levels, decided by what the device allows:
 *  - always: the app is the HOME screen (manifest), full screen, the screen stays
 *    on while the tablet is plugged in;
 *  - when the app is the device owner (set once over USB, see README): Android's
 *    lock task mode, no status bar, no lock screen, and the app pinned as HOME.
 *    Leaving it takes the PIN of the settings page.
 *
 * Device owner, from a computer, on a tablet with no account added:
 *   adb shell dpm set-device-owner com.damou.tigertouch/.kiosk.KioskAdmin
 */
object Kiosk {
    private const val TAG = "kiosk"

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context) = ComponentName(ctx, KioskAdmin::class.java)

    fun isDeviceOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun isLocked(activity: Activity): Boolean {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= 23) am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        else @Suppress("DEPRECATION") am.isInLockTaskMode
    }

    /** Called from onCreate / onResume of the only activity. */
    fun enter(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars(activity)
        if (!isDeviceOwner(activity)) return
        val dpm = dpm(activity)
        val admin = admin(activity)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
            // HOME is this app, whatever the owner picks later.
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(activity, activity.javaClass))
            if (Build.VERSION.SDK_INT >= 23) {
                dpm.setKeyguardDisabled(admin, true)
                dpm.setStatusBarDisabled(admin, true)
            }
            // The screen stays on while the tablet is plugged in (AC, USB or wireless).
            val plugged = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, plugged.toString())
            if (!isLocked(activity)) activity.startLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "kiosk mode not applied: ${e.javaClass.simpleName}")
        }
    }

    /** Leaves the kiosk until the next start of the app (the settings page, after the PIN). */
    fun leave(activity: Activity) {
        try {
            if (isLocked(activity)) activity.stopLockTask()
            if (isDeviceOwner(activity) && Build.VERSION.SDK_INT >= 23) {
                dpm(activity).setStatusBarDisabled(admin(activity), false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not leave kiosk mode: ${e.javaClass.simpleName}")
        }
    }

    /** Gives the tablet back: no longer device owner, no longer pinned as HOME. */
    fun release(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        leave(activity)
        val dpm = dpm(activity)
        try {
            dpm.clearPackagePersistentPreferredActivities(admin(activity), activity.packageName)
            if (Build.VERSION.SDK_INT >= 23) dpm.setKeyguardDisabled(admin(activity), false)
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(activity.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "could not release the device: ${e.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    fun hideSystemBars(activity: Activity) {
        activity.window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }
}
