package com.damou.tigertouch.cam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** What the camera view shows: a frame, or why there is none. */
sealed interface CamFrame {
    data object Connecting : CamFrame
    data class Image(val bitmap: Bitmap) : CamFrame
    data class Failed(val reason: String) : CamFrame
}

/*
 * An MJPEG stream (multipart/x-mixed-replace, as mjpg-streamer, ustreamer and
 * OctoPrint serve it) read as a flow of frames.
 *
 * Frames are cut on the JPEG markers (FF D8 ... FF D9) rather than on the
 * multipart boundaries, which every server writes a little differently. Frames
 * above [maxFps] are read and dropped undecoded: decoding is what costs on a
 * 2013 Atom, reading is cheap. Each frame is decoded at the smallest power-of-two
 * scale that still covers [targetWidth], in RGB_565, to keep memory down on a
 * 1 GB tablet. The stream reconnects on its own until the collector stops.
 */
fun mjpegFrames(
    url: String,
    base: OkHttpClient,
    maxFps: Int = 12,
    targetWidth: Int = 1280,
): Flow<CamFrame> = flow {
    val http = base.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS) // a stream that stops sending for 10 s is dead
        .build()
    val minGapMs = 1000L / maxFps.coerceAtLeast(1)
    var sample = 0 // decided on the first frame

    while (true) {
        emit(CamFrame.Connecting)
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                val input = r.body?.byteStream() ?: throw IllegalStateException("empty")
                val frame = ByteArrayOutputStream(256 * 1024)
                val buf = ByteArray(32 * 1024)
                var inFrame = false
                var prev = 0
                var lastEmit = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    var start = 0 // where the bytes of this chunk that belong to a frame begin
                    for (i in 0 until n) {
                        val b = buf[i].toInt() and 0xff
                        if (!inFrame) {
                            if (prev == 0xff && b == 0xd8) {
                                inFrame = true
                                frame.reset()
                                frame.write(0xff)
                                start = i // 0xd8 onwards is copied below
                            }
                        } else if (prev == 0xff && b == 0xd9) {
                            frame.write(buf, start, i + 1 - start)
                            inFrame = false
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= minGapMs) {
                                lastEmit = now
                                val bytes = frame.toByteArray()
                                if (sample == 0) sample = sampleFor(bytes, targetWidth)
                                decode(bytes, sample)?.let { emit(CamFrame.Image(it)) }
                            }
                        }
                        prev = b
                    }
                    if (inFrame) frame.write(buf, start, n - start)
                    // A frame that never ends is not a JPEG stream: start over rather than grow forever.
                    if (frame.size() > 8 * 1024 * 1024) { inFrame = false; frame.reset() }
                }
            }
            emit(CamFrame.Failed(com.damou.tigertouch.drivers.Tr.t("The stream ended.", "Le flux s'est arrêté.")))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            emit(CamFrame.Failed(com.damou.tigertouch.drivers.Tr.t(
                "The camera does not answer.", "La caméra ne répond pas.")))
        }
        delay(2000)
    }
}.flowOn(Dispatchers.IO)

private fun sampleFor(jpeg: ByteArray, targetWidth: Int): Int {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
    var s = 1
    while (o.outWidth / (s * 2) >= targetWidth) s *= 2
    return s
}

private fun decode(jpeg: ByteArray, sample: Int): Bitmap? = try {
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    })
} catch (e: OutOfMemoryError) {
    null
}
