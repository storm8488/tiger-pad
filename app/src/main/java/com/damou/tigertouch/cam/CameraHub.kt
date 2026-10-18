package com.damou.tigertouch.cam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** What the cards show of the cameras (Settings > Display). */
enum class CardCams {
    OFF,
    /** One picture every [CameraHub.PREVIEW_MS], whatever the fleet. */
    PREVIEW,
    /** As fluid as the tablet can afford for the cameras on screen. */
    LIVE,
}

/**
 * The cameras of the cards: one small picture per printer at work, at a pace
 * that adapts to how many are on screen and to what this tablet can decode.
 *
 * A thumbnail asks for **one picture at a time** (the camera's snapshot), at the
 * pace decided here: a continuous MJPEG stream sends every frame the camera
 * makes, 15 cameras of them would fill an old tablet's Wi-Fi. The stream stays
 * for the full-screen view.
 *
 * The pace is a budget, not a constant. The hub keeps a moving average of what
 * decoding one picture costs on this tablet and aims at a share of the
 * processor: total pictures per second = [TARGET_MS] / cost, shared between the
 * thumbnails open (the cards on screen: a card scrolled away is not composed and
 * holds no feed), each between [MIN_FPS] and [MAX_FPS]. Two cameras run near
 * fluid, fifteen at about one picture a second, a faster tablet gets more.
 *
 * Everything pauses while a finger scrolls, while a camera is full screen (the
 * budget is all its own), at night and when the app is not in front; a dimmed
 * screen falls back to the preview pace.
 */
@Stable
class CameraHub(base: OkHttpClient, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "cams"
        /**
         * Decoding time aimed at, per second: half of one core. Measured on the
         * reference tablet with six cameras on screen, the whole app stays near a
         * third of one core and under 10 % of its frames miss their time.
         */
        const val TARGET_MS = 500.0
        const val MIN_FPS = 0.2
        const val MAX_FPS = 8.0
        const val PREVIEW_MS = 3000L
        /** At most this many pictures decoded at once (two cores). */
        private const val DECODERS = 2
        private const val FAIL_RETRY_MS = 2000L
        /** Misses in a row before the thumbnail says the camera does not answer: one lost picture is the Wi-Fi's. */
        private const val MISSES_BEFORE_FAILED = 3
    }

    // A snapshot of 110 KB takes up to 3 s over a weak Wi-Fi: generous timeouts, or every other picture is lost.
    private val http = base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val decoders = Semaphore(DECODERS)

    @Volatile var mode: CardCams = CardCams.LIVE
        set(v) { field = v; refresh() }
    @Volatile var dimmed: Boolean = false
        set(v) { field = v; refresh() }

    private val pauses = HashSet<String>()
    private val running = MutableStateFlow(true)
    private val feeds = LinkedHashSet<Feed>()

    /** Moving average of one decode, in ms: what the budget is made from. */
    @Volatile private var costMs = 25.0

    /** Stops every thumbnail for [reason] ("scroll", "full", "night", "background"), or lets them go again. */
    fun pause(reason: String, on: Boolean) {
        synchronized(pauses) { if (on) pauses.add(reason) else pauses.remove(reason) }
        refresh()
    }

    private fun refresh() {
        val p = synchronized(pauses) { pauses.isNotEmpty() }
        running.value = !p && mode != CardCams.OFF
    }

    /** The pause between two pictures of one camera, now. */
    private fun intervalMs(): Long {
        if (mode == CardCams.PREVIEW || dimmed) return PREVIEW_MS
        val n = synchronized(feeds) { feeds.size }.coerceAtLeast(1)
        val total = TARGET_MS / costMs.coerceAtLeast(1.0)
        val each = (total / n).coerceIn(MIN_FPS, MAX_FPS)
        return (1000.0 / each).toLong()
    }

    /** The pictures of one camera, for one thumbnail. Closed with [close] when the thumbnail goes. */
    fun open(url: String, widthPx: Int): Feed = Feed(url, widthPx.coerceAtLeast(64)).also { f ->
        synchronized(feeds) { feeds.add(f) }
        f.job = scope.launch(Dispatchers.IO) { f.run() }
    }

    fun close(f: Feed) {
        synchronized(feeds) { feeds.remove(f) }
        f.job?.cancel()
    }

    @Stable
    inner class Feed internal constructor(private val url: String, private val widthPx: Int) {
        internal var job: Job? = null

        /** The last picture; null before the first. */
        var frame by mutableStateOf<ImageBitmap?>(null)
            private set
        /** The camera does not answer: the thumbnail says so over its last picture. */
        var failed by mutableStateOf(false)
            private set
        /** Pictures per second over the last few seconds, shown in a corner. */
        var fps by mutableStateOf(0f)
            private set

        private val snapshot: String? = snapshotUrl(url)
        private var sample = 0
        // Three bitmaps in turn: the one shown, the one the screen may still be drawing, the one being written.
        private val ring = arrayOfNulls<Bitmap>(3)
        private var next = 0
        private val times = ArrayDeque<Long>()
        private var misses = 0

        internal suspend fun run() {
            while (currentCoroutineContext().isActive) {
                running.first { it }
                val t0 = SystemClock.elapsedRealtime()
                val bytes = try {
                    grab()
                } catch (e: Exception) {
                    Log.w(TAG, "no picture: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                if (bytes == null) {
                    // The last picture stays on screen; the banner comes only when the camera keeps failing.
                    if (++misses >= MISSES_BEFORE_FAILED) failed = true
                    delay(FAIL_RETRY_MS)
                    continue
                }
                misses = 0
                val bmp = decoders.withPermit { decode(bytes) }
                if (bmp == null) Log.w(TAG, "not decoded (${bytes.size} bytes)")
                if (bmp != null) {
                    frame = bmp.asImageBitmap()
                    failed = false
                    val now = SystemClock.elapsedRealtime()
                    times.addLast(now)
                    while (times.isNotEmpty() && now - times.first() > 5000) times.removeFirst()
                    fps = if (times.size < 2) 0f else (times.size - 1) * 1000f / (now - times.first()).coerceAtLeast(1)
                }
                val wait = intervalMs() - (SystemClock.elapsedRealtime() - t0)
                if (wait > 0) delay(wait)
            }
        }

        /** One JPEG: the snapshot when the camera has one, else the first whole frame of its stream. */
        private suspend fun grab(): ByteArray? {
            if (url.startsWith(DemoCams.SCHEME)) return DemoCams.frame(url)
            val target = snapshot ?: url
            http.newCall(Request.Builder().url(target).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body ?: return null
                return if (snapshot != null) body.bytes() else firstFrame(body.byteStream())
            }
        }

        private fun decode(jpeg: ByteArray): Bitmap? {
            val t0 = SystemClock.elapsedRealtime()
            if (sample == 0) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
                if (o.outWidth <= 0) return null
                var s = 1
                // libjpeg scales while it decodes, and a quarter of the pixels costs about a quarter of the time:
                // the smallest power-of-two scale that still gives three quarters of the thumbnail's width
                // (a slight stretch nobody sees at this size, against a decode up to four times cheaper).
                while (o.outWidth / (s * 2) >= widthPx * 3 / 4) s *= 2
                sample = s
            }
            val reuse = ring[next]
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true
                inBitmap = reuse
            }
            val bmp = try {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            } catch (e: IllegalArgumentException) {
                // The camera changed size: a new bitmap this time.
                opts.inBitmap = null
                runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) }.getOrNull()
            } catch (e: OutOfMemoryError) {
                null
            } ?: return null
            ring[next] = bmp
            next = (next + 1) % ring.size
            val cost = (SystemClock.elapsedRealtime() - t0).toDouble()
            costMs = costMs * 0.9 + cost * 0.1
            return bmp
        }
    }
}

/** mjpg-streamer, ustreamer and OctoPrint answer `?action=snapshot` beside `?action=stream`. */
internal fun snapshotUrl(stream: String): String? =
    if (stream.contains("action=stream")) stream.replace("action=stream", "action=snapshot") else null

/** The first whole JPEG of an MJPEG stream, cut on its markers; the connection is closed after it. */
internal fun firstFrame(input: java.io.InputStream): ByteArray? {
    val out = ByteArrayOutputStream(256 * 1024)
    val buf = ByteArray(16 * 1024)
    var inFrame = false
    var prev = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) return null
        var start = 0
        for (i in 0 until n) {
            val b = buf[i].toInt() and 0xff
            if (!inFrame) {
                if (prev == 0xff && b == 0xd8) { inFrame = true; out.reset(); out.write(0xff); start = i }
            } else if (prev == 0xff && b == 0xd9) {
                out.write(buf, start, i + 1 - start)
                return out.toByteArray()
            }
            prev = b
        }
        if (inFrame) out.write(buf, start, n - start)
        if (out.size() > 8 * 1024 * 1024) return null
    }
}
