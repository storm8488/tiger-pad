package com.damou.tigertouch.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.damou.tigertouch.cam.CameraHub
import com.damou.tigertouch.drivers.Tr

/** A feed of [hub] for [url], open while the caller is composed: a card or tile scrolled away holds none. */
@Composable
fun rememberFeed(hub: CameraHub, url: String, widthPx: Int): CameraHub.Feed {
    val feed = remember(hub, url) { hub.open(url, widthPx) }
    DisposableEffect(feed) { onDispose { hub.close(feed) } }
    return feed
}

/**
 * The live picture of a camera, drawn in a layer of its own: a new picture
 * redraws this node alone, recomposes nothing and leaves what is drawn over or
 * under it as it is. The picture is centre-cropped to the node, its corners
 * rounded in [behind] (a clip would be another layer on this GPU).
 */
@Composable
fun CameraLayer(feed: CameraHub.Feed, modifier: Modifier, radius: Dp, behind: Color, label: Boolean) {
    val d = LocalDensity.current
    val pen = remember(d) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13 * d.density * d.fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.White.toArgb()
        }
    }
    val rPx = with(d) { radius.toPx() }
    Spacer(
        modifier
            .graphicsLayer { }
            .drawBehind {
                val r = CornerRadius(rPx)
                val img = feed.frame
                if (img == null) drawRoundRect(Pal.card, cornerRadius = r)
                else {
                    // The whole picture, fitted and centred: the print is what the camera is for, every edge of it.
                    drawRoundRect(Pal.card, cornerRadius = r)
                    val scale = minOf(size.width / img.width, size.height / img.height)
                    val dw = (img.width * scale).toInt()
                    val dh = (img.height * scale).toInt()
                    drawImage(
                        img,
                        srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()),
                        dstSize = IntSize(dw, dh),
                        filterQuality = FilterQuality.Low,
                    )
                    val outer = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        addRoundRect(RoundRect(Rect(Offset.Zero, size), r))
                    }
                    drawPath(outer, behind)
                }
                if (!label) return@drawBehind
                val pad = 10 * density
                val text = when {
                    feed.failed -> Tr.t("Camera not answering", "Caméra muette")
                    img == null -> Tr.t("Camera…", "Caméra…")
                    feed.fps >= 1f -> Tr.t("LIVE · %.0f fps", "DIRECT · %.0f i/s").format(feed.fps)
                    feed.fps > 0f -> Tr.t("LIVE · 1 / %.0f s", "DIRECT · 1 / %.0f s").format(1f / feed.fps)
                    else -> Tr.t("LIVE", "DIRECT")
                }
                val tw = pen.measureText(text)
                val th = pen.textSize
                val bg = if (feed.failed) Pal.danger.copy(alpha = 0.85f) else Color(0x99000000)
                drawRoundRect(bg, Offset(pad, pad), Size(tw + 16 * density, th + 10 * density), CornerRadius(th))
                drawContext.canvas.nativeCanvas.drawText(text, pad + 8 * density, pad + 5 * density + th * 0.85f, pen)
            },
    )
}

/** The thumbnail above a card: 16:9, the pace in a corner, a touch opens the camera full screen. */
@Composable
fun CameraThumb(hub: CameraHub, url: String, widthDp: Int, onOpen: () -> Unit) {
    val d = LocalDensity.current
    val px = with(d) { widthDp.dp.toPx() }.toInt()
    val feed = rememberFeed(hub, url, px)
    // The camera's own proportions once its first picture is in (4:3 or 16:9): no band above or below.
    val ratio = feed.frame?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    CameraLayer(
        feed,
        Modifier.fillMaxWidth().aspectRatio(ratio).clickable(onClick = onOpen),
        radius = 18.dp, behind = Pal.bg, label = true,
    )
}

/**
 * Whether this printer's camera is shown: while it prints or pauses, and for
 * [HOLD_MS] after. A link that blinks (the Wi-Fi, a printer that restarts its
 * socket) reports the printer idle or offline for a few seconds; without this
 * hold the camera would vanish and come back with it.
 */
@Composable
fun rememberCameraShown(fleet: FleetView, id: String): Boolean {
    val working by remember(fleet, id) { derivedStateOf { fleet.get(id)?.jobActive == true } }
    var shown by remember { mutableStateOf(working) }
    LaunchedEffect(working) {
        if (working) shown = true
        else { delay(HOLD_MS); shown = false }
    }
    return shown
}

private const val HOLD_MS = 3 * 60_000L
