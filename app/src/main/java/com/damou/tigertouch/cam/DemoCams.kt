package com.damou.tigertouch.cam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * The cameras of the demo printers: real JPEGs, 1280 × 720 at about the weight
 * of a printer camera's, so that what costs (decoding them) is measured for
 * real with 15 cameras. Four pictures per printer, made once, the nozzle moving
 * from one to the next; served with a little latency, like a camera on the LAN.
 */
object DemoCams {
    const val SCHEME = "demo://"
    private const val W = 1280
    private const val H = 720
    private const val VARIANTS = 4
    private const val LATENCY_MS = 40L

    private val made = ConcurrentHashMap<String, List<ByteArray>>()
    private val turn = ConcurrentHashMap<String, Int>()

    fun url(printerId: String) = SCHEME + printerId

    suspend fun frame(url: String): ByteArray {
        delay(LATENCY_MS)
        val pics = made.getOrPut(url) { make(url) }
        val i = turn.merge(url, 1) { a, b -> (a + b) % VARIANTS } ?: 0
        return pics[i]
    }

    private fun make(url: String): List<ByteArray> {
        val seed = url.hashCode()
        val rnd = Random(seed.toLong())
        val hue = floatArrayOf((seed and 0xff) * 360f / 256f, 0.5f, 0.35f)
        val bed = Color.HSVToColor(hue)
        val base = IntArray(W * H)
        // A plate in perspective-ish shading, with grain: a grain-free picture would compress, and decode, far too easily.
        for (y in 0 until H) {
            val shade = 0.6f + 0.4f * y / H
            for (x in 0 until W) {
                val n = rnd.nextInt(40) - 20
                val r = ((Color.red(bed) * shade).toInt() + n).coerceIn(0, 255)
                val g = ((Color.green(bed) * shade).toInt() + n).coerceIn(0, 255)
                val b = ((Color.blue(bed) * shade).toInt() + n).coerceIn(0, 255)
                base[y * W + x] = Color.rgb(r, g, b)
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        return (0 until VARIANTS).map { v ->
            val bmp = Bitmap.createBitmap(base, W, H, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(bmp)
            // The part being printed, and the head moving over it.
            paint.color = Color.rgb(230, 90, 40)
            c.drawRect(440f, 360f, 840f, 600f, paint)
            paint.color = Color.rgb(40, 40, 40)
            val hx = 440f + v * 120f
            c.drawRect(hx, 250f, hx + 110f, 340f, paint)
            paint.color = Color.WHITE
            paint.textSize = 40f
            c.drawText("DEMO CAM", 40f, 70f, paint)
            val out = ByteArrayOutputStream(160 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            out.toByteArray()
        }
    }
}
