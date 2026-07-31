package com.justaleks.syncnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Shown before key sync is ever switched on, never after.
 *
 * It says what the feature does, what the user has to do, and what can go wrong,
 * in that order and in plain words. A feature that moves a billable credential
 * into a database should not be a switch someone flips without reading anything —
 * and the honest downsides are the reason to show this, not the polite framing of
 * the upside.
 */
@Composable
fun KeySyncDialog(
    busy: Boolean,
    error: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }

    val tooShort = passphrase.isNotEmpty() && passphrase.length < 10
    val mismatch = again.isNotEmpty() && again != passphrase
    val ready = passphrase.length >= 10 && again == passphrase && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Sync your API key to your other devices?") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Section("What this does") {
                    Text(
                        "Your key is encrypted on this device with a passphrase you choose, " +
                            "and only the encrypted result is stored in your account. Any " +
                            "device you sign in on can unlock it with that passphrase, " +
                            "instead of you typing the key in again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Section("What you'll need to do") {
                    Bullets(
                        "Choose a passphrase now. It is not your account password, and it " +
                            "never leaves this device.",
                        "Enter it once on each other device, to unlock the key there.",
                        "Sync Notes, Firebase and Google only ever hold the encrypted blob — " +
                            "none of them can read your key.",
                    )
                }

                Section("The risks, plainly") {
                    Bullets(
                        "Forget the passphrase and the synced key is gone. Nobody can reset " +
                            "it — not us, not Google. You'd enter the key again and start over.",
                        "It widens what a stolen account is worth. Today someone who got into " +
                            "your account gets your notes. With this on, someone who gets into " +
                            "your account and knows your passphrase gets a working, billable key.",
                        "A weak passphrase makes this weak. The encryption is only as good as " +
                            "what you pick — a guessable phrase can be attacked offline by " +
                            "anyone holding the encrypted copy.",
                        "It does not change what happens on this device: the key still sits " +
                            "unencrypted in this app's storage, exactly as it does now.",
                    )
                }

                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    placeholder = { Text("At least 10 characters") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = again,
                    onValueChange = { again = it },
                    label = { Text("Passphrase again") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (tooShort) {
                    Text(
                        "Ten characters or more, please — this is the whole lock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (mismatch) {
                    Text(
                        "Those two don't match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (error.isNotEmpty()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = ready) {
                Text(if (busy) "Encrypting…" else "Turn it on")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy) { Text("Keep it off") }
        },
    )
}

/** Asks for the passphrase on a device that has an encrypted key but no plain one. */
@Composable
fun KeyUnlockDialog(
    busy: Boolean,
    error: String,
    onUnlock: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Unlock your synced API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This account has an encrypted key stored. Enter the passphrase you chose " +
                        "when you turned syncing on, and it will be decrypted here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotEmpty()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onUnlock(passphrase) },
                enabled = passphrase.isNotEmpty() && !busy,
            ) { Text(if (busy) "Unlocking…" else "Unlock") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy) { Text("Not now") }
        },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun Bullets(vararg lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (line in lines) {
            Text("•  $line", style = MaterialTheme.typography.bodySmall)
        }
    }
}
