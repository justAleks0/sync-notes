package com.justaleks.syncnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.justaleks.syncnotes.AuthState
import com.justaleks.syncnotes.SettingsStatus

private const val PASSWORD_PROVIDER = "password"
private const val GOOGLE_PROVIDER = "google.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    account: AuthState.SignedIn,
    status: SettingsStatus,
    needsReauth: Boolean,
    onSaveName: (String) -> Unit,
    onSetPassword: (String, Boolean) -> Unit,
    onConfirmIdentity: (String) -> Unit,
    onCancelReauth: () -> Unit,
    onLinkGoogle: () -> Unit,
    onUnlink: (String) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val hasPassword = account.providers.contains(PASSWORD_PROVIDER)
    val hasGoogle = account.providers.contains(GOOGLE_PROVIDER)
    // Firebase will not let you remove the only way back into an account.
    val canUnlink = account.providers.size > 1

    var name by remember(account.displayName) { mutableStateOf(account.displayName) }
    var password by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = (account.displayName.ifEmpty { account.email })
                                .take(1).uppercase(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                Column {
                    Text(
                        account.displayName.ifEmpty { "No name set" },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        account.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (status.error.isNotEmpty()) {
                Text(status.error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (status.notice.isNotEmpty()) {
                Text(status.notice, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }

            if (needsReauth) {
                SettingsCard("Confirm it's you") {
                    Text(
                        "You signed in a while ago. Re-enter your password to finish that change.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = { currentPassword = it },
                        label = { Text("Current password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onConfirmIdentity(currentPassword); currentPassword = "" },
                            enabled = !status.busy && currentPassword.isNotEmpty(),
                        ) { Text("Confirm") }
                        TextButton(onClick = { currentPassword = ""; onCancelReauth() }) {
                            Text("Cancel")
                        }
                    }
                }
            }

            SettingsCard("Username") {
                Text(
                    "The name shown on your notes. Only you see it for now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onSaveName(name) },
                    enabled = !status.busy && name.trim() != account.displayName.trim(),
                ) { Text("Save") }
            }

            SettingsCard("Sign-in methods") {
                Text(
                    "Connect both and you can sign in either way - same account, same notes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ProviderRow(
                    label = "Email & password",
                    detail = if (hasPassword) account.email else "Not set up",
                    action = if (hasPassword && canUnlink) {
                        { TextButton(onClick = { onUnlink(PASSWORD_PROVIDER) }, enabled = !status.busy) {
                            Text("Disconnect", color = MaterialTheme.colorScheme.error)
                        } }
                    } else null,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (hasPassword) "New password" else "Choose a password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onSetPassword(password, hasPassword); password = "" },
                    enabled = !status.busy && password.length >= 6,
                ) { Text(if (hasPassword) "Change password" else "Add password") }

                ProviderRow(
                    label = "Google",
                    detail = if (hasGoogle) "Connected" else "Not connected",
                    action = when {
                        hasGoogle && canUnlink -> {
                            { TextButton(onClick = { onUnlink(GOOGLE_PROVIDER) }, enabled = !status.busy) {
                                Text("Disconnect", color = MaterialTheme.colorScheme.error)
                            } }
                        }
                        !hasGoogle -> {
                            { TextButton(onClick = onLinkGoogle, enabled = !status.busy) {
                                Text("Connect")
                            } }
                        }
                        else -> null
                    },
                )

                if (!canUnlink) {
                    Text(
                        "Add a second method before removing the first - otherwise you'd " +
                            "lock yourself out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(onClick = onSignOut) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Text("  Sign out")
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun ProviderRow(label: String, detail: String, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.invoke()
    }
}
