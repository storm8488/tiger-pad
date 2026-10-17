package com.damou.tigertouch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.damou.tigertouch.cloud.CloudLink
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.links.PrinterEntry
import com.damou.tigertouch.model.Brands
import com.damou.tigertouch.model.PV_LABEL_EXT
import com.damou.tigertouch.model.PV_LABEL_HT
import com.damou.tigertouch.model.PrinterView
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvState
import com.damou.tigertouch.model.PvUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The printers page. Where the Panda Touch scrolls one card at a time on its
 * 5" panel, the tablet lays every switched-on printer out in a grid, all live.
 */
@Composable
fun DashboardScreen(
    entries: List<PrinterEntry>,
    link: CloudLink,
    accountError: String,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onControl: (String, PvControl) -> Unit,
) {
    var openId by remember { mutableStateOf<String?>(null) }
    val shown = entries.filter { it.enabled }

    Column(Modifier.fillMaxSize().background(Pal.bg)) {
        Header(link, onRefresh, onSettings)
        if (accountError.isNotEmpty()) {
            Text(accountError, color = Pal.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (entries.isEmpty()) Tr.t(
                        "No printer in the account yet. Add them in Tiger Studio Manager.",
                        "Aucune imprimante dans le compte. Ajoutez-les dans Tiger Studio Manager.",
                    ) else Tr.t("Every printer is switched off (Settings).", "Toutes les imprimantes sont désactivées (Réglages)."),
                    color = Pal.muted, fontSize = 20.sp,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 380.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.printer.id }) { e ->
                    PrinterCard(e, onOpen = { openId = e.printer.id }, onControl = { onControl(e.printer.id, it) })
                }
            }
        }
    }

    val open = shown.firstOrNull { it.printer.id == openId }
    if (open != null) {
        Dialog(onDismissRequest = { openId = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            PrinterFull(open, onClose = { openId = null }, onControl = { onControl(open.printer.id, it) })
        }
    }
}

@Composable
private fun Header(link: CloudLink, onRefresh: () -> Unit, onSettings: () -> Unit) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) { now = Date(); delay(1000) }
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tiger Touch", color = Pal.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = Pal.text, fontSize = 24.sp)
        Spacer(Modifier.width(20.dp))
        val (icon, tint) = when (link) {
            CloudLink.OK -> Icons.Filled.Cloud to Pal.ok
            CloudLink.CONNECTING -> Icons.Filled.CloudSync to Pal.muted
            CloudLink.NONE -> Icons.Filled.CloudOff to Pal.danger
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, Tr.t("Refresh", "Actualiser"), tint = Pal.text) }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, Tr.t("Settings", "Réglages"), tint = Pal.text) }
    }
}

/** One printer: the firmware's card (ui/ui_cards.c), larger. */
@Composable
private fun PrinterCard(e: PrinterEntry, onOpen: () -> Unit, onControl: (PvControl) -> Unit) {
    val v = e.view ?: PrinterView(name = e.printer.name, brand = e.printer.brand)
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Pal.card)
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TitleRow(v, e.printer.cloud)
        if (v.notice.isNotEmpty()) {
            Text(v.notice, color = if (v.noticeError) Pal.danger else Pal.muted, fontSize = 14.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        JobBlock(v)
        TempRow(v, tileHeight = 64.dp)
        Units(v, compact = true)
        JobButtons(v, onControl, size = 56.dp)
    }
}

@Composable
private fun TitleRow(v: PrinterView, cloud: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(v.name, color = Pal.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(Brands.label(v.brand) + if (cloud) " · Cloud" else " · LAN", color = Pal.muted, fontSize = 14.sp)
        }
        val (bg, fg) = stateColors(v.state, v.online)
        Text(
            if (v.online) stateText(v.state) else Tr.t("Offline", "Hors ligne"),
            color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun JobBlock(v: PrinterView) {
    val active = v.jobActive || v.state == PvState.DONE
    Column(Modifier.alpha(if (active) 1f else 0.45f)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${v.progress} %", color = Pal.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(v.file.ifEmpty { "--" }, color = Pal.muted, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(bottom = 6.dp))
        }
        // A plain filled bar: Material 3's indicator draws a gap and an end dot that read as noise here.
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Pal.tile)) {
            Box(
                Modifier.fillMaxWidth(v.progress.coerceIn(0, 100) / 100f).fillMaxHeight()
                    .background(stateColors(v.state, v.online).second),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(Tr.t("Left ", "Reste ") + fmtLeft(v.leftS), color = Pal.text, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            if (v.totalLayer > 0) Text("${v.layer} / ${v.totalLayer}", color = Pal.muted, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(Tr.t("End ", "Fin ") + fmtEnd(v.leftS), color = Pal.text, fontSize = 15.sp)
        }
    }
}

/** Nozzle(s), bed, chamber: the temperature tiles (ui/ui_tile.c). */
@Composable
private fun TempRow(v: PrinterView, tileHeight: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (v.nozzleCount >= 2) {
            for (i in 0 until v.nozzleCount.coerceAtMost(v.nozzles.size)) {
                val n = v.nozzles[i]
                TempTile(n.label.ifEmpty { Tr.t("Nozzle ${i + 1}", "Buse ${i + 1}") }, n.temp, n.target,
                    highlight = i == v.nozzleActive, height = tileHeight, modifier = Modifier.weight(1f))
            }
        } else {
            TempTile(Tr.t("Nozzle", "Buse"), v.nozzle, v.nozzleTarget, false, tileHeight, Modifier.weight(1f))
        }
        TempTile(Tr.t("Bed", "Plateau"), v.bed, v.bedTarget, false, tileHeight, Modifier.weight(1f))
        TempTile(Tr.t("Chamber", "Caisson"), v.chamber, 0f, false, tileHeight, Modifier.weight(1f))
    }
}

@Composable
private fun TempTile(label: String, t: Float, target: Float, highlight: Boolean, height: Dp, modifier: Modifier) {
    Column(
        modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Pal.tile)
            .then(if (highlight) Modifier.border(2.dp, Pal.accent, RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = Pal.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtTemp(t), color = Pal.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (target > 0f) Text(" / " + fmtTemp(target), color = Pal.muted, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** The filament units: boxes first, then the external spool and the AMS HT on one row. */
@Composable
private fun Units(v: PrinterView, compact: Boolean) {
    val units = v.unit.filter { it.present && it.slots > 0 }
    if (units.isEmpty()) return
    val boxes = units.filter { it.label != PV_LABEL_EXT && !it.label.startsWith(PV_LABEL_HT) }
    val singles = units - boxes.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        boxes.forEach { UnitRow(it, compact) }
        if (singles.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            singles.forEach { UnitRow(it, compact) }
        }
    }
}

@Composable
private fun UnitRow(u: PvUnit, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(u.label, color = Pal.muted, fontSize = 13.sp, modifier = Modifier.width(if (compact) 58.dp else 72.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until u.slots.coerceIn(0, 4)) {
                val s = u.slot[i]
                val d = if (compact) 34.dp else 52.dp
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(d)
                            .clip(CircleShape)
                            .background(if (s.present) rgb(s.color) else Pal.tile)
                            .border(if (s.selected) 3.dp else 1.dp, if (s.selected) Pal.accent else Color(0x33FFFFFF), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!compact && s.present && s.percent >= 0) {
                            Text("${s.percent}%", fontSize = 11.sp, color = readableOn(s.color))
                        }
                    }
                    if (!compact) Text(s.material.ifEmpty { "--" }, color = Pal.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun readableOn(c: Int): Color {
    val r = c shr 16 and 0xff; val g = c shr 8 and 0xff; val b = c and 0xff
    return if (r * 299 + g * 587 + b * 114 > 150_000) Color.Black else Color.White
}

/**
 * Pause / resume, stop and the light (ui/ui_job.c): all three always drawn,
 * dimmed and inert when they cannot act. Stop is a hold of 1.5 s, pause a touch.
 */
@Composable
private fun JobButtons(v: PrinterView, onControl: (PvControl) -> Unit, size: Dp) {
    val canJob = v.online && v.canControl && v.jobActive
    val canLight = v.online && v.canLight
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val paused = v.state == PvState.PAUSED
        RoundButton(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            if (paused) Tr.t("Resume", "Reprendre") else Tr.t("Pause", "Pause"),
            enabled = canJob, size = size,
        ) { onControl(if (paused) PvControl.RESUME else PvControl.PAUSE) }
        HoldToStop(enabled = canJob, size = size) { onControl(PvControl.STOP) }
        Spacer(Modifier.weight(1f))
        RoundButton(
            if (v.lightOn) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
            Tr.t("Light", "Lumière"), enabled = canLight, size = size,
            tint = if (v.lightOn) Color(0xFFF5D04A) else Pal.text,
        ) { onControl(if (v.lightOn) PvControl.LIGHT_OFF else PvControl.LIGHT_ON) }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector, label: String, enabled: Boolean, size: Dp, tint: Color = Pal.text, onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Pal.tile)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

/** Stop: a hold of 1.5 s, an animation of the fill; letting go before cancels it. */
@Composable
private fun HoldToStop(enabled: Boolean, size: Dp, onStop: () -> Unit) {
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Pal.tile)
            .alpha(if (enabled) 1f else 0.35f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    val run = scope.launch {
                        fill.snapTo(0f)
                        fill.animateTo(1f, tween(1500, easing = LinearEasing))
                        onStop()
                    }
                    tryAwaitRelease()
                    run.cancel()
                    scope.launch { fill.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().padding(0.dp)) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(fill.value).align(Alignment.BottomCenter).background(Pal.danger))
        }
        Icon(Icons.Filled.Stop, Tr.t("Stop (hold)", "Arrêt (maintenir)"), tint = Pal.text, modifier = Modifier.size(size * 0.5f))
    }
}

/** The full view of one printer: bigger tiles, the spools with their material and what is left. */
@Composable
private fun PrinterFull(e: PrinterEntry, onClose: () -> Unit, onControl: (PvControl) -> Unit) {
    val v = e.view ?: PrinterView(name = e.printer.name, brand = e.printer.brand)
    Column(
        Modifier.fillMaxSize().background(Pal.bg).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TitleRow(v, e.printer.cloud) }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, Tr.t("Close", "Fermer"), tint = Pal.text) }
        }
        if (v.notice.isNotEmpty()) {
            Text(v.notice, color = if (v.noticeError) Pal.danger else Pal.muted, fontSize = 18.sp)
        }
        JobBlock(v)
        TempRow(v, tileHeight = 96.dp)
        Units(v, compact = false)
        Spacer(Modifier.weight(1f))
        JobButtons(v, onControl, size = 84.dp)
    }
}
