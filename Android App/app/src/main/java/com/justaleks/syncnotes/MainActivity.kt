package com.justaleks.syncnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

    Column(modifier = Modifier.fillMaxSize()) {
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
    viewModel: NotesViewModel,
) {
    var openNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

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
                    onSave = { title, body -> viewModel.saveNote(openNote.id, title, body) },
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
