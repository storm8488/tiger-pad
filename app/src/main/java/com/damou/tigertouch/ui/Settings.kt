package com.damou.tigertouch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damou.tigertouch.BuildConfig
import com.damou.tigertouch.drivers.Tr
import com.damou.tigertouch.links.PrinterEntry
import com.damou.tigertouch.model.Brands

/** What the settings page knows of the kiosk. */
data class KioskInfo(val deviceOwner: Boolean, val locked: Boolean, val hasPin: Boolean)

/** Kiosk actions that leave the app: guarded by the PIN when one is set. */
enum class KioskAction { LEAVE, RELEASE }

@Composable
fun SettingsScreen(
    email: String,
    entries: List<PrinterEntry>,
    kiosk: KioskInfo,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSignOut: () -> Unit,
    onSetPin: (String) -> Unit,
    checkPin: (String) -> Boolean,
    onKiosk: (KioskAction) -> Unit,
) {
    var askPinFor by remember { mutableStateOf<KioskAction?>(null) }
    var settingPin by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Pal.bg)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, Tr.t("Back", "Retour"), tint = Pal.text) }
            Text(Tr.t("Settings", "Réglages"), color = Pal.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Section(Tr.t("Printers", "Imprimantes")) }
            items(entries, key = { it.printer.id }) { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.card).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.printer.name, color = Pal.text, fontSize = 18.sp)
                        Text(
                            Brands.label(e.printer.brand) + (if (e.printer.cloud) " · Cloud" else " · ${e.printer.ip}") +
                                if (!e.supported) Tr.t(" · not reachable by this app", " · non joignable par cette appli") else "",
                            color = Pal.muted, fontSize = 14.sp,
                        )
                    }
                    Switch(checked = e.enabled, enabled = e.supported, onCheckedChange = { onToggle(e.printer.id, it) })
                }
            }

            item { Section(Tr.t("Account", "Compte")) }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.card).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(email.ifEmpty { "TigerTag" }, color = Pal.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { confirmSignOut = true }) { Text(Tr.t("Sign out", "Se déconnecter")) }
                }
            }

            item { Section(Tr.t("Dedicated screen", "Écran dédié")) }
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.card).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        when {
                            kiosk.locked -> Tr.t("Locked: the tablet only runs Tiger Touch.", "Verrouillé : la tablette n'exécute que Tiger Touch.")
                            kiosk.deviceOwner -> Tr.t("Device owner, not locked until the next start.", "Propriétaire de l'appareil, déverrouillé jusqu'au prochain démarrage.")
                            else -> Tr.t(
                                "Home screen only. For the full lock, make the app device owner (see README).",
                                "Écran d'accueil seulement. Pour le verrouillage complet, passez l'appli en propriétaire de l'appareil (voir README).",
                            )
                        },
                        color = Pal.text, fontSize = 16.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { settingPin = true }) {
                            Text(if (kiosk.hasPin) Tr.t("Change PIN", "Changer le code") else Tr.t("Set a PIN", "Définir un code"))
                        }
                        if (kiosk.locked) OutlinedButton(onClick = { if (kiosk.hasPin) askPinFor = KioskAction.LEAVE else onKiosk(KioskAction.LEAVE) }) {
                            Text(Tr.t("Leave kiosk mode", "Quitter le mode kiosque"))
                        }
                        if (kiosk.deviceOwner) OutlinedButton(onClick = { if (kiosk.hasPin) askPinFor = KioskAction.RELEASE else onKiosk(KioskAction.RELEASE) }) {
                            Text(Tr.t("Release the tablet", "Libérer la tablette"))
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text("Tiger Touch Android ${BuildConfig.VERSION_NAME}", color = Pal.muted, fontSize = 13.sp)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    askPinFor?.let { action ->
        PinDialog(
            title = Tr.t("Enter the PIN", "Entrez le code"),
            onDismiss = { askPinFor = null },
            onPin = { pin -> if (checkPin(pin)) { askPinFor = null; onKiosk(action); true } else false },
        )
    }
    if (settingPin) {
        PinDialog(
            title = Tr.t("New PIN (4 digits or more)", "Nouveau code (4 chiffres ou plus)"),
            onDismiss = { settingPin = false },
            onPin = { pin -> if (pin.length >= 4) { onSetPin(pin); settingPin = false; true } else false },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(Tr.t("Sign out?", "Se déconnecter ?")) },
            text = { Text(Tr.t("Every printer link will close.", "Toutes les connexions aux imprimantes seront fermées.")) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text(Tr.t("Sign out", "Se déconnecter")) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(Tr.t("Cancel", "Annuler")) } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Pal.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
}

/** Asks a PIN; [onPin] returns false to keep the dialog open (wrong or too short). */
@Composable
private fun PinDialog(title: String, onDismiss: () -> Unit, onPin: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8); wrong = false },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (wrong) {
                    Spacer(Modifier.width(8.dp))
                    Text(Tr.t("Wrong PIN", "Code incorrect"), color = Pal.danger)
                }
            }
        },
        confirmButton = { Button(onClick = { wrong = !onPin(pin); if (wrong) pin = "" }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Tr.t("Cancel", "Annuler")) } },
    )
}
