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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
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
import com.damou.tigertouch.fleet.FleetFilter
import com.damou.tigertouch.fleet.JobUi
import com.damou.tigertouch.events.FleetEvent
import com.damou.tigertouch.fleet.FleetPage
import kotlinx.coroutines.flow.StateFlow
import com.damou.tigertouch.fleet.PrinterUi
import com.damou.tigertouch.fleet.TempUi
import com.damou.tigertouch.fleet.UnitUi
import com.damou.tigertouch.model.Brands
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many printers the home page shows at once. */
enum class Density {
    /** The largest of the others that shows the whole fleet without scrolling. */
    AUTO,
    /** Tiles readable from across the room: 15 on one screen. */
    WALL,
    /** Large cards with the temperatures, the spools and the buttons. */
    CARDS,
    /** One row per printer, with its buttons: the firmware's list. */
    LIST;

    fun label(): String = when (this) {
        AUTO -> "Auto"
        WALL -> Tr.t("Wall", "Mur")
        CARDS -> Tr.t("Cards", "Cartes")
        LIST -> Tr.t("List", "Liste")
    }
}

private const val CARD_MIN_W = 380
/** About what a card with one box of spools takes. */
private const val CARD_H = 400
private const val GAP = 12

/**
 * The printers page. Where the Panda Touch scrolls one card at a time on its
 * 5" panel, the tablet shows the whole fleet: a wall of tiles readable from
 * across the room, large cards, or a list.
 */
@Composable
fun DashboardScreen(
    fleet: FleetView,
    /** The account has printers, even if every one is switched off. */
    accountHasPrinters: Boolean,
    link: CloudLink,
    accountError: String,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onControl: (String, PvControl) -> Unit,
    density: Density,
    onDensity: (Density) -> Unit,
    /** Which printers, in which order. */
    filter: FleetFilter,
    onFilter: (FleetFilter) -> Unit,
    /** The printers shown and the summary, as [filter] makes them. */
    page: FleetPage,
    /** Which printers blink: an event nobody acknowledged. */
    alerts: AlertView,
    /** Events not yet acknowledged, for the bell. */
    unacked: Int,
    /** The journal, read only while it is open. */
    events: StateFlow<List<FleetEvent>>,
    /** Someone touched this printer: its events are seen. */
    onAck: (String) -> Unit,
    onAckAll: () -> Unit,
    /** MJPEG stream URL by printer id, for the printers that have a camera. */
    cameras: Map<String, String> = emptyMap(),
    /** The tablet's battery beside the clock (Settings: off for a tablet that never leaves its charger). */
    showBattery: Boolean = true,
    /** Demo mode is on: a discreet banner says so. */
    demo: Boolean = false,
) {
    var openId by remember { mutableStateOf<String?>(null) }
    var camId by remember { mutableStateOf<String?>(null) }
    var journal by remember { mutableStateOf(false) }
    // The printers shown, in their order: this screen recomposes when that changes, not at every refresh.
    // Kept as the same instances while equal: the views below skip when they did not change.
    val ids = remember(page.ids) { page.ids }
    val summary = page.summary
    val brands = remember(summary.brands) { summary.brands }
    var area by remember { mutableStateOf(IntSize.Zero) }

    // No background: the window's is Pal.bg (themes.xml).
    Column(Modifier.fillMaxSize()) {
        Header(link, showBattery, demo, density, onDensity, onRefresh, onSettings, filter, brands, onFilter) {
            Bell(unacked) { journal = true }
        }
        if (fleet.ids.isNotEmpty()) SummaryBar(summary, filter, onFilter)
        if (accountError.isNotEmpty()) {
            Text(accountError, color = Pal.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (ids.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (filter.active && fleet.ids.isNotEmpty()) Text(
                    Tr.t("No printer matches the filter.", "Aucune imprimante ne correspond au filtre."),
                    color = Pal.muted, fontSize = 20.sp,
                    modifier = Modifier.clickable { onFilter(filter.copy(state = null, brand = null)) }.padding(16.dp),
                ) else Text(
                    if (!accountHasPrinters) Tr.t(
                        "No printer in the account yet. Add them in Tiger Studio Manager.",
                        "Aucune imprimante dans le compte. Ajoutez-les dans Tiger Studio Manager.",
                    ) else Tr.t("Every printer is switched off (Settings).", "Toutes les imprimantes sont désactivées (Réglages)."),
                    color = Pal.muted, fontSize = 20.sp,
                )
            }
        } else {
            // The size of the page, read once: no subcomposition at every refresh.
            val dens = LocalDensity.current
            Box(Modifier.fillMaxSize().onSizeChanged { area = it }) {
                if (area != IntSize.Zero) {
                    val w = with(dens) { area.width.toDp().value.toInt() }
                    val h = with(dens) { area.height.toDp().value.toInt() }
                    val shown = if (density == Density.AUTO) autoDensity(ids.size, w, h) else density
                    // A touch on a printer that blinks acknowledges it; the next one opens it.
                    val onOpen: (String) -> Unit = { id -> if (alerts.has(id)) onAck(id) else openId = id }
                    when (shown) {
                        Density.WALL -> Wall(fleet, ids, w, h, alerts, onOpen)
                        Density.LIST -> FleetList(fleet, ids, alerts, onOpen, onControl)
                        else -> Cards(fleet, ids, alerts, onOpen, onControl, cameras) { camId = it }
                    }
                }
            }
        }
    }

    val open = openId?.let { fleet.printer(it) }
    if (open != null) {
        Dialog(onDismissRequest = { openId = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            ImmersiveDialog()
            PrinterFull(
                open, demo, onClose = { openId = null }, onControl = { onControl(open.id, it) },
                onCamera = cameras[open.id]?.let { { camId = open.id } },
            )
        }
    }

    if (journal) {
        val list by events.collectAsState()
        JournalDialog(list, demo, onAckAll, onAck) { journal = false }
    }

    val cam = camId?.let { id -> cameras[id]?.let { url -> id to url } }
    if (cam != null) {
        val name = fleet.get(cam.first)?.name ?: ""
        CameraDialog(title = name, url = cam.second, onClose = { camId = null })
    }
}

/** Cards when they all fit on the screen, the wall otherwise. */
private fun autoDensity(n: Int, w: Int, h: Int): Density {
    val cols = ((w - 2 * 16 + GAP) / (CARD_MIN_W + GAP)).coerceAtLeast(1)
    val rows = ((h - 2 * 16 + GAP) / (CARD_H + GAP)).coerceAtLeast(1)
    return if (n <= cols * rows) Density.CARDS else Density.WALL
}

@Composable
private fun Header(
    link: CloudLink, showBattery: Boolean, demo: Boolean, density: Density, onDensity: (Density) -> Unit,
    onRefresh: () -> Unit, onSettings: () -> Unit, filter: FleetFilter, brands: List<String>, onFilter: (FleetFilter) -> Unit,
    bell: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tiger Touch", color = Pal.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (demo) DemoBadge(Modifier.padding(start = 10.dp))
        Spacer(Modifier.width(16.dp))
        DensitySwitch(density, onDensity)
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortMenu(filter, onFilter)
            BrandMenu(brands, filter, onFilter)
        }
        Spacer(Modifier.weight(1f))
        bell()
        Spacer(Modifier.width(8.dp))
        Clock()
        if (showBattery) {
            Spacer(Modifier.width(16.dp))
            BatteryIndicator()
        }
        Spacer(Modifier.width(16.dp))
        val (icon, tint) = when (link) {
            CloudLink.OK -> Icons.Filled.Cloud to Pal.ok
            CloudLink.CONNECTING -> Icons.Filled.CloudSync to Pal.muted
            CloudLink.NONE -> Icons.Filled.CloudOff to Pal.danger
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        IconButton(onClick = onRefresh, Modifier.size(56.dp)) { Icon(Icons.Filled.Refresh, Tr.t("Refresh", "Actualiser"), tint = Pal.text) }
        IconButton(onClick = onSettings, Modifier.size(56.dp)) { Icon(Icons.Filled.Settings, Tr.t("Settings", "Réglages"), tint = Pal.text) }
    }
}

/** The time of day: the only part of the header that changes on its own, so the only part redrawn. */
@Composable
private fun Clock() {
    var now by remember { mutableStateOf(fmtClock(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) { now = fmtClock(System.currentTimeMillis()); delay(1000) }
    }
    Text(now, color = Pal.text, fontSize = 24.sp)
}

@Composable
private fun DensitySwitch(density: Density, onDensity: (Density) -> Unit) {
    Row(Modifier.background(Pal.card, RoundedCornerShape(12.dp))) {
        for (d in Density.entries) {
            val on = d == density
            Box(
                Modifier
                    .height(44.dp)
                    .background(if (on) Pal.tile else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onDensity(d) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(d.label(), color = if (on) Pal.accent else Pal.muted, fontSize = 16.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Always on screen while demo mode is on: nothing shown is real. */
@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    Text(
        Tr.t("Demo", "Démo"), color = Pal.bg, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.background(Color(0xFFF5B84A), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 3.dp),
    )
}

// ---- Cards ----------------------------------------------------------------------

@Composable
private fun Cards(
    fleet: FleetView, ids: List<String>, alerts: AlertView, onOpen: (String) -> Unit, onControl: (String, PvControl) -> Unit,
    cameras: Map<String, String>, onCamera: (String) -> Unit,
) {
    val d = LocalDensity.current
    val pens = remember(d) { Pens(d) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = CARD_MIN_W.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP.dp),
        verticalArrangement = Arrangement.spacedBy(GAP.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(ids, key = { it }) { id ->
            PrinterCard(fleet, id, pens, alerts, hasCamera = id in cameras, onOpen, onControl, onCamera)
        }
    }
}

@Composable
private fun TitleRow(p: PrinterUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Pal.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(Brands.label(p.brand) + if (p.cloud) " · Cloud" else " · LAN", color = Pal.muted, fontSize = 14.sp)
        }
        StatePill(p, 15)
    }
}

/** The printer's state in its colour; grey and "Offline" when it does not answer. */
@Composable
fun StatePill(p: PrinterUi, size: Int) {
    val (bg, fg) = stateColors(p.state, p.online)
    Text(
        stateWord(p), color = fg, fontSize = size.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

fun stateWord(p: PrinterUi): String = when {
    p.connecting -> Tr.t("Connecting", "Connexion")
    !p.online -> Tr.t("Offline", "Hors ligne")
    else -> stateText(p.state)
}

/** A plain filled bar: Material 3's indicator draws a gap and an end dot that read as noise here. */
@Composable
fun ProgressBar(p: PrinterUi, height: Dp, modifier: Modifier = Modifier) {
    val fg = stateColors(p.state, p.online).second
    val f = p.progress / 100f
    // Drawn, not clipped: a clip is a layer of its own on this GPU.
    Box(modifier.fillMaxWidth().height(height).drawBehind {
        val r = androidx.compose.ui.geometry.CornerRadius(this.size.height / 2)
        drawRoundRect(Pal.tile, cornerRadius = r)
        if (f > 0f) drawRoundRect(fg, size = this.size.copy(width = (this.size.width * f).coerceAtLeast(this.size.height)), cornerRadius = r)
    })
}

@Composable
private fun JobBlock(p: PrinterUi) {
    val active = p.jobActive || p.state == PvState.DONE
    Column(Modifier.dim(if (active) 1f else 0.45f)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${p.progress} %", color = Pal.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(p.file.ifEmpty { "--" }, color = Pal.muted, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(bottom = 6.dp))
        }
        ProgressBar(p, 10.dp)
        Spacer(Modifier.height(6.dp))
        Row {
            Text(Tr.t("Left ", "Reste ") + fmtLeftMin(p.leftMin), color = Pal.text, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            if (p.totalLayer > 0) Text("${p.layer} / ${p.totalLayer}", color = Pal.muted, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(Tr.t("End ", "Fin ") + fmtClockMin(p.endMin), color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Nozzle(s), bed, chamber: the temperature tiles (ui/ui_tile.c). */
@Composable
private fun TempRow(p: PrinterUi, tileHeight: Dp) {
    // Four nozzles leave no room for long words: "N1", "B1"... and no chamber tile when there is no reading.
    val many = p.nozzles.size >= 3
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        p.nozzles.forEachIndexed { i, n ->
            TempTile(if (many) n.copy(label = Tr.t("N", "B") + (i + 1)) else n, tileHeight, Modifier.weight(1f))
        }
        TempTile(p.bed, tileHeight, Modifier.weight(1f))
        if (p.chamber.temp > 0 || !many) TempTile(p.chamber, tileHeight, Modifier.weight(1f))
    }
}

@Composable
private fun TempTile(t: TempUi, height: Dp, modifier: Modifier) {
    Column(
        modifier
            .height(height)
            .background(Pal.tile, RoundedCornerShape(12.dp))
            .then(if (t.active) Modifier.border(2.dp, Pal.accent, RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(t.label, color = Pal.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtTemp(t.temp), color = Pal.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (t.target > 0) Text(" / " + fmtTemp(t.target), color = Pal.muted, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** The filament units: boxes first, then the external spool and the AMS HT on one row. */
@Composable
private fun Units(units: List<UnitUi>, compact: Boolean) {
    if (units.isEmpty()) return
    val boxes = units.filter { !it.single }
    val singles = units.filter { it.single }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        boxes.forEach { UnitRow(it, compact) }
        if (singles.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            singles.forEach { UnitRow(it, compact) }
        }
    }
}

@Composable
private fun UnitRow(u: UnitUi, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(u.label, color = Pal.muted, fontSize = 13.sp, modifier = Modifier.width(if (compact) 58.dp else 72.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in u.slots) {
                val d = if (compact) 34.dp else 52.dp
                // The spool at work only: a holder that reports 0 % may just not know.
                val low = s.present && s.selected && s.percent in 0 until PrinterUi.LOW_SPOOL
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(d)
                            .background(if (s.present) rgb(s.color) else Pal.tile, CircleShape)
                            .border(
                                if (s.selected || low) 3.dp else 1.dp,
                                when { low -> Pal.danger; s.selected -> Pal.accent; else -> Color(0x33FFFFFF) },
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!compact && s.present && s.percent >= 0) {
                            Text("${s.percent}%", fontSize = 13.sp, color = readableOn(s.color))
                        }
                    }
                    if (!compact) Text(s.material.ifEmpty { "--" }, color = Pal.muted, fontSize = 13.sp)
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
fun JobButtons(j: JobUi, onControl: (PvControl) -> Unit, size: Dp, onCamera: (() -> Unit)? = null, spread: Boolean = true) {
    val canJob = j.canJob
    val canLight = j.canLight
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val paused = j.paused
        RoundButton(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            if (paused) Tr.t("Resume", "Reprendre") else Tr.t("Pause", "Pause"),
            enabled = canJob, size = size,
        ) { onControl(if (paused) PvControl.RESUME else PvControl.PAUSE) }
        HoldToStop(enabled = canJob, size = size) { onControl(PvControl.STOP) }
        if (spread) Spacer(Modifier.weight(1f))
        // Only drawn when the printer has a camera this app can show.
        if (onCamera != null) RoundButton(Icons.Filled.Videocam, Tr.t("Camera", "Caméra"), enabled = true, size = size, onClick = onCamera)
        RoundButton(
            if (j.lightOn) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
            Tr.t("Light", "Lumière"), enabled = canLight, size = size,
            tint = if (j.lightOn) Color(0xFFF5D04A) else Pal.text,
        ) { onControl(if (j.lightOn) PvControl.LIGHT_OFF else PvControl.LIGHT_ON) }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector, label: String, enabled: Boolean, size: Dp, tint: Color = Pal.text, onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .background(Pal.tile, CircleShape)
            .dim(if (enabled) 1f else 0.35f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = size / 2),
                enabled = enabled, onClick = onClick,
            ),
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
            .background(Pal.tile, CircleShape)
            .dim(if (enabled) 1f else 0.35f)
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
        // Read while drawing: the hold redraws this button, it does not recompose the card.
        Box(Modifier.fillMaxSize().drawBehind {
            val f = fill.value
            // A disc that grows to the edge: no clip needed.
            if (f > 0f) drawCircle(Pal.danger, radius = this.size.minDimension / 2 * f)
        })
        Icon(Icons.Filled.Stop, Tr.t("Stop (hold)", "Arrêt (maintenir)"), tint = Pal.text, modifier = Modifier.size(size * 0.5f))
    }
}

/** The full view of one printer: bigger tiles, the spools with their material and what is left. */
@Composable
private fun PrinterFull(p: PrinterUi, demo: Boolean, onClose: () -> Unit, onControl: (PvControl) -> Unit, onCamera: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().background(Pal.bg).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TitleRow(p) }
            if (demo) DemoBadge(Modifier.padding(start = 12.dp))
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onClose, Modifier.size(56.dp)) { Icon(Icons.Filled.Close, Tr.t("Close", "Fermer"), tint = Pal.text) }
        }
        if (p.notice.isNotEmpty()) {
            Text(p.notice, color = if (p.noticeError) Pal.danger else Pal.muted, fontSize = 18.sp)
        }
        JobBlock(p)
        TempRow(p, tileHeight = 96.dp)
        Units(p.units, compact = false)
        Spacer(Modifier.weight(1f))
        JobButtons(p.job, onControl, size = 84.dp, onCamera = onCamera)
    }
}

/**
 * Fades what it wraps without an offscreen buffer: the alpha goes to each
 * drawing, which costs nothing, where Modifier.alpha() draws into a layer of its
 * own first.
 */
fun Modifier.dim(alpha: Float): Modifier =
    if (alpha >= 1f) this else graphicsLayer { this.alpha = alpha; compositingStrategy = CompositingStrategy.ModulateAlpha }
