package com.justaleks.syncnotes.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiProvider(
    val id: String,
    val label: String,
    val keyHint: String,
    val consoleUrl: String,
) {
    ANTHROPIC(
        id = "anthropic",
        label = "Claude (Anthropic)",
        keyHint = "sk-ant-…",
        consoleUrl = "https://console.anthropic.com/settings/keys",
    ),
    OPENAI(
        id = "openai",
        label = "OpenAI",
        keyHint = "sk-…",
        consoleUrl = "https://platform.openai.com/api-keys",
    );

    companion object {
        fun from(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: ANTHROPIC
    }
}

data class AiSettings(
    /** Off until the user opts in. Nothing is sent anywhere while this is false. */
    val enabled: Boolean = false,
    val provider: AiProvider = AiProvider.ANTHROPIC,
    val apiKey: String = "",
    val model: String = "",
) {
    val isConfigured: Boolean get() = enabled && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * Stored in this app's own preferences, per device — deliberately NOT in Firestore,
 * exactly as on the web.
 *
 * An API key is a billable credential. Syncing it would write it to a server,
 * replicate it to every device, and leave it in each one's offline cache; anyone
 * who reached the account would get a working key rather than just some notes.
 * Keeping it local means the key only ever travels from this phone to the vendor
 * that issued it — which is also why it is excluded from cloud backup in
 * backup_rules.xml.
 *
 * The tradeoff is real and worth stating: you enter the key once per device, so
 * the key on the phone is a separate one from the key in the browser.
 */
object AiSettingsStore {

    private const val PREFS = "sync-notes-ai"

    private val _settings = MutableStateFlow(AiSettings())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = AiSettings(
            enabled = prefs.getBoolean("enabled", false),
            provider = AiProvider.from(prefs.getString("provider", null)),
            // Trimmed on the way in: a key is almost always pasted, and a
            // trailing newline makes an invalid HTTP header, which fails as a
            // bare network error rather than anything that mentions the key.
            apiKey = prefs.getString("apiKey", "").orEmpty().trim(),
            model = prefs.getString("model", "").orEmpty(),
        )
    }

    fun save(context: Context, settings: AiSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", settings.enabled)
            .putString("provider", settings.provider.id)
            .putString("apiKey", settings.apiKey.trim())
            .putString("model", settings.model)
            .apply()
        _settings.value = settings
    }

    /** Forgets the key entirely, for handing the phone on or turning the feature off for good. */
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
        _settings.value = AiSettings()
    }
}
