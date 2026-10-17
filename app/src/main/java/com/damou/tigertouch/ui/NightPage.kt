package com.damou.tigertouch.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.events.EventKind
import com.damou.tigertouch.events.FleetEvent
import com.damou.tigertouch.model.PvState
import kotlinx.coroutines.delay

/** One line of the night page: only what it shows, so a temperature that moves redraws nothing. */
@Immutable
private data class NightRow(
    val name: String,
    /** The newest event nobody acknowledged, or null. */
    val alert: EventKind?,
    /** Printing or paused, and answering. */
    val active: Boolean,
    val paused: Boolean,
    val progress: Int,
    val endMin: Long,
)

/** Alerts first, the worst first; then the prints at work, the next to end first. */
private val ALERT_ORDER = listOf(EventKind.ERROR, EventKind.OFFLINE, EventKind.PAUSED, EventKind.DONE, EventKind.LOW_SPOOL)

private fun nightRows(fleet: FleetView, events: List<FleetEvent>): List<NightRow> {
    val alert = HashMap<String, EventKind>()
    // Newest first: the first unacknowledged event of a printer is its latest.
    for (e in events) if (!e.acked && e.printerId !in alert) alert[e.printerId] = e.kind
    val rows = ArrayList<NightRow>()
    for (id in fleet.ids) {
        val p = fleet.get(id) ?: continue
        val a = alert[id]
        val active = p.online && p.jobActive
        if (a == null && !active) continue
        rows += NightRow(p.name, a, active, p.state == PvState.PAUSED, p.progress, if (active) p.endMin else 0)
    }
    return rows.sortedWith(
        compareBy<NightRow>({ it.alert?.let(ALERT_ORDER::indexOf) ?: ALERT_ORDER.size },
            { if (it.endMin > 0) it.endMin else Long.MAX_VALUE }, { it.name }),
    )
}

/** What the night page draws in grey: readable at a low backlight, never white. */
private val NIGHT_TEXT = Color(0xFFB4B8BF)
private val NIGHT_MUTED = Color(0xFF6E737C)

/**
 * The night page: black, the time, the prints at work with their end, and the
 * alerts. One node, drawn with the platform's text: it redraws when one of its
 * lines changes or the minute turns, and nothing moves in between. Shifted by
 * a few pixels now and then by [night]. No touch handler: the touch that wakes
 * the screen is taken before it gets here.
 */
@Composable
fun NightPage(fleet: FleetView, events: State<List<FleetEvent>>, night: Night, demo: Boolean) {
    val rows by remember { derivedStateOf { nightRows(fleet, events.value) } }
    val minute = remember { mutableStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
            minute.value = System.currentTimeMillis() / 60_000L
        }
    }
    val d = LocalDensity.current
    val pens = remember(d) { Pens(d) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = night.shiftX.toFloat(); translationY = night.shiftY.toFloat() }
                .drawWithCache {
                    val t = NightText(rows, minute.value, pens, demo, size.width, size.height)
                    onDrawBehind { t.draw(drawContext.canvas.nativeCanvas) }
                },
        )
    }
}

/** The night page laid out for its size. */
private class NightText(rows: List<NightRow>, minute: Long, k: Pens, demo: Boolean, w: Float, h: Float) {
    private val lines = ArrayList<Pair<Line, PointF>>()
    private val dots = ArrayList<Pair<Int, PointF>>()
    private val dotR = k.px(7)
    private val dotPen = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val pad = k.px(40)
        val clockTop = k.px(24)
        val clock = Line(fmtClock(minute * 60_000L), k.pen(72, Weight.MEDIUM), NIGHT_TEXT.toArgb())
        lines += clock to PointF(pad, clockTop)
        val n = rows.count { it.active }
        val head = Line(
            if (n == 0) Tr.t("Nothing printing", "Rien en cours") else Tr.t("$n printing", "$n en cours"),
            k.pen(24), NIGHT_MUTED.toArgb(),
        )
        // On the clock's baseline.
        lines += head to PointF(pad + clock.w + k.px(28), clockTop + clock.pen.base() - head.pen.base())
        if (demo) {
            val b = Line(Tr.t("Demo", "Démo"), k.pen(18, Weight.BOLD), 0xFF8A6A2A.toInt())
            lines += b to PointF(w - pad - b.w, clockTop + k.px(4))
        }
        val top = clockTop + clock.h + k.px(20)
        // One column while the lines fit at 38 px; then two, the lines shared evenly.
        val avail = h - top - k.px(16)
        val fit = (avail / k.px(38)).toInt().coerceAtLeast(1)
        val cols = if (rows.size <= fit) 1 else 2
        val perCol = minOf(fit, (rows.size + cols - 1) / cols).coerceAtLeast(1)
        val rowH = minOf(k.px(46), avail / perCol)
        val colW = (w - 2 * pad - (cols - 1) * pad) / cols
        // What does not fit is counted on the last line.
        val shown = if (rows.size > perCol * cols) perCol * cols - 1 else rows.size
        val namePen = k.pen(28, Weight.MEDIUM)
        val valuePen = k.pen(28)
        val indent = dotR * 2 + k.px(12)
        for (i in 0 until shown) {
            val r = rows[i]
            val x = pad + (i / perCol) * (colW + pad)
            val y = top + (i % perCol) * rowH
            val color = r.alert?.let { kindColor(it).toArgb() }
            // A spool running low on a print at work: the print's end, in the alert's colour.
            val printLine = r.active && (r.alert == null || r.alert == EventKind.LOW_SPOOL)
            val tail = if (printLine) Line(Tr.t("End ", "Fin ") + fmtClockMin(r.endMin), valuePen, color ?: NIGHT_TEXT.toArgb())
            else Line(r.alert!!.label(), valuePen, color!!)
            val pct = if (printLine) Line((if (r.paused) "pause · " else "") + "${r.progress} %", valuePen, NIGHT_MUTED.toArgb()) else null
            val tailX = x + colW - tail.w
            val pctX = tailX - k.px(28) - (pct?.w ?: 0f)
            val nameX = x + indent
            lines += Line(r.name, namePen, NIGHT_TEXT.toArgb(), pctX - k.px(16) - nameX) to PointF(nameX, y)
            if (pct != null) lines += pct to PointF(pctX, y)
            lines += tail to PointF(tailX, y)
            if (color != null) dots += color to PointF(x + dotR, y + namePen.lineH() / 2)
        }
        if (shown < rows.size) {
            val more = Line(Tr.t("+ ${rows.size - shown} more", "+ ${rows.size - shown} autres"), valuePen, NIGHT_MUTED.toArgb())
            lines += more to PointF(pad + (shown / perCol) * (colW + pad) + indent, top + (shown % perCol) * rowH)
        }
    }

    fun draw(c: Canvas) {
        for ((l, at) in lines) l.draw(c, at.x, at.y)
        for ((color, at) in dots) {
            dotPen.color = color
            c.drawCircle(at.x, at.y, dotR, dotPen)
        }
    }
}
