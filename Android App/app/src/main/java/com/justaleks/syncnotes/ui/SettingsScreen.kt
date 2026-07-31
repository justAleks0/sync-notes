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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.justaleks.syncnotes.AuthState
import com.justaleks.syncnotes.KeySyncState
import com.justaleks.syncnotes.ModelChoices
import com.justaleks.syncnotes.SettingsStatus
import com.justaleks.syncnotes.ai.AiProvider
import com.justaleks.syncnotes.ai.AiSettings
import com.justaleks.syncnotes.ai.RECOMMENDED
import com.justaleks.syncnotes.ai.findRecommendation
import com.justaleks.syncnotes.ai.monthlyEstimate
import com.justaleks.syncnotes.ai.supportsVision

private const val PASSWORD_PROVIDER = "password"
private const val GOOGLE_PROVIDER = "google.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    account: AuthState.SignedIn,
    status: SettingsStatus,
    needsReauth: Boolean,
    ai: AiSettings,
    aiModels: ModelChoices,
    keySync: KeySyncState,
    onSaveAi: (AiSettings) -> Unit,
    onLoadAiModels: (AiProvider, String) -> Unit,
    onForgetAiKey: () -> Unit,
    onStartKeySync: (String) -> Unit,
    onStopKeySync: () -> Unit,
    onUnlockKey: (String) -> Unit,
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

            AiCard(
                settings = ai,
                choices = aiModels,
                keySync = keySync,
                onChange = onSaveAi,
                onLoadModels = onLoadAiModels,
                onForgetKey = onForgetAiKey,
                onStartKeySync = onStartKeySync,
                onStopKeySync = onStopKeySync,
                onUnlockKey = onUnlockKey,
            )

            OutlinedButton(onClick = onSignOut) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Text("  Sign out")
            }
        }
    }
}

/**
 * Bring-your-own-key AI, off until it is switched on. The wording here matters as
 * much as the controls: it is the user's key, their bill, and their note text
 * leaving the device, so the card says so rather than burying it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiCard(
    settings: AiSettings,
    choices: ModelChoices,
    keySync: KeySyncState,
    onChange: (AiSettings) -> Unit,
    onLoadModels: (AiProvider, String) -> Unit,
    onForgetKey: () -> Unit,
    onStartKeySync: (String) -> Unit,
    onStopKeySync: () -> Unit,
    onUnlockKey: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var key by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    var providerOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var showUnlockDialog by remember { mutableStateOf(false) }

    // A successful unlock is signalled by the key arriving in settings, so that is
    // what dismisses the dialog. Closing it from the button instead would also
    // close it on a wrong passphrase, hiding the error it exists to show.
    LaunchedEffect(settings.apiKey) {
        if (settings.apiKey.isNotBlank()) showUnlockDialog = false
    }

    SettingsCard("AI assistance") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Use your own API key to rewrite, summarise and continue notes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.enabled,
                onCheckedChange = { onChange(settings.copy(enabled = it)) },
            )
        }

        if (!settings.enabled) return@SettingsCard

        Text(
            "The key is stored on this phone only — never in your account — so it is " +
                "separate from the one in the browser. Notes you run an action on are " +
                "sent to the provider you pick.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ExposedDropdownMenuBox(
            expanded = providerOpen,
            onExpandedChange = { providerOpen = it },
        ) {
            OutlinedTextField(
                value = settings.provider.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = providerOpen,
                onDismissRequest = { providerOpen = false },
            ) {
                AiProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.label) },
                        onClick = {
                            providerOpen = false
                            // The model list belongs to the old provider, and so does
                            // the key — clear both rather than send one to the other.
                            onChange(settings.copy(provider = provider, model = ""))
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API key") },
            placeholder = { Text(settings.provider.keyHint) },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { keyVisible = !keyVisible }) {
                    Text(if (keyVisible) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onChange(settings.copy(apiKey = key.trim()))
                    onLoadModels(settings.provider, key.trim())
                },
                enabled = key.isNotBlank() && !choices.loading,
            ) { Text(if (choices.loading) "Checking…" else "Save & load models") }

            TextButton(onClick = { uriHandler.openUri(settings.provider.consoleUrl) }) {
                Text("Get a key")
            }
        }

        if (choices.error.isNotEmpty()) {
            Text(
                choices.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (choices.models.isNotEmpty()) {
            val recommended = RECOMMENDED.getValue(settings.provider)
                .filter { choices.models.contains(it.id) }
            val rest = choices.models.filterNot { id -> recommended.any { it.id == id } }

            ExposedDropdownMenuBox(
                expanded = modelOpen,
                onExpandedChange = { modelOpen = it },
            ) {
                OutlinedTextField(
                    value = settings.model.ifEmpty { "Pick a model" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelOpen) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = modelOpen,
                    onDismissRequest = { modelOpen = false },
                ) {
                    recommended.forEach { recommendation ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(recommendation.label)
                                    Text(
                                        monthlyEstimate(recommendation),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                modelOpen = false
                                onChange(settings.copy(model = recommendation.id))
                            },
                        )
                    }
                    if (rest.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            "All chat models",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        rest.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(id) },
                                onClick = {
                                    modelOpen = false
                                    onChange(settings.copy(model = id))
                                },
                            )
                        }
                    }
                }
            }

            findRecommendation(settings.provider, settings.model)?.let { recommendation ->
                Text(
                    recommendation.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (settings.model.isNotEmpty() &&
                !supportsVision(settings.provider, settings.model)
            ) {
                Text(
                    "This model can't read the images in a note — only the text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sync this key to my other devices, encrypted",
                    style = MaterialTheme.typography.bodyMedium)
                if (keySync.stored) {
                    Text(
                        "An encrypted copy is in your account. Turning this off deletes it; " +
                            "the key stays on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (settings.apiKey.isBlank()) {
                    Text(
                        "Add a key first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = keySync.stored,
                enabled = !keySync.busy && (keySync.stored || settings.apiKey.isNotBlank()),
                onCheckedChange = { on -> if (on) showSyncDialog = true else onStopKeySync() },
            )
        }

        // The device this matters on is the one with an envelope in the account and
        // nothing local to open it with.
        if (keySync.stored && settings.apiKey.isBlank()) {
            TextButton(onClick = { showUnlockDialog = true }) {
                Text("Unlock the synced key here")
            }
        }
        if (keySync.notice.isNotEmpty()) {
            Text(keySync.notice, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        if (keySync.error.isNotEmpty()) {
            Text(keySync.error, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }

        if (settings.apiKey.isNotEmpty()) {
            TextButton(onClick = { key = ""; onForgetKey() }) {
                Text("Forget key on this device", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showSyncDialog) {
        KeySyncDialog(
            busy = keySync.busy,
            error = keySync.error,
            onConfirm = { passphrase -> showSyncDialog = false; onStartKeySync(passphrase) },
            onCancel = { showSyncDialog = false },
        )
    }
    if (showUnlockDialog) {
        KeyUnlockDialog(
            busy = keySync.busy,
            error = keySync.error,
            onUnlock = onUnlockKey,
            onCancel = { showUnlockDialog = false },
        )
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
