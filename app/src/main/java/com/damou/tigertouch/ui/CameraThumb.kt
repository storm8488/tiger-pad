package com.damou.tigertouch.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
                    val scale = maxOf(size.width / img.width, size.height / img.height)
                    val sw = (size.width / scale).toInt()
                    val sh = (size.height / scale).toInt()
                    drawImage(
                        img,
                        srcOffset = IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
                        srcSize = IntSize(sw, sh),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
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
    CameraLayer(
        feed,
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable(onClick = onOpen),
        radius = 18.dp, behind = Pal.bg, label = true,
    )
}
