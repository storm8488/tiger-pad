package com.damou.tigertouch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.events.AlertPrefs
import com.damou.tigertouch.events.EventKind
import com.damou.tigertouch.events.FleetEvent
import com.damou.tigertouch.model.PvState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Which printers have an event nobody acknowledged, and the blink they share.
 * Both are read while drawing: a blink redraws the border of the printers
 * that blink, and recomposes nothing.
 */
@Stable
class AlertView(private val alerting: State<Set<String>>, private val bright: State<Boolean>) {
    fun has(id: String): Boolean = id in alerting.value
    val on: Boolean get() = bright.value
}

/** The blink: bright, dim, bright... while at least one printer waits for a touch. */
@Composable
fun rememberAlertView(alerting: State<Set<String>>): AlertView {
    val bright = remember { mutableStateOf(true) }
    val any = alerting.value.isNotEmpty()
    LaunchedEffect(any) {
        bright.value = true
        // Two steps, no animation: two frames a second, a soft pulse.
        while (any) { delay(700); bright.value = !bright.value }
    }
    return remember(alerting) { AlertView(alerting, bright) }
}

/** The colour of a printer's alert: its state's. */
private fun alertColor(p: com.damou.tigertouch.fleet.PrinterUi?): Color = when {
    p == null || !p.online -> Pal.danger
    p.state == PvState.DONE -> Color(0xFF6AB0F0)
    p.state == PvState.PAUSED -> Color(0xFFF5B84A)
    p.state == PvState.ERROR -> Pal.danger
    else -> Pal.accent
}

/**
 * A blinking border around a printer with an event to acknowledge. Put it
 * outside the printer's own texture: blinking redraws this light layer, not
 * the tile. Only a printer that blinks gets the layer: the others scroll with
 * one layer less.
 */
@Composable
fun Modifier.alertBorder(alerts: AlertView, fleet: FleetView, id: String, radius: Dp): Modifier =
    if (!alerts.has(id)) this else graphicsLayer().drawWithContent {
        drawContent()
        if (alerts.has(id)) {
            val w = 5.dp.toPx()
            val c = alertColor(fleet.get(id))
            drawRoundRect(
                c.copy(alpha = if (alerts.on) 1f else 0.3f), topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(radius.toPx()), style = Stroke(w),
            )
        }
    }

/** The bell of the header: how many events wait for a touch, and the journal behind it. */
@Composable
fun Bell(unacked: Int, onOpen: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        IconButton(onClick = onOpen, Modifier.size(56.dp)) {
            Icon(
                if (unacked > 0) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                Tr.t("Events", "Événements"), tint = if (unacked > 0) Pal.accent else Pal.text,
            )
        }
        if (unacked > 0) Text(
            if (unacked > 99) "99+" else "$unacked", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp)
                .background(Pal.danger, RoundedCornerShape(50)).padding(horizontal = 6.dp),
        )
    }
}

internal fun kindColor(k: EventKind): Color = when (k) {
    EventKind.DONE -> Color(0xFF6AB0F0)
    EventKind.PAUSED -> Color(0xFFF5B84A)
    EventKind.ERROR, EventKind.OFFLINE -> Pal.danger
    EventKind.LOW_SPOOL -> Pal.accent
}

/** The journal: the last 100 events, newest first, kept across restarts. */
@Composable
fun JournalDialog(events: List<FleetEvent>, demo: Boolean, onAckAll: () -> Unit, onAck: (String) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        Column(Modifier.fillMaxSize().background(Pal.bg).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Tr.t("Events", "Journal des événements"), color = Pal.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                if (demo) DemoBadge(Modifier.padding(start = 12.dp))
                Spacer(Modifier.weight(1f))
                if (events.any { !it.acked }) OutlinedButton(onClick = onAckAll, modifier = Modifier.height(48.dp)) {
                    Text(Tr.t("Acknowledge all", "Tout acquitter"), fontSize = 16.sp)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClose, Modifier.size(56.dp)) { Icon(Icons.Filled.Close, Tr.t("Close", "Fermer"), tint = Pal.text) }
            }
            Spacer(Modifier.height(12.dp))
            if (events.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(Tr.t("Nothing happened yet.", "Rien à signaler pour l'instant."), color = Pal.muted, fontSize = 20.sp)
                }
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(events, key = { "${it.atMs}|${it.printerId}|${it.kind}" }) { e -> EventRow(e) { onAck(e.printerId) } }
            }
        }
    }
}

private val dayFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

private fun whenText(ms: Long): String {
    val today = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = today.get(Calendar.YEAR) == then.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) fmtClock(ms) else dayFormat.format(Date(ms))
}

@Composable
private fun EventRow(e: FleetEvent, onAck: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(if (e.acked) Pal.card else Pal.tile, RoundedCornerShape(12.dp))
            .clickable(enabled = !e.acked, onClick = onAck)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(whenText(e.atMs), color = Pal.muted, fontSize = 16.sp, modifier = Modifier.width(110.dp))
        Box(Modifier.size(12.dp).background(kindColor(e.kind), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(e.kind.label(), color = kindColor(e.kind), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(220.dp))
        Text(e.printerName, color = Pal.text, fontSize = 18.sp, fontWeight = if (e.acked) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(260.dp))
        Text(e.detail, color = Pal.muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (!e.acked) Text(Tr.t("New", "Nouveau"), color = Pal.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Settings > Alerts: the beep, its volume, and which events are followed. */
@Composable
fun AlertSettings(prefs: AlertPrefs, onPrefs: (AlertPrefs) -> Unit, onTry: (Int) -> Unit) {
    var volume by remember(prefs.volume) { mutableStateOf(prefs.volume.toFloat()) }
    Column(
        Modifier.fillMaxWidth().background(Pal.card, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Tr.t("Sound", "Son"), color = Pal.text, fontSize = 18.sp)
                Text(
                    Tr.t("A short beep at each event (alarm volume of the tablet).", "Un bip court à chaque événement (volume des alarmes de la tablette)."),
                    color = Pal.muted, fontSize = 14.sp,
                )
            }
            Switch(checked = prefs.sound, onCheckedChange = { onPrefs(prefs.copy(sound = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tr.t("Volume: ", "Volume : ") + volume.toInt(), color = Pal.text, fontSize = 16.sp, modifier = Modifier.width(140.dp))
            Slider(
                value = volume, onValueChange = { volume = it }, valueRange = 0f..100f, enabled = prefs.sound,
                onValueChangeFinished = { onPrefs(prefs.copy(volume = volume.toInt())); onTry(volume.toInt()) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { onTry(volume.toInt()) }) { Text(Tr.t("Try", "Essayer")) }
        }
        Text(Tr.t("Events followed", "Événements suivis"), color = Pal.text, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (k in EventKind.entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = k in prefs.kinds, onCheckedChange = { on ->
                        onPrefs(prefs.copy(kinds = if (on) prefs.kinds + k else prefs.kinds - k))
                    })
                    Spacer(Modifier.width(6.dp))
                    Text(k.label(), color = Pal.text, fontSize = 15.sp)
                }
            }
        }
    }
}
