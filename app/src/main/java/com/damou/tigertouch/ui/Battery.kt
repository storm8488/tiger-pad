package com.damou.tigertouch.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damou.tigertouch.drivers.Tr

/** The tablet's own battery, as the system last announced it. */
data class BatteryState(val percent: Int, val charging: Boolean)

private fun read(i: Intent?): BatteryState? {
    if (i == null) return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return BatteryState(level * 100 / scale, charging)
}

/**
 * Follows ACTION_BATTERY_CHANGED while shown. A sticky broadcast: registering
 * returns the current state at once, then the system sends every change.
 */
@Composable
fun rememberBattery(): BatteryState? {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<BatteryState?>(null) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                read(i)?.let { state = it }
            }
        }
        read(ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))?.let { state = it }
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return state
}

/** The header's battery: an icon by level, the percentage, red below 15 % when not charging. */
@Composable
fun BatteryIndicator() {
    val b = rememberBattery() ?: return
    val low = !b.charging && b.percent < 15
    val icon = when {
        b.charging -> Icons.Filled.BatteryChargingFull
        low -> Icons.Filled.BatteryAlert
        b.percent >= 90 -> Icons.Filled.BatteryFull
        b.percent >= 60 -> Icons.Filled.Battery5Bar
        b.percent >= 30 -> Icons.Filled.Battery3Bar
        else -> Icons.Filled.Battery1Bar
    }
    val tint = when {
        low -> Pal.danger
        b.charging -> Pal.ok
        else -> Pal.text
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = Tr.t("Battery", "Batterie"), tint = tint, modifier = Modifier.size(26.dp))
        Text("${b.percent} %", color = tint, fontSize = 18.sp)
    }
}
