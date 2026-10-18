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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.damou.tigertouch.cam.CameraHub
import com.damou.tigertouch.drivers.Tr

/**
 * The live picture of one printer's camera, above its card. A layer of its own,
 * outside the card's: a new picture redraws this node alone, recomposes nothing
 * and leaves the card's texture as it is. The feed is open while the thumbnail
 * is composed, so a card scrolled off the screen costs nothing.
 */
@Composable
fun CameraThumb(hub: CameraHub, url: String, widthDp: Int, onOpen: () -> Unit) {
    val d = LocalDensity.current
    val px = with(d) { widthDp.toFloat() * density }.toInt()
    val feed = remember(hub, url) { hub.open(url, px) }
    DisposableEffect(feed) { onDispose { hub.close(feed) } }
    val label = remember(d) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13 * d.density * d.fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.White.toArgb()
        }
    }
    val radius = with(d) { 18 * density }
    Spacer(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .graphicsLayer { }
            .clickable(onClick = onOpen)
            .drawBehind {
                val r = CornerRadius(radius)
                val img = feed.frame
                drawRoundRect(Pal.card, cornerRadius = r)
                if (img != null) {
                    // Centre-cropped to the frame: cameras are 16:9 or 4:3.
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
                    // Rounded corners drawn over the picture in the page's colour: a clip would be a layer of its own.
                    val outer = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size), r))
                    }
                    drawPath(outer, Pal.bg)
                }
                val cv = drawContext.canvas.nativeCanvas
                val pad = 10 * density
                val text = when {
                    feed.failed -> Tr.t("Camera not answering", "Caméra muette")
                    img == null -> Tr.t("Camera…", "Caméra…")
                    feed.fps >= 1f -> Tr.t("LIVE · %.0f fps", "DIRECT · %.0f i/s").format(feed.fps)
                    feed.fps > 0f -> Tr.t("LIVE · 1 / %.0f s", "DIRECT · 1 / %.0f s").format(1f / feed.fps)
                    else -> Tr.t("LIVE", "DIRECT")
                }
                val tw = label.measureText(text)
                val th = label.textSize
                val bg = if (feed.failed) Pal.danger.copy(alpha = 0.85f) else Color(0x99000000)
                drawRoundRect(bg, Offset(pad, pad), Size(tw + 16 * density, th + 10 * density), CornerRadius(th))
                cv.drawText(text, pad + 8 * density, pad + 5 * density + th * 0.85f, label)
            },
    )
}
