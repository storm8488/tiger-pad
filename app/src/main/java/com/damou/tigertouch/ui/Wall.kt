package com.damou.tigertouch.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.damou.tigertouch.cam.CameraHub
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.fleet.Attention
import com.damou.tigertouch.fleet.PrinterUi
import com.damou.tigertouch.model.PvState
import kotlin.math.min

private const val PAD = 12
private const val GAP = 10

/**
 * The wall: every printer on one screen, without scrolling, readable from three
 * metres. Each tile is tinted with its state and says the name, the state, the
 * percentage in very large type, the time left and the time it ends.
 */
@Composable
fun Wall(
    fleet: FleetView, ids: List<String>, width: Int, height: Int, alerts: AlertView, onOpen: (String) -> Unit,
    /** The cameras, by printer id, and the hub that feeds them; null when the tiles show none. */
    cameras: Map<String, String> = emptyMap(), camHub: CameraHub? = null,
) {
    val (cols, rows) = wallGrid(ids.size, width, height)
    val tileH = (height - 2 * PAD - (rows - 1) * GAP) / rows
    val tileW = (width - 2 * PAD - (cols - 1) * GAP) / cols
    // At least 40 sp, as large as the tile allows.
    val pct = (tileH * 0.30f).toInt().coerceIn(40, 110)
    val compact = tileH < 180
    val d = LocalDensity.current
    val paints = remember(d, pct, compact) { WallPaints(d, pct, compact) }
    Column(Modifier.fillMaxSize().padding(PAD.dp), verticalArrangement = Arrangement.spacedBy(GAP.dp)) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP.dp)) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    if (i < ids.size) {
                        val id = ids[i]
                        key(id) {
                            WallTile(fleet, id, paints, alerts, onOpen, Modifier.weight(1f).fillMaxHeight(),
                                camHub = camHub, camUrl = cameras[id], tileW = tileW)
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The columns and rows that give the largest tiles, a little wider than tall. */
fun wallGrid(n: Int, w: Int, h: Int): Pair<Int, Int> {
    var best = 1 to n.coerceAtLeast(1)
    var bestScore = -1f
    for (cols in 1..min(n, 8).coerceAtLeast(1)) {
        val rows = (n + cols - 1) / cols
        val tw = (w - 2 * PAD - (cols - 1) * GAP).toFloat() / cols
        val th = (h - 2 * PAD - (rows - 1) * GAP).toFloat() / rows
        val score = min(tw / 1.15f, th)
        if (score > bestScore) { bestScore = score; best = cols to rows }
    }
    return best
}

/**
 * One tile, drawn in one piece with the platform's text: a dozen composables
 * per tile, or Compose's text measurer, cost the Atom several milliseconds at
 * each change. Here a change is one node and a few drawText calls.
 */
@Composable
private fun WallTile(
    fleet: FleetView, id: String, paints: WallPaints, alerts: AlertView, onOpen: (String) -> Unit, modifier: Modifier,
    camHub: CameraHub?, camUrl: String?, tileW: Int,
) {
    // The camera, under the words, for a printer at work only: read as its own state, the tile
    // recomposes when the print starts or ends, not at every refresh.
    val cam = camHub != null && camUrl != null && rememberCameraShown(fleet, id)
    Box(modifier.alertBorder(alerts, fleet, id, 16.dp).clickable { onOpen(id) }) {
        // The ground, the name, the state, the bar and the times; the number too when no picture is shown.
        Spacer(
            Modifier
                .fillMaxSize()
                // A texture of its own: the GPU redraws the tile that changed and copies the others.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    // The printer is read while drawing: a change redraws this tile, it recomposes nothing.
                    val p = fleet.get(id)
                    val t = p?.let { TileText(it, paints, size.width) }
                    onDrawBehind { t?.draw(this, withBig = !cam) }
                },
        )
        if (cam) {
            val d = LocalDensity.current
            val px = with(d) { tileW.dp.toPx() }.toInt()
            val feed = rememberFeed(camHub!!, camUrl!!, px)
            // The picture, whole and untinted, between the words above and the times below, framed in the state's colour.
            val frame by remember(fleet, id) { derivedStateOf { fleet.get(id)?.let { stateColors(it.state, it.online).second } } }
            CameraLayer(
                feed,
                Modifier.fillMaxSize().padding(
                    start = with(d) { paints.padX.toDp() }, end = with(d) { paints.padX.toDp() },
                    top = with(d) { paints.topBand().toDp() }, bottom = with(d) { paints.bottomBand().toDp() },
                ),
                radius = 10.dp, behind = Color.Transparent, label = false, frame = frame,
            )
            // The number over the picture, with a shadow that keeps it readable on any picture.
            Spacer(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val p = fleet.get(id)
                        val t = p?.let { TileText(it, paints, size.width) }
                        onDrawBehind { t?.drawBig(this, shadow = true) }
                    },
            )
        }
    }
}

/** The pens of the wall, made once for a tile size. */
private class WallPaints(private val d: Density, pctSp: Int, val compact: Boolean) {
    private fun pen(sp: Int, tf: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = with(d) { sp.sp.toPx() }
        typeface = tf
    }

    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val name = pen(20, bold)
    val state = pen(if (compact) 15 else 17, medium)
    val spool = pen(14, bold)
    val big = pen(pctSp, bold)
    val sign = pen(pctSp / 2, medium)
    /** The same, shadowed: over a camera picture. */
    val bigShadow = pen(pctSp, bold).apply { setShadowLayer(with(d) { 6.dp.toPx() }, 0f, with(d) { 2.dp.toPx() }, 0xCC000000.toInt()) }
    val signShadow = pen(pctSp / 2, medium).apply { setShadowLayer(with(d) { 4.dp.toPx() }, 0f, with(d) { 1.dp.toPx() }, 0xCC000000.toInt()) }
    val idle = pen(26, Typeface.DEFAULT)
    val time = pen(18, Typeface.DEFAULT)
    val end = pen(18, medium)
    val small = pen(15, Typeface.DEFAULT)
    val padX = with(d) { 14.dp.toPx() }
    val padY = with(d) { 10.dp.toPx() }
    val radius = with(d) { 16.dp.toPx() }
    val barH = with(d) { 8.dp.toPx() }
    val gap = with(d) { 6.dp.toPx() }

    /** What the name and the state take at the top of a tile, in pixels. */
    fun topBand(): Float = padY + name.lineH() + (if (compact) 0f else state.lineH()) + gap
    /** What the bar and the times take at the bottom. */
    fun bottomBand(): Float = padY + time.lineH() + gap + barH + gap
}

/** What a tile says, laid out for its width. */
private class TileText(p: PrinterUi, private val k: WallPaints, w: Float) {
    private val inner = w - 2 * k.padX
    private val colors = stateColors(p.state, p.online)
    // A printer that lapsed keeps its number: the print goes on, only the link blinked.
    private val showPct = (p.online || p.lapse) && (p.jobActive || p.state == PvState.DONE || p.state == PvState.ERROR)
    private val progress = p.progress / 100f
    private val state = Line(stateWord(p), k.state, colors.second.toArgb(), inner)
    private val name = Line(p.name, k.name, Pal.text.toArgb(), if (k.compact) inner - state.w - k.gap else inner)
    private val spool = if (p.online && p.spool in 0 until PrinterUi.LOW_SPOOL)
        Line(Tr.t("Spool ${p.spool} %", "Bobine ${p.spool} %"), k.spool, Pal.bg.toArgb(), inner) else null
    private val big = if (showPct) Line("${p.progress}", k.big, Pal.text.toArgb(), inner) else Line(
        when {
            p.connecting -> Tr.t("Connecting…", "Connexion…")
            !p.online -> Tr.t("No answer", "Pas de réponse")
            else -> Tr.t("Ready", "Prête")
        },
        k.idle, Pal.muted.toArgb(), inner,
    )
    private val sign = if (showPct) Line(" %", k.sign, Pal.text.toArgb(), inner) else null
    private val left: Line
    private val right: Line?

    init {
        when {
            p.online && p.jobActive -> {
                right = Line(Tr.t("End ", "Fin ") + fmtClockMin(p.endMin), k.end, Pal.text.toArgb(), inner)
                left = Line(fmtLeftMin(p.leftMin), k.time, Pal.text.toArgb(), inner - right.w - k.gap)
            }
            p.attention == Attention.DONE -> {
                left = Line(Tr.t("Clear the plate", "Plateau à vider"), k.time, colors.second.toArgb(), inner)
                right = null
            }
            p.notice.isNotEmpty() -> {
                left = Line(p.notice, k.small, (if (p.noticeError) Pal.danger else Pal.muted).toArgb(), inner)
                right = null
            }
            else -> {
                val noz = p.nozzles.firstOrNull { it.active } ?: p.nozzles[0]
                left = Line(
                    Tr.t("Nozzle ", "Buse ") + fmtTemp(noz.temp) + Tr.t(" · Bed ", " · Plateau ") + fmtTemp(p.bed.temp),
                    k.small, Pal.muted.toArgb(), inner,
                )
                right = null
            }
        }
    }

    /** Where the number sits: centred between the top band and the bar (or the bottom line). */
    private fun DrawScope.bigTop(): Float {
        val h = size.height
        val bottomY = h - k.padY - left.h
        val barY = bottomY - k.gap - k.barH
        val y = k.padY + name.h + if (k.compact) 0f else state.h
        val bigBottom = if (showPct) barY else bottomY
        return y + (bigBottom - y - big.h) / 2f
    }

    /** The number and its sign alone, over a picture when [shadow]. */
    fun drawBig(s: DrawScope, shadow: Boolean) = with(s) {
        val c = drawContext.canvas.nativeCanvas
        val top = bigTop()
        val bp = if (shadow && showPct) k.bigShadow else big.pen
        bp.color = Pal.text.toArgb()
        c.drawText(big.text, k.padX, top + bp.base(), bp)
        val sg = sign ?: return@with
        val sp = if (shadow) k.signShadow else sg.pen
        sp.color = Pal.text.toArgb()
        c.drawText(sg.text, k.padX + big.w, top + bp.base(), sp)
    }

    fun draw(s: DrawScope, withBig: Boolean = true) = with(s) {
        val c = drawContext.canvas.nativeCanvas
        val w = size.width
        val h = size.height
        drawRoundRect(colors.first, cornerRadius = CornerRadius(k.radius))
        var y = k.padY
        name.draw(c, k.padX, y)
        if (k.compact) state.draw(c, w - k.padX - state.w, y + (name.h - state.h) / 2f)
        y += name.h
        if (!k.compact) {
            state.draw(c, k.padX, y)
            y += state.h
        }
        val bottomY = h - k.padY - left.h
        val barY = bottomY - k.gap - k.barH
        if (spool != null) {
            // Top right, or just above the bar when the tile has no second line.
            val sw = spool.w + 2 * k.gap
            val sh = spool.h
            val sx = w - k.padX - sw
            val sy = if (k.compact) barY - k.gap - sh else k.padY + name.h
            drawRoundRect(Pal.danger, Offset(sx, sy), Size(sw, sh), CornerRadius(sh / 2))
            spool.draw(c, sx + k.gap, sy)
        }
        if (withBig) drawBig(s, shadow = false)
        if (showPct) {
            val r = CornerRadius(k.barH / 2)
            val full = w - 2 * k.padX
            drawRoundRect(Pal.tile, Offset(k.padX, barY), Size(full, k.barH), r)
            if (progress > 0f) drawRoundRect(colors.second, Offset(k.padX, barY), Size((full * progress).coerceAtLeast(k.barH), k.barH), r)
        }
        left.draw(c, k.padX, bottomY)
        right?.draw(c, w - k.padX - right.w, bottomY)
    }
}
