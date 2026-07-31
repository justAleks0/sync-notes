package com.justaleks.syncnotes

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.justaleks.syncnotes.ai.AiClient
import com.justaleks.syncnotes.ai.AiProvider
import com.justaleks.syncnotes.ai.AiSettings
import com.justaleks.syncnotes.ai.AiSettingsStore
import com.justaleks.syncnotes.ai.EDIT_SYSTEM_PROMPT
import com.justaleks.syncnotes.ai.NoteImage
import com.justaleks.syncnotes.ai.SYSTEM_PROMPT
import com.justaleks.syncnotes.ai.SuggestedEdit
import com.justaleks.syncnotes.ai.aiErrorMessage
import com.justaleks.syncnotes.ai.defaultModel
import com.justaleks.syncnotes.ai.parseEdits
import com.justaleks.syncnotes.data.ImageUploader
import com.justaleks.syncnotes.data.Note
import com.justaleks.syncnotes.data.NotesRepository
import com.justaleks.syncnotes.data.Revision
import com.justaleks.syncnotes.data.RevisionsRepository
import com.justaleks.syncnotes.data.uploadErrorMessage
import com.justaleks.syncnotes.data.UpdateChecker
import com.justaleks.syncnotes.data.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(
        val uid: String,
        val email: String,
        val displayName: String,
        val providers: List<String>,
    ) : AuthState
}

/** Outcome of a settings change, surfaced as a message under the field. */
data class SettingsStatus(val error: String = "", val notice: String = "", val busy: Boolean = false)

/** What the model picker in Settings currently knows about the entered key. */
data class ModelChoices(
    val models: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
)

/** One run of the assistant, streamed into [output] as it arrives. */
data class AssistState(
    val running: Boolean = false,
    val output: String = "",
    val error: String = "",
    /** Which action button is lit, or null for a custom instruction. */
    val actionId: String? = null,
    /**
     * Set only for the "Suggest edits" action, which comes back as a list to tick
     * off rather than as prose. Null means "not that kind of result".
     */
    val edits: List<SuggestedEdit>? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(app: Application) : AndroidViewModel(app) {

    // Profile edits mutate the FirebaseUser in place instead of emitting a new auth
    // state, so bumping this is what makes the UI notice them.
    private val profileVersion = MutableStateFlow(0)

    val authState: StateFlow<AuthState> =
        combine(NotesRepository.authState(), profileVersion) { user, _ ->
            if (user == null) {
                AuthState.SignedOut
            } else {
                AuthState.SignedIn(
                    uid = user.uid,
                    email = user.email.orEmpty(),
                    displayName = user.displayName.orEmpty(),
                    providers = NotesRepository.providerIds(),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)

    val notes: StateFlow<List<Note>?> = NotesRepository.authState()
        .map { it?.uid }
        // Without this, every profile edit would tear down and re-create the Firestore
        // listener, making the note list flicker.
        .distinctUntilChanged()
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else NotesRepository.notes(uid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _authError = MutableStateFlow("")
    val authError: StateFlow<String> = _authError.asStateFlow()

    private val _authBusy = MutableStateFlow(false)
    val authBusy: StateFlow<Boolean> = _authBusy.asStateFlow()

    private val _settings = MutableStateFlow(SettingsStatus())
    val settings: StateFlow<SettingsStatus> = _settings.asStateFlow()

    /** Set when Firebase wants the password re-entered before a sensitive change. */
    private val _needsReauth = MutableStateFlow(false)
    val needsReauth: StateFlow<Boolean> = _needsReauth.asStateFlow()
    private var pendingChange: (suspend () -> Unit)? = null

    private val _imageUploading = MutableStateFlow(false)
    val imageUploading: StateFlow<Boolean> = _imageUploading.asStateFlow()

    private val _imageError = MutableStateFlow("")
    val imageError: StateFlow<String> = _imageError.asStateFlow()

    /**
     * Uploads a picked image and hands back its markdown so the editor can splice it
     * in at the caret.
     */
    fun uploadImage(uri: Uri, noteId: String, onInsert: (String) -> Unit) {
        val uid = uid ?: return
        viewModelScope.launch {
            _imageUploading.value = true
            _imageError.value = ""
            try {
                val context = getApplication<Application>()
                val url = ImageUploader.upload(context, uid, noteId, uri)
                onInsert(ImageUploader.markdownFor(context, uri, url))
            } catch (e: IllegalArgumentException) {
                _imageError.value = e.message ?: "That image can't be used."
            } catch (e: Exception) {
                _imageError.value = uploadErrorMessage(e)
            } finally {
                _imageUploading.value = false
            }
        }
    }

    fun clearImageError() {
        _imageError.value = ""
    }

    // ---------- AI assistance ----------

    val aiSettings: StateFlow<AiSettings> = AiSettingsStore.settings

    private val _aiModels = MutableStateFlow(ModelChoices())
    val aiModels: StateFlow<ModelChoices> = _aiModels.asStateFlow()

    private val _assist = MutableStateFlow(AssistState())
    val assist: StateFlow<AssistState> = _assist.asStateFlow()
    private var assistJob: Job? = null

    fun saveAiSettings(settings: AiSettings) =
        AiSettingsStore.save(getApplication(), settings)

    fun clearAiSettings() {
        AiSettingsStore.clear(getApplication())
        _aiModels.value = ModelChoices()
    }

    /** Asks the provider which models this key can actually use. */
    fun loadAiModels(provider: AiProvider, apiKey: String) {
        if (apiKey.isBlank()) return
        viewModelScope.launch {
            _aiModels.value = ModelChoices(loading = true)
            try {
                val models = AiClient.listModels(provider, apiKey)
                _aiModels.value = ModelChoices(models = models)
                // Nothing chosen yet, or a model that belongs to the other provider.
                val current = aiSettings.value
                if (current.model.isBlank() || current.model !in models) {
                    saveAiSettings(current.copy(model = defaultModel(provider, models)))
                }
            } catch (e: Exception) {
                _aiModels.value = ModelChoices(error = aiErrorMessage(e))
            }
        }
    }

    fun clearAiModels() {
        _aiModels.value = ModelChoices()
    }

    /**
     * Runs one assist action. Output streams into [assist]; nothing is ever written
     * to the note until the user picks where it goes.
     */
    fun runAssist(
        prompt: String,
        actionId: String?,
        images: List<NoteImage>,
        wantsEdits: Boolean = false,
    ) {
        val settings = aiSettings.value
        if (!settings.isConfigured) return

        assistJob?.cancel()
        assistJob = viewModelScope.launch {
            _assist.value = AssistState(running = true, actionId = actionId)
            try {
                val system = if (wantsEdits) EDIT_SYSTEM_PROMPT else SYSTEM_PROMPT
                AiClient.stream(settings, system, prompt, images) { chunk ->
                    _assist.update { it.copy(output = it.output + chunk) }
                }
                _assist.update { current ->
                    if (!wantsEdits) return@update current.copy(running = false)

                    val parsed = parseEdits(current.output)
                    if (parsed == null) {
                        // Fall back to showing the raw reply rather than claiming
                        // failure — the text is usually still readable and useful.
                        current.copy(
                            running = false,
                            error = "Couldn't read that as a list of edits. The raw reply is below.",
                        )
                    } else {
                        current.copy(running = false, edits = parsed)
                    }
                }
            } catch (e: CancellationException) {
                // Stop keeps whatever streamed so far — it is often enough to use.
                _assist.update { it.copy(running = false) }
                throw e
            } catch (e: Exception) {
                _assist.update { it.copy(running = false, error = aiErrorMessage(e)) }
            }
        }
    }

    fun stopAssist() {
        assistJob?.cancel()
        assistJob = null
    }

    /** Called when the assist sheet closes, so the next note starts clean. */
    fun clearAssist() {
        stopAssist()
        _assist.value = AssistState()
    }

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    /** Null until a download starts, then 0..100. */
    private val _updateProgress = MutableStateFlow<Int?>(null)
    val updateProgress: StateFlow<Int?> = _updateProgress.asStateFlow()

    init {
        // The key lives in this device's own preferences, so it has to be read off
        // disk before anything can ask whether the assistant is configured.
        AiSettingsStore.load(getApplication())

        // "Am I the newest version?" — asked once per launch. Silent if the answer is yes.
        viewModelScope.launch {
            _update.value = UpdateChecker.checkForUpdate(getApplication())
        }
    }

    fun downloadAndInstallUpdate() {
        val info = _update.value ?: return
        viewModelScope.launch {
            _updateProgress.value = 0
            try {
                val apk = UpdateChecker.downloadApk(getApplication(), info) { percent ->
                    _updateProgress.value = percent
                }
                UpdateChecker.installApk(getApplication(), apk)
            } catch (e: Exception) {
                _settings.value = SettingsStatus(error = "Update failed: ${e.message}")
            } finally {
                _updateProgress.value = null
            }
        }
    }

    private val uid: String? get() = (authState.value as? AuthState.SignedIn)?.uid

    fun signIn(email: String, password: String) = authCall {
        NotesRepository.signIn(email.trim(), password)
    }

    fun register(email: String, password: String) = authCall {
        NotesRepository.register(email.trim(), password)
    }

    /** [context] must be an Activity — Credential Manager shows the account picker. */
    fun signInWithGoogle(context: Context) = authCall {
        NotesRepository.signInWithGoogle(context)
    }

    fun linkGoogle(context: Context) = settingsCall("Google connected.") {
        NotesRepository.linkGoogle(context)
    }

    fun unlinkProvider(providerId: String) = settingsCall("Sign-in method removed.") {
        NotesRepository.unlinkProvider(providerId)
    }

    fun signOut() = NotesRepository.signOut()

    fun clearAuthError() {
        _authError.value = ""
    }

    fun clearSettingsStatus() {
        _settings.value = SettingsStatus()
    }

    fun saveDisplayName(name: String) = settingsCall("Username saved.") {
        NotesRepository.updateDisplayName(name)
    }

    fun setPassword(password: String, hasPassword: Boolean) =
        settingsCall(if (hasPassword) "Password changed." else "Password sign-in added.") {
            NotesRepository.setOrChangePassword(password)
        }

    /** Re-enter the current password, then replay whatever change needed it. */
    fun confirmIdentity(password: String) {
        viewModelScope.launch {
            _settings.value = SettingsStatus(busy = true)
            try {
                NotesRepository.reauthenticate(password)
                _needsReauth.value = false
                pendingChange?.invoke()
                pendingChange = null
                profileVersion.value++
                _settings.value = SettingsStatus(notice = "Done.")
            } catch (e: Exception) {
                _settings.value = SettingsStatus(error = authErrorMessage(e))
            }
        }
    }

    fun cancelReauth() {
        _needsReauth.value = false
        pendingChange = null
        _settings.value = SettingsStatus()
    }

    private fun settingsCall(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _settings.value = SettingsStatus(busy = true)
            try {
                block()
                profileVersion.value++
                _settings.value = SettingsStatus(notice = successMessage)
            } catch (e: FirebaseAuthRecentLoginRequiredException) {
                pendingChange = block
                _needsReauth.value = true
                _settings.value = SettingsStatus(error = authErrorMessage(e))
            } catch (e: Exception) {
                _settings.value = SettingsStatus(error = authErrorMessage(e))
            }
        }
    }

    /** Creates an empty note and hands its id back so the caller can open it. */
    fun createNote(onCreated: (String) -> Unit) {
        val uid = uid ?: return
        viewModelScope.launch {
            runCatching { NotesRepository.createNote(uid) }.onSuccess(onCreated)
        }
    }

    fun saveNote(id: String, title: String, body: String) {
        val uid = uid ?: return
        NotesRepository.saveNote(uid, id, title, body)

        // Offered on every save; it decides for itself whether this moment is worth
        // keeping. History is a convenience — a failure here must never be allowed
        // to look like the note itself failed to save.
        viewModelScope.launch {
            runCatching { RevisionsRepository.record(uid, id, title, body) }
        }
    }

    fun deleteNote(id: String) {
        val uid = uid ?: return
        viewModelScope.launch {
            // History first: once the note document is gone there is no screen left
            // that could reach its revisions to clean them up.
            runCatching { RevisionsRepository.deleteAll(uid, id) }
            NotesRepository.deleteNote(uid, id)
        }
    }

    // ---------- Version history ----------

    /** Which note's history is on screen, or null when the panel is closed. */
    private val _historyFor = MutableStateFlow<String?>(null)

    val revisions: StateFlow<List<Revision>?> = _historyFor
        .flatMapLatest { noteId ->
            val uid = uid
            if (noteId == null || uid == null) flowOf(null)
            else RevisionsRepository.revisions(uid, noteId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openHistory(noteId: String) {
        _historyFor.value = noteId
    }

    fun closeHistory() {
        _historyFor.value = null
    }

    /**
     * Restores [revision] into the note, checkpointing what is on screen first so
     * the restore is itself undoable. [onRestored] receives the text to show.
     */
    fun restoreRevision(
        noteId: String,
        currentTitle: String,
        currentBody: String,
        revision: Revision,
        onRestored: (title: String, body: String) -> Unit,
    ) {
        val uid = uid ?: return
        viewModelScope.launch {
            runCatching {
                RevisionsRepository.record(uid, noteId, currentTitle, currentBody, always = true)
            }
            onRestored(revision.title, revision.body)
            closeHistory()
        }
    }

    private fun authCall(block: suspend () -> Unit) {
        viewModelScope.launch {
            _authBusy.value = true
            _authError.value = ""
            try {
                block()
            } catch (e: Exception) {
                _authError.value = authErrorMessage(e)
            } finally {
                _authBusy.value = false
            }
        }
    }
}

/** Turns Firebase's auth error codes into something a human can read. */
private fun authErrorMessage(e: Exception): String = when {
    // Backing out of the Google account picker is a choice, not an error.
    e is GetCredentialCancellationException -> ""
    e is NoCredentialException ->
        "No Google account on this device. Add one in Android settings first."
    e is FirebaseAuthRecentLoginRequiredException ->
        "For security, confirm your password before changing this."
    else -> when ((e as? FirebaseAuthException)?.errorCode) {
        "ERROR_INVALID_EMAIL" -> "That doesn't look like a valid email address."
        "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters."
        "ERROR_EMAIL_ALREADY_IN_USE" -> "That email already has an account — try signing in instead."
        "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_CREDENTIAL" ->
            "Wrong email or password."
        "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a minute and try again."
        "ERROR_PROVIDER_ALREADY_LINKED" -> "That sign-in method is already connected."
        "ERROR_CREDENTIAL_ALREADY_IN_USE" ->
            "Those details already belong to a different Sync Notes account."
        else -> e.message ?: "Something went wrong."
    }
}
