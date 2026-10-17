package com.damou.tigertouch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damou.tigertouch.drivers.Tr

private val DIM_AFTER = listOf(0, 1, 2, 5, 10, 15, 30)

/** Settings > Night: when the screen dims, how much, and the night hours. */
@Composable
fun NightSettings(prefs: NightPrefs, onPrefs: (NightPrefs) -> Unit, onShowNight: () -> Unit) {
    var level by remember(prefs.dimLevel) { mutableStateOf(prefs.dimLevel.toFloat()) }
    Column(
        Modifier.fillMaxWidth().background(Pal.card, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(Tr.t("Dim after", "Atténuer après"), color = Pal.text, fontSize = 18.sp)
            Text(
                Tr.t("Without a touch. A touch or an event brings the brightness back; the touch that wakes presses nothing.",
                    "Sans toucher l'écran. Un toucher ou un événement rend la luminosité ; le toucher qui réveille n'appuie sur rien."),
                color = Pal.muted, fontSize = 14.sp,
            )
        }
        Choices(DIM_AFTER, prefs.dimAfterMin, { if (it == 0) Tr.t("Never", "Jamais") else "$it min" }) {
            onPrefs(prefs.copy(dimAfterMin = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tr.t("Dimmed: ", "Atténuée : ") + "${level.toInt()} %", color = Pal.text, fontSize = 16.sp, modifier = Modifier.width(150.dp))
            Slider(
                value = level, onValueChange = { level = it }, valueRange = 1f..50f,
                onValueChangeFinished = { onPrefs(prefs.copy(dimLevel = level.toInt())) }, modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Tr.t("Night hours", "Plage de nuit"), color = Pal.text, fontSize = 18.sp)
                Text(
                    Tr.t("A black page with the prints at work, their end and the alerts, dimmed, after the same idle time (2 min when dimming is off).",
                        "Une page noire avec les impressions en cours, leur fin et les alertes, atténuée, après le même délai (2 min si l'atténuation est désactivée)."),
                    color = Pal.muted, fontSize = 14.sp,
                )
            }
            Switch(checked = prefs.night, onCheckedChange = { onPrefs(prefs.copy(night = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Tr.t("From", "De"), color = Pal.text, fontSize = 16.sp)
            HourStepper(prefs.fromHour, prefs.night) { onPrefs(prefs.copy(fromHour = it)) }
            Text(Tr.t("to", "à"), color = Pal.text, fontSize = 16.sp)
            HourStepper(prefs.toHour, prefs.night) { onPrefs(prefs.copy(toHour = it)) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onShowNight) { Text(Tr.t("Show the night page", "Voir la page de nuit")) }
        }
    }
}

/** A row of choices, the chosen one lit. */
@Composable
private fun <T> Choices(all: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
    Row(Modifier.background(Pal.bg, RoundedCornerShape(12.dp))) {
        for (v in all) {
            val on = v == chosen
            Box(
                Modifier
                    .height(48.dp)
                    .background(if (on) Pal.tile else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onChoose(v) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(v), color = if (on) Pal.accent else Pal.muted, fontSize = 16.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** An hour of the day, − and + around it. */
@Composable
private fun HourStepper(hour: Int, enabled: Boolean, onHour: (Int) -> Unit) {
    Row(Modifier.background(Pal.bg, RoundedCornerShape(12.dp)), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", enabled) { onHour((hour + 23) % 24) }
        Text("%d h".format(hour), color = if (enabled) Pal.text else Pal.muted, fontSize = 18.sp,
            modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("+", enabled) { onHour((hour + 1) % 24) }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Pal.accent else Pal.muted, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}
