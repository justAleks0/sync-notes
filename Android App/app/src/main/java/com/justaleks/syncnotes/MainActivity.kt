package com.justaleks.syncnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justaleks.syncnotes.ui.EditorScreen
import com.justaleks.syncnotes.ui.NoteListScreen
import com.justaleks.syncnotes.data.Note
import com.justaleks.syncnotes.ui.SettingsScreen
import com.justaleks.syncnotes.ui.SignInScreen
import com.justaleks.syncnotes.ui.UpdateBanner
import com.justaleks.syncnotes.ui.SyncNotesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncNotesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SyncNotesApp()
                }
            }
        }
    }
}

@Composable
private fun SyncNotesApp(viewModel: NotesViewModel = viewModel()) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val authError by viewModel.authError.collectAsStateWithLifecycle()
    val authBusy by viewModel.authBusy.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val needsReauth by viewModel.needsReauth.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()
    val imageUploading by viewModel.imageUploading.collectAsStateWithLifecycle()
    val imageError by viewModel.imageError.collectAsStateWithLifecycle()

    // The app draws edge-to-edge, so the top-level column normally sits under the
    // status bar and each Scaffold insets its own app bar. Once the banner is on
    // screen it becomes the topmost thing, so the column takes the status bar inset
    // instead — windowInsetsPadding also consumes it, which stops the Scaffold below
    // from padding for the same bar a second time.
    val bannerVisible = update != null
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (bannerVisible) Modifier.windowInsetsPadding(WindowInsets.statusBars)
                else Modifier
            )
    ) {
        UpdateBanner(
            update = update,
            progress = updateProgress,
            onDownload = viewModel::downloadAndInstallUpdate,
        )
        SyncNotesContent(
            authState = authState,
            notes = notes,
            authError = authError,
            authBusy = authBusy,
            settings = settings,
            needsReauth = needsReauth,
            imageUploading = imageUploading,
            imageError = imageError,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun SyncNotesContent(
    authState: AuthState,
    notes: List<Note>?,
    authError: String,
    authBusy: Boolean,
    settings: SettingsStatus,
    needsReauth: Boolean,
    imageUploading: Boolean,
    imageError: String,
    viewModel: NotesViewModel,
) {
    // Credential Manager renders its account picker on an Activity, not a bare Context.
    val activity = LocalActivity.current ?: return

    var openNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // The editor supplies the callback that splices the finished markdown in at the
    // caret. It is held here because the picker result arrives long after the tap.
    var pendingInsert by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val insert = pendingInsert
        pendingInsert = null
        if (uri != null && insert != null && openNoteId != null) {
            viewModel.uploadImage(uri, openNoteId!!, insert)
        }
    }

    // Leaving settings should not leave a stale error or notice behind for next time.
    fun closeSettings() {
        showSettings = false
        viewModel.clearSettingsStatus()
    }

    when (authState) {
        AuthState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AuthState.SignedOut -> SignInScreen(
            busy = authBusy,
            error = authError,
            onSignIn = viewModel::signIn,
            onRegister = viewModel::register,
            // Credential Manager needs an Activity to show the account picker on.
            onGoogleSignIn = { viewModel.signInWithGoogle(activity) },
            onModeChanged = viewModel::clearAuthError,
        )

        is AuthState.SignedIn -> {
            val account = authState
            val openNote = remember(notes, openNoteId) {
                notes?.firstOrNull { it.id == openNoteId }
            }

            if (showSettings) {
                BackHandler { closeSettings() }
                SettingsScreen(
                    account = account,
                    status = settings,
                    needsReauth = needsReauth,
                    onSaveName = viewModel::saveDisplayName,
                    onSetPassword = viewModel::setPassword,
                    onConfirmIdentity = viewModel::confirmIdentity,
                    onCancelReauth = viewModel::cancelReauth,
                    onLinkGoogle = { viewModel.linkGoogle(activity) },
                    onUnlink = viewModel::unlinkProvider,
                    onSignOut = {
                        closeSettings()
                        openNoteId = null
                        viewModel.signOut()
                    },
                    onBack = { closeSettings() },
                )
            } else if (openNote == null) {
                // The open note can vanish if it was deleted on another device.
                if (openNoteId != null && notes != null) openNoteId = null

                NoteListScreen(
                    notes = notes,
                    onOpen = { openNoteId = it },
                    onNew = { viewModel.createNote { id -> openNoteId = id } },
                    onSettings = { showSettings = true },
                )
            } else {
                BackHandler { openNoteId = null }
                EditorScreen(
                    note = openNote,
                    uploading = imageUploading,
                    imageError = imageError,
                    onSave = { title, body -> viewModel.saveNote(openNote.id, title, body) },
                    onPickImage = { onInsert ->
                        pendingInsert = onInsert
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onBack = { openNoteId = null },
                    onDelete = {
                        viewModel.deleteNote(openNote.id)
                        openNoteId = null
                    },
                )
            }
        }
    }
}
