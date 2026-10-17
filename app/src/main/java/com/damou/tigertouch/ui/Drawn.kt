package com.damou.tigertouch.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/*
 * Text drawn with the platform's own pens. The home page draws what a printer
 * says in one node per printer: Compose's text (a node and a paragraph per
 * word group) costs the reference tablet's Atom several milliseconds per card
 * at each change, a drawText call almost nothing.
 */

enum class Weight { NORMAL, MEDIUM, BOLD }

/** Pens by size and weight, made once per density. Used on the main thread only. */
class Pens(private val d: Density) {
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val made = HashMap<Int, TextPaint>()

    fun pen(sp: Int, w: Weight = Weight.NORMAL): TextPaint = made.getOrPut(sp * 4 + w.ordinal) {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(d) { sp.sp.toPx() }
            typeface = when (w) { Weight.NORMAL -> Typeface.DEFAULT; Weight.MEDIUM -> medium; Weight.BOLD -> bold }
        }
    }

    fun px(dp: Int): Float = dp * d.density
}

/** How far below the top of its line the baseline of this pen sits. */
fun TextPaint.base(): Float = -fontMetrics.ascent

fun TextPaint.lineH(): Float = fontMetrics.let { it.descent - it.ascent }

/** One line of text, cut to its room with an ellipsis. */
class Line(text: String, val pen: TextPaint, private val color: Int, room: Float = Float.MAX_VALUE) {
    val text: String =
        if (room == Float.MAX_VALUE) text
        else TextUtils.ellipsize(text, pen, room.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
    val w: Float = pen.measureText(this.text)
    val h: Float = pen.lineH()

    fun draw(c: Canvas, x: Float, top: Float) {
        pen.color = color
        c.drawText(text, x, top + pen.base(), pen)
    }

    /** Drawn with its right edge at [right]. */
    fun drawRight(c: Canvas, right: Float, top: Float) = draw(c, right - w, top)
}
