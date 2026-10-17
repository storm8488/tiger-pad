package com.damou.tigertouch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.model.PvState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* The firmware's palette (ui/ui_dashboard.c), on a dark ground like the Panda Touch. */

object Pal {
    val bg = Color(0xFF111318)
    val card = Color(0xFF1B1E25)
    val tile = Color(0xFF252932)
    val text = Color(0xFFE8EAED)
    val muted = Color(0xFF8B909A)
    val accent = Color(0xFFFF7A1A)
    val danger = Color(0xFFF06A6A)
    val ok = Color(0xFF3ECF8E)
}

@Composable
fun TouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.accent,
            onPrimary = Color.White,
            background = Pal.bg,
            surface = Pal.card,
            onSurface = Pal.text,
            onBackground = Pal.text,
            surfaceVariant = Pal.tile,
            onSurfaceVariant = Pal.muted,
            error = Pal.danger,
        ),
        content = content,
    )
}

fun rgb(c: Int) = Color(0xFF000000.toInt() or (c and 0xffffff))

/** The words of a printer's state: ui_state_text(). */
fun stateText(s: PvState): String = when (s) {
    PvState.PRINTING -> Tr.t("Printing", "Impression")
    PvState.PAUSED -> Tr.t("Paused", "Pause")
    PvState.DONE -> Tr.t("Done", "Terminé")
    PvState.ERROR -> Tr.t("Error", "Erreur")
    PvState.IDLE -> Tr.t("Idle", "Repos")
}

/** Chip background and text: ui_state_colors(). Grey when the printer is offline. */
fun stateColors(s: PvState, online: Boolean): Pair<Color, Color> {
    if (!online) return Color(0xFF2B2F38) to Color(0xFF8B909A)
    return when (s) {
        PvState.PRINTING -> Color(0xFF1F3D31) to Color(0xFF3ECF8E)
        PvState.PAUSED -> Color(0xFF4A3A16) to Color(0xFFF5B84A)
        PvState.DONE -> Color(0xFF1C3550) to Color(0xFF6AB0F0)
        PvState.ERROR -> Color(0xFF4A1F1F) to Color(0xFFF06A6A)
        PvState.IDLE -> Color(0xFF2B2F38) to Color(0xFF8B909A)
    }
}

/** Time left, from whole minutes: ui_fmt_left(). */
fun fmtLeftMin(min: Int): String = when {
    min <= 0 -> "--"
    min >= 60 -> "%d h %02d".format(min / 60, min % 60)
    else -> "%d min".format(min)
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** A time of day from minutes since the epoch, "--:--" when unknown: ui_fmt_end(). */
fun fmtClockMin(epochMin: Long): String =
    if (epochMin <= 0) "--:--" else clockFormat.format(Date(epochMin * 60_000L))

fun fmtClock(ms: Long): String = clockFormat.format(Date(ms))

/** Whole degrees, "--" when unknown. */
fun fmtTemp(t: Int): String = if (t <= 0) "--" else "$t°"
