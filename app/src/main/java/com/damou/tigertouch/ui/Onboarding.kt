package com.damou.tigertouch.ui

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damou.tigertouch.Phase
import com.damou.tigertouch.drivers.Tr
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun StartingScreen() {
    Box(Modifier.fillMaxSize().background(Pal.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tiger Pad", color = Pal.accent, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = Pal.accent)
        }
    }
}

/** The TigerTag account: e-mail and password, or the QR code for an account created with Google. */
@Composable
fun SignInScreen(phase: Phase.SignIn, onSignIn: (String, String) -> Unit, onPair: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // never saved
    // Scrolls: the on-screen keyboard covers the lower half of a landscape tablet.
    Box(
        Modifier.fillMaxSize().background(Pal.bg).imePadding().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Tiger Pad", color = Pal.accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(Tr.t("Sign in to your TigerTag account", "Connectez-vous à votre compte TigerTag"),
                color = Pal.text, fontSize = 20.sp)
            OutlinedTextField(
                value = email, onValueChange = { email = it }, singleLine = true,
                label = { Text(Tr.t("E-mail", "E-mail")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(Tr.t("Password", "Mot de passe")) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (phase.error.isNotEmpty()) Text(phase.error, color = Pal.danger, fontSize = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onSignIn(email, password); password = "" },
                    enabled = !phase.busy && email.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.height(56.dp),
                ) { Text(Tr.t("Sign in", "Se connecter"), fontSize = 18.sp) }
                Spacer(Modifier.width(16.dp))
                if (phase.busy) CircularProgressIndicator(color = Pal.accent, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(Tr.t("Account created with Google?", "Compte créé avec Google ?"), color = Pal.muted, fontSize = 16.sp)
            OutlinedButton(onClick = onPair, modifier = Modifier.height(56.dp)) {
                Text(Tr.t("Sign in with a QR code", "Se connecter avec un QR code"), fontSize = 18.sp)
            }
            WifiButton()
        }
    }
}

/** The pairing page: a QR the owner scans and approves on the phone, and its code. */
@Composable
fun PairingScreen(phase: Phase.Pairing, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Pal.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val pair = phase.pair
            when {
                pair != null -> {
                    Text(Tr.t("Scan with your phone and approve", "Scannez avec votre téléphone et validez"),
                        color = Pal.text, fontSize = 22.sp)
                    val qr = remember(pair.verifyUrl) { qrBitmap(pair.verifyUrl, 640) }
                    Image(qr.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.size(320.dp).clip(RoundedCornerShape(16.dp)))
                    Text(pair.code, color = Pal.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
                phase.error.isNotEmpty() -> {
                    Text(phase.error, color = Pal.danger, fontSize = 20.sp)
                    Button(onClick = onRetry) { Text(Tr.t("Try again", "Réessayer"), fontSize = 18.sp) }
                }
                else -> CircularProgressIndicator(color = Pal.accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack) { Text(Tr.t("Back", "Retour"), fontSize = 18.sp) }
                WifiButton()
            }
        }
    }
}

/** Android's Wi-Fi page: the tablet has no other way in while the app is its home screen. */
@Composable
fun WifiButton() {
    val ctx = LocalContext.current
    TextButton(onClick = {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }) { Text(Tr.t("Wi-Fi settings", "Réglages Wi-Fi"), fontSize = 16.sp) }
}

private fun qrBitmap(text: String, px: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(m.width * m.height) { i ->
        if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
}
