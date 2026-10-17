package com.damou.tigertouch.ui

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import com.damou.tigertouch.cam.CamFrame
import com.damou.tigertouch.cam.mjpegFrames
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.net.Net

/**
 * A printer's camera, full screen. The stream is open only while this is shown:
 * closing it closes the connection, so a camera costs nothing the rest of the time.
 * A touch anywhere closes it.
 */
@Composable
fun CameraDialog(title: String, url: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A dialog has its own window: full screen there too, or the navigation bar comes back.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.decorView?.let { d ->
                @Suppress("DEPRECATION")
                d.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
            }
        }
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        var status by remember { mutableStateOf<CamFrame>(CamFrame.Connecting) }
        LaunchedEffect(url) {
            mjpegFrames(url, Net.lanHttp).collect { f ->
                status = f
                if (f is CamFrame.Image) image = f.bitmap.asImageBitmap()
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose)) {
            image?.let {
                Image(it, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Pal.tile).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, Tr.t("Close", "Fermer"), tint = Pal.text) }
            }
            // Shown over the last frame while (re)connecting, or alone before the first one.
            val s = status
            if (s !is CamFrame.Image) {
                Column(
                    Modifier.align(Alignment.Center).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .background(Color(0xCC000000)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (s is CamFrame.Failed) {
                        Text(s.reason, color = Pal.danger, fontSize = 18.sp)
                        Text(Tr.t("Trying again…", "Nouvel essai…"), color = Pal.muted, fontSize = 15.sp)
                    } else {
                        CircularProgressIndicator(color = Pal.accent)
                        Text(Tr.t("Connecting to the camera…", "Connexion à la caméra…"), color = Pal.text, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
