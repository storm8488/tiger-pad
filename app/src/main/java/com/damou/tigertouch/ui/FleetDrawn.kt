package com.damou.tigertouch.ui

import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.fleet.PrinterUi
import com.damou.tigertouch.fleet.TempUi
import com.damou.tigertouch.fleet.UnitUi
import com.damou.tigertouch.model.Brands
import com.damou.tigertouch.model.PvControl
import com.damou.tigertouch.model.PvState

/*
 * The card and the list row of the home page, drawn: what the printer says is
 * one node read while drawing (a change redraws it, recomposes nothing), and
 * only the buttons are composables, recomposed when what they can do changes.
 */

// ---- The card -------------------------------------------------------------------------

private const val CARD_PAD = 18
private const val TITLE_H = 50
private const val JOB_H = 78
private const val TEMPS_H = 64
private const val UNIT_ROW = 34
private const val SPACE = 12

/** How many rows of spools a card draws. */
private fun unitRows(p: PrinterUi?): Int {
    if (p == null) return 0
    val boxes = p.units.count { !it.single }
    return boxes + if (p.units.any { it.single }) 1 else 0
}

/** One printer: the firmware's card (ui/ui_cards.c), larger. */
@Composable
fun PrinterCard(
    fleet: FleetView, id: String, pens: Pens, alerts: AlertView, hasCamera: Boolean,
    onOpen: (String) -> Unit, onControl: (String, PvControl) -> Unit, onCamera: (String) -> Unit,
) {
    // Only what changes the card's height or its buttons recomposes it.
    val rows by remember(fleet, id) { derivedStateOf(structuralEqualityPolicy()) { unitRows(fleet.get(id)) } }
    val job by remember(fleet, id) { derivedStateOf(structuralEqualityPolicy()) { fleet.get(id)?.job } }
    val infoH = TITLE_H + SPACE + JOB_H + SPACE + TEMPS_H + if (rows > 0) SPACE + rows * UNIT_ROW + (rows - 1) * 8 else 0
    Column(
        Modifier
            .alertBorder(alerts, fleet, id, 18.dp)
            // A texture of its own: scrolling moves it, a change redraws it alone.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .background(Pal.card, RoundedCornerShape(18.dp))
            .clickable { onOpen(id) }
            .padding(CARD_PAD.dp),
    ) {
        Spacer(
            Modifier.fillMaxWidth().height(infoH.dp).drawWithCache {
                val p = fleet.get(id)
                val info = p?.let { CardInfo(it, pens, size.width) }
                onDrawBehind { info?.draw(this) }
            },
        )
        Spacer(Modifier.height(SPACE.dp))
        job?.let { j ->
            JobButtons(j, { onControl(id, it) }, size = 56.dp, onCamera = if (hasCamera) ({ onCamera(id) }) else null)
        }
    }
}

/** The card's words and shapes, laid out for its width. */
private class CardInfo(private val p: PrinterUi, private val k: Pens, private val w: Float) {
    private val colors = stateColors(p.state, p.online)
    private val pill = Line(stateWord(p), k.pen(15, Weight.MEDIUM), colors.second.toArgb())
    private val pillW = pill.w + k.px(28)
    private val name = Line(p.name, k.pen(22, Weight.MEDIUM), Pal.text.toArgb(), w - pillW - k.px(12))
    private val sub = when {
        p.notice.isNotEmpty() -> Line(p.notice, k.pen(14), (if (p.noticeError) Pal.danger else Pal.muted).toArgb(), w)
        else -> Line(Brands.label(p.brand) + if (p.cloud) " · Cloud" else " · LAN", k.pen(14), Pal.muted.toArgb(), w)
    }
    // An idle printer's job block steps back.
    private val jobAlpha = if (p.jobActive || p.state == PvState.DONE) 1f else 0.45f
    private fun c(col: Color) = col.copy(alpha = col.alpha * jobAlpha).toArgb()
    private val pct = Line("${p.progress} %", k.pen(34, Weight.BOLD), c(Pal.text))
    private val file = Line(p.file.ifEmpty { "--" }, k.pen(15), c(Pal.muted), w - pct.w - k.px(12))
    private val left = Line(Tr.t("Left ", "Reste ") + fmtLeftMin(p.leftMin), k.pen(15), c(Pal.text))
    private val layers = if (p.totalLayer > 0) Line("${p.layer} / ${p.totalLayer}", k.pen(15), c(Pal.muted)) else null
    private val end = Line(Tr.t("End ", "Fin ") + fmtClockMin(p.endMin), k.pen(15, Weight.MEDIUM), c(Pal.text))
    // Four nozzles leave no room for long words: "N1", "B1"... and no chamber tile when there is no reading.
    private val many = p.nozzles.size >= 3
    private val temps: List<TempUi> = p.nozzles.mapIndexed { i, n -> if (many) n.copy(label = Tr.t("N", "B") + (i + 1)) else n } +
        p.bed + if (p.chamber.temp > 0 || !many) listOf(p.chamber) else emptyList()
    private val tileW = (w - k.px(8) * (temps.size - 1)) / temps.size
    private val tempLines = temps.map { t ->
        val v = Line(fmtTemp(t.temp), k.pen(20, Weight.MEDIUM), Pal.text.toArgb())
        Triple(Line(t.label, k.pen(13), Pal.muted.toArgb(), tileW - k.px(20)), v,
            if (t.target > 0) Line(" / " + fmtTemp(t.target), k.pen(13), Pal.muted.toArgb(), tileW - k.px(20) - v.w) else null)
    }
    private val unitLabels = p.units.associateWith { Line(it.label, k.pen(13), Pal.muted.toArgb(), k.px(56)) }

    fun draw(s: DrawScope) = with(s) {
        val cv = drawContext.canvas.nativeCanvas
        // Title: the name, the brand (or why nothing is shown), the state in its colour.
        val pillH = pill.h + k.px(12)
        drawRoundRect(colors.first, Offset(w - pillW, 0f), Size(pillW, pillH), CornerRadius(pillH / 2))
        pill.draw(cv, w - pillW + k.px(14), k.px(6))
        name.draw(cv, 0f, 0f)
        sub.draw(cv, 0f, name.h)
        // The job: per cent and file, the bar, time left, layers, end.
        var y = k.px(TITLE_H + SPACE)
        pct.draw(cv, 0f, y)
        file.draw(cv, pct.w + k.px(12), y + pct.h - file.h - k.px(4))
        y += pct.h + k.px(4)
        val barH = k.px(10)
        drawRoundRect(Pal.tile, Offset(0f, y), Size(w, barH), CornerRadius(barH / 2))
        if (p.progress > 0) drawRoundRect(
            colors.second.copy(alpha = jobAlpha), Offset(0f, y), Size((w * p.progress / 100f).coerceAtLeast(barH), barH), CornerRadius(barH / 2),
        )
        y += barH + k.px(6)
        left.draw(cv, 0f, y)
        layers?.draw(cv, (w - layers.w) / 2, y)
        end.drawRight(cv, w, y)
        // The temperature tiles.
        y = k.px(TITLE_H + SPACE + JOB_H + SPACE)
        val th = k.px(TEMPS_H)
        temps.forEachIndexed { i, t ->
            val x = i * (tileW + k.px(8))
            drawRoundRect(Pal.tile, Offset(x, y), Size(tileW, th), CornerRadius(k.px(12)))
            if (t.active) drawRoundRect(Pal.accent, Offset(x + k.px(1), y + k.px(1)), Size(tileW - k.px(2), th - k.px(2)),
                CornerRadius(k.px(11)), style = Stroke(k.px(2)))
            val (label, v, target) = tempLines[i]
            val top = y + (th - label.h - v.h) / 2
            label.draw(cv, x + k.px(10), top)
            v.draw(cv, x + k.px(10), top + label.h)
            target?.draw(cv, x + k.px(10) + v.w, top + label.h + v.pen.base() - target.pen.base())
        }
        // The spools: boxes first, one row each, then the external spool and the AMS HT on one row.
        y += th + k.px(SPACE)
        var x = 0f
        for (u in p.units.filter { !it.single }) {
            drawUnit(cv, u, 0f, y)
            y += k.px(UNIT_ROW + 8)
        }
        for (u in p.units.filter { it.single }) x = drawUnit(cv, u, x, y) + k.px(16)
    }

    /** One unit: its label and a circle per slot. Returns where it ends. */
    private fun DrawScope.drawUnit(cv: Canvas, u: UnitUi, x0: Float, y: Float): Float {
        val label = unitLabels.getValue(u)
        label.draw(cv, x0, y + (k.px(UNIT_ROW) - label.h) / 2)
        var x = x0 + k.px(58)
        val r = k.px(UNIT_ROW) / 2
        for (s in u.slots) {
            val center = Offset(x + r, y + r)
            drawCircle(if (s.present) rgb(s.color) else Pal.tile, r, center)
            val low = s.present && s.percent in 0 until PrinterUi.LOW_SPOOL
            val (bw, bc) = when {
                low -> k.px(3) to Pal.danger
                s.selected -> k.px(3) to Pal.accent
                else -> k.px(1) to Color(0x33FFFFFF)
            }
            drawCircle(bc, r - bw / 2, center, style = Stroke(bw))
            x += 2 * r + k.px(8)
        }
        return x
    }
}

// ---- The list row ---------------------------------------------------------------------

/**
 * One row of the list: the firmware's list view (ui_cards.c, ui_cards_set_list):
 * the same reading as a card, with less drawn, and the pause, stop and light buttons.
 */
@Composable
fun ListRow(fleet: FleetView, id: String, pens: Pens, alerts: AlertView, onOpen: (String) -> Unit, onControl: (String, PvControl) -> Unit) {
    val job by remember(fleet, id) { derivedStateOf(structuralEqualityPolicy()) { fleet.get(id)?.job } }
    Row(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .alertBorder(alerts, fleet, id, 12.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .background(Pal.card, RoundedCornerShape(12.dp))
            .clickable { onOpen(id) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier.weight(1f).fillMaxHeight().drawWithCache {
                val p = fleet.get(id)
                val info = p?.let { RowInfo(it, pens, size.width, size.height) }
                onDrawBehind { info?.draw(this) }
            },
        )
        job?.let { j -> JobButtons(j, { onControl(id, it) }, size = 56.dp, spread = false) }
    }
}

private class RowInfo(private val p: PrinterUi, private val k: Pens, private val w: Float, private val h: Float) {
    private val live = p.online
    private val fg = stateColors(p.state, p.online).second
    private val initial = Line(Brands.label(p.brand).take(1), k.pen(20, Weight.BOLD), Pal.text.toArgb())
    private val nameX = k.px(52)
    private val nameW = k.px(250)
    private val name = Line(p.name, k.pen(20), (if (live) Pal.text else Pal.muted).toArgb(), nameW)
    private val status = Line(
        stateWord(p) + if (live && p.spool in 0 until PrinterUi.LOW_SPOOL) Tr.t(" · spool ${p.spool} %", " · bobine ${p.spool} %") else "",
        k.pen(14), (if (!live || p.spool in 0 until PrinterUi.LOW_SPOOL) Pal.danger else Pal.muted).toArgb(), nameW - k.px(16),
    )
    private val tempsW = k.px(130)
    private val progX = nameX + nameW + k.px(16)
    private val progW = w - progX - tempsW - k.px(16)
    private val pct = Line(if (live) "${p.progress} %" else "--", k.pen(20, Weight.MEDIUM), Pal.text.toArgb())
    private val times = if (live && p.jobActive)
        Line(fmtLeftMin(p.leftMin) + Tr.t(" · end ", " · fin ") + fmtClockMin(p.endMin), k.pen(14), Pal.muted.toArgb()) else null
    private val file = Line(if (p.notice.isNotEmpty()) p.notice else if (live) p.file else "", k.pen(14),
        (if (p.noticeError) Pal.danger else Pal.muted).toArgb(), progW)
    private val noz = p.nozzles.firstOrNull { it.active } ?: p.nozzles[0]
    private val t1 = Line(Tr.t("Nozzle ", "Buse ") + if (live) fmtTemp(noz.temp) else "--", k.pen(16), Pal.text.toArgb(), tempsW)
    private val t2 = Line(Tr.t("Bed ", "Plateau ") + if (live) fmtTemp(p.bed.temp) else "--", k.pen(16), Pal.text.toArgb(), tempsW)

    fun draw(s: DrawScope) = with(s) {
        val cv = drawContext.canvas.nativeCanvas
        // The brand's initial in a disc, where the firmware draws its logo.
        val r = k.px(20)
        drawCircle(Pal.tile, r, Offset(r, h / 2))
        initial.draw(cv, r - initial.w / 2, h / 2 - initial.h / 2)
        name.draw(cv, nameX, k.px(10))
        val led = if (p.connecting) Pal.muted else if (live) fg else Pal.danger
        drawCircle(led, k.px(5), Offset(nameX + k.px(5), k.px(54)))
        status.draw(cv, nameX + k.px(16), k.px(54) - status.h / 2)
        pct.draw(cv, progX, k.px(6))
        times?.drawRight(cv, progX + progW, k.px(6) + pct.h - times.h)
        val barY = k.px(36)
        val barH = k.px(6)
        drawRoundRect(Pal.tile, Offset(progX, barY), Size(progW, barH), CornerRadius(barH / 2))
        if (live && p.progress > 0) drawRoundRect(fg, Offset(progX, barY), Size((progW * p.progress / 100f).coerceAtLeast(barH), barH), CornerRadius(barH / 2))
        file.draw(cv, progX, k.px(46))
        val tx = w - tempsW
        t1.draw(cv, tx, k.px(12))
        t2.draw(cv, tx, k.px(42))
    }
}
