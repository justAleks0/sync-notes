package com.justaleks.syncnotes

import android.app.Application
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.justaleks.syncnotes.data.Note
import com.justaleks.syncnotes.data.NotesRepository
import com.justaleks.syncnotes.data.UpdateChecker
import com.justaleks.syncnotes.data.UpdateInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    /** Null until a download starts, then 0..100. */
    private val _updateProgress = MutableStateFlow<Int?>(null)
    val updateProgress: StateFlow<Int?> = _updateProgress.asStateFlow()

    init {
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
        uid?.let { NotesRepository.saveNote(it, id, title, body) }
    }

    fun deleteNote(id: String) {
        uid?.let { NotesRepository.deleteNote(it, id) }
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
