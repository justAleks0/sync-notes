package com.justaleks.syncnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.justaleks.syncnotes.AssistState
import com.justaleks.syncnotes.ai.AiSettings
import com.justaleks.syncnotes.ai.NoteImage
import com.justaleks.syncnotes.data.Note
import com.justaleks.syncnotes.data.Revision
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val AUTOSAVE_DELAY_MS = 600L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    note: Note,
    uploading: Boolean,
    imageError: String,
    aiSettings: AiSettings,
    assist: AssistState,
    revisions: List<Revision>?,
    onSave: (title: String, body: String) -> Unit,
    onPickImage: (onInsert: (String) -> Unit) -> Unit,
    onOpenHistory: () -> Unit,
    onCloseHistory: () -> Unit,
    onRestore: (
        currentTitle: String,
        currentBody: String,
        revision: Revision,
        onRestored: (String, String) -> Unit,
    ) -> Unit,
    onRunAssist: (
        prompt: String,
        actionId: String?,
        images: List<NoteImage>,
        wantsEdits: Boolean,
    ) -> Unit,
    onStopAssist: () -> Unit,
    onClearAssist: () -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    // The fields own their text outright.
    //
    // Driving a text field from hoisted String state means every save round-trips
    // through recomposition and back into the field, and each of those trips
    // re-syncs the keyboard: the composing word was thrown away (typed letters
    // vanished), the caret was reset (a title typed "hel" came out "elh"), and the
    // keyboard dropped back to its letter layer mid-symbol. TextFieldState keeps
    // the buffer on the field's side of that line, so a save is invisible to the IME.
    val titleState = remember(note.id) { TextFieldState(note.title) }
    val bodyState = remember(note.id) { TextFieldState(note.body) }

    val title = titleState.text.toString()
    val body = bodyState.text.toString()

    var preview by remember(note.id) { mutableStateOf(false) }
    // Captured when the sheet opens: the field loses its selection to the sheet, so
    // the range has to be remembered rather than read back later.
    var assistScope by remember(note.id) { mutableStateOf<TextRange?>(null) }
    var showAssist by remember(note.id) { mutableStateOf(false) }
    var showHistory by remember(note.id) { mutableStateOf(false) }

    // The last text handed to Firestore. A snapshot matching this is our own write
    // echoing back, not an edit from another device.
    var saved by remember(note.id) { mutableStateOf(note.title to note.body) }

    // Derived rather than tracked: "there is something unsaved" is a fact about the
    // text, and a separate flag could disagree with it.
    val dirty = title != saved.first || body != saved.second

    // Adopt edits that arrived from another device, but never clobber what the user
    // is actively typing here.
    LaunchedEffect(note.title, note.body) {
        if (note.title == saved.first && note.body == saved.second) return@LaunchedEffect
        if (title != saved.first || body != saved.second) return@LaunchedEffect

        saved = note.title to note.body
        titleState.setTextAndPlaceCursorAtEnd(note.title)
        bodyState.setTextAndPlaceCursorAtEnd(note.body)
    }

    // Debounced autosave — no save button, the way a notes app should work.
    // collectLatest cancels the pending delay on the next keystroke, so the write
    // only happens once typing actually pauses.
    LaunchedEffect(note.id) {
        snapshotFlow { titleState.text.toString() to bodyState.text.toString() }
            .collectLatest { (t, b) ->
                if (t == saved.first && b == saved.second) return@collectLatest
                delay(AUTOSAVE_DELAY_MS)
                saved = t to b
                onSave(t, b)
            }
    }

    // Flush anything still unsaved when the screen goes away (back press, process death).
    val latest = rememberUpdatedState(Triple(dirty, title, body))
    DisposableEffect(note.id) {
        onDispose {
            val (isDirty, t, b) = latest.value
            if (isDirty) onSave(t, b)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            uploading -> "Uploading image…"
                            dirty || note.pending -> "Saving…"
                            else -> "Saved"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            onPickImage { markdown ->
                                val inserted = "\n\n" + markdown + "\n\n"
                                bodyState.edit {
                                    // Where the user was last typing, so the image
                                    // lands there rather than at the end.
                                    val at = selection.end.coerceIn(0, length)
                                    replace(at, at, inserted)
                                    selection = TextRange(at + inserted.length)
                                }
                            }
                        },
                        enabled = !uploading,
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Insert image")
                    }
                    IconButton(
                        onClick = {
                            showHistory = true
                            onOpenHistory()
                        },
                    ) {
                        Icon(Icons.Default.History, contentDescription = "Earlier versions")
                    }
                    if (aiSettings.isConfigured) {
                        IconButton(
                            onClick = {
                                assistScope = bodyState.selection.takeIf { !it.collapsed }
                                showAssist = true
                            },
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Assist")
                        }
                    }
                    IconButton(onClick = { preview = !preview }) {
                        Icon(
                            if (preview) Icons.Default.Edit else Icons.Default.Visibility,
                            contentDescription = if (preview) "Edit" else "Preview",
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete note",
                            tint = MaterialTheme.colorScheme.error,
                        )
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
                .padding(horizontal = 16.dp),
        ) {
            if (imageError.isNotEmpty()) {
                Text(
                    imageError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            EditorField(
                state = titleState,
                placeholder = "Title",
                readOnly = preview,
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            )

            if (preview) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    if (body.isBlank()) {
                        Text(
                            "Nothing to preview yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Markdown(
                            content = body,
                            // Honours the width and alignment the web editor records
                            // in the image URL fragment.
                            imageTransformer = LayoutAwareImageTransformer,
                            colors = markdownColor(),
                            typography = markdownTypography(),
                        )
                    }
                }
            } else {
                EditorField(
                    state = bodyState,
                    placeholder = "Start writing… markdown works: **bold**, # heading, - list",
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp),
                )
            }
        }

        if (showAssist) {
            val scope = assistScope
            fun close() {
                showAssist = false
                assistScope = null
                onClearAssist()
            }

            AssistSheet(
                state = assist,
                settings = aiSettings,
                title = title,
                source = scope?.let { body.substring(it.min, it.max) } ?: body,
                scopeIsSelection = scope != null,
                onRun = onRunAssist,
                onStop = onStopAssist,
                onReplace = { text ->
                    if (scope != null) {
                        bodyState.edit {
                            replace(scope.min, scope.max, text)
                            selection = TextRange(scope.min + text.length)
                        }
                    } else {
                        bodyState.setTextAndPlaceCursorAtEnd(text)
                    }
                },
                onAppend = { text ->
                    val at = scope?.max ?: body.length
                    val merged = (body.substring(0, at) + "\n\n" + text + "\n\n" + body.substring(at))
                        .replace(Regex("\n{3,}"), "\n\n")
                    bodyState.setTextAndPlaceCursorAtEnd(merged)
                },
                onDismiss = { close() },
            )
        }

        if (showHistory) {
            HistorySheet(
                revisions = revisions,
                currentTitle = title,
                currentBody = body,
                onRestore = { revision ->
                    onRestore(title, body, revision) { restoredTitle, restoredBody ->
                        titleState.setTextAndPlaceCursorAtEnd(restoredTitle)
                        bodyState.setTextAndPlaceCursorAtEnd(restoredBody)
                    }
                    showHistory = false
                },
                onDismiss = {
                    showHistory = false
                    onCloseHistory()
                },
            )
        }
    }
}

/**
 * Undecorated text field — the editor should feel like a blank page, not a form.
 *
 * Built on TextFieldState rather than a hoisted String or TextFieldValue. The
 * value-based overloads push every edit out to app state and accept it back on
 * the next recomposition, and each of those round trips re-syncs the keyboard:
 * the composing word is discarded, the caret is reset, and the layout snaps back
 * to letters. Since autosave recomposes this screen constantly, all three were
 * happening while typing. Here the field keeps its own buffer and a save never
 * touches it.
 */
@Composable
private fun EditorField(
    state: TextFieldState,
    placeholder: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    BasicTextField(
        state = state,
        readOnly = readOnly,
        textStyle = textStyle,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier,
        lineLimits = TextFieldLineLimits.MultiLine(),
        decorator = { inner ->
            if (state.text.isEmpty()) {
                Text(
                    placeholder,
                    style = textStyle.merge(
                        SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ),
                )
            }
            inner()
        },
    )
}
