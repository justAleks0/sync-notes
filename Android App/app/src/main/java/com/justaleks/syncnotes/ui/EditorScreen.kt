package com.justaleks.syncnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.justaleks.syncnotes.AssistState
import com.justaleks.syncnotes.ai.AiSettings
import com.justaleks.syncnotes.ai.NoteImage
import com.justaleks.syncnotes.data.Note
import kotlinx.coroutines.delay

private const val AUTOSAVE_DELAY_MS = 600L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    note: Note,
    uploading: Boolean,
    imageError: String,
    aiSettings: AiSettings,
    assist: AssistState,
    onSave: (title: String, body: String) -> Unit,
    onPickImage: (onInsert: (String) -> Unit) -> Unit,
    onRunAssist: (prompt: String, actionId: String?, images: List<NoteImage>) -> Unit,
    onStopAssist: () -> Unit,
    onClearAssist: () -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    var dirty by remember(note.id) { mutableStateOf(false) }
    var preview by remember(note.id) { mutableStateOf(false) }
    // Tracked so an uploaded image can be spliced in where the user was typing.
    var selection by remember(note.id) { mutableStateOf(TextRange(0)) }
    // Captured when the sheet opens: the field loses its selection to the sheet, so
    // the range has to be remembered rather than read back later.
    var assistScope by remember(note.id) { mutableStateOf<TextRange?>(null) }
    var showAssist by remember(note.id) { mutableStateOf(false) }

    // The last text handed to Firestore. A snapshot matching this is our own write
    // echoing back, not an edit from another device.
    var saved by remember(note.id) { mutableStateOf(note.title to note.body) }

    // Adopt edits that arrived from another device, but never clobber what the
    // user is actively typing here.
    LaunchedEffect(note.title, note.body, dirty) {
        if (dirty) return@LaunchedEffect
        // Reassigning the field to text it already holds still rebuilds its
        // TextFieldValue, which throws the caret back to the start. Our own echo
        // has to be ignored, not merely allowed through harmlessly.
        if (note.title == saved.first && note.body == saved.second) return@LaunchedEffect

        saved = note.title to note.body
        title = note.title
        body = note.body
    }

    // Debounced autosave — no save button, the way a notes app should work. Keyed on
    // the text, so a keystroke restarts the delay and nothing is ever marked saved
    // while newer characters are still sitting in the field.
    LaunchedEffect(dirty, title, body) {
        if (dirty) {
            delay(AUTOSAVE_DELAY_MS)
            saved = title to body
            onSave(title, body)
            dirty = false
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
                                val at = selection.end.coerceIn(0, body.length)
                                body = body.substring(0, at) + "\n\n" + markdown + "\n\n" +
                                    body.substring(at)
                                dirty = true
                            }
                        },
                        enabled = !uploading,
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Insert image")
                    }
                    if (aiSettings.isConfigured) {
                        IconButton(
                            onClick = {
                                assistScope = selection.takeIf { !it.collapsed }
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
                value = title,
                onValueChange = { title = it; dirty = true },
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
                EditorFieldWithSelection(
                    value = body,
                    selection = selection,
                    onValueChange = { text, range ->
                        body = text
                        selection = range
                        dirty = true
                    },
                    onSelectionChange = { selection = it },
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
                    body = if (scope != null) {
                        body.substring(0, scope.min) + text + body.substring(scope.max)
                    } else {
                        text
                    }
                    dirty = true
                },
                onAppend = { text ->
                    val at = scope?.max ?: body.length
                    body = (body.substring(0, at) + "\n\n" + text + "\n\n" + body.substring(at))
                        .replace(Regex("\n{3,}"), "\n\n")
                    dirty = true
                },
                onDismiss = { close() },
            )
        }
    }
}

/**
 * Undecorated text field — the editor should feel like a blank page, not a form.
 */
@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        textStyle = textStyle,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier,
        decorationBox = { inner ->
            if (value.isEmpty()) {
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

/**
 * Same field, but reporting the caret position — an inserted image has to land where
 * the user was typing rather than at the end of the note.
 */
@Composable
private fun EditorFieldWithSelection(
    value: String,
    selection: TextRange,
    onValueChange: (String, TextRange) -> Unit,
    onSelectionChange: (TextRange) -> Unit,
    placeholder: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    // The field owns its own TextFieldValue so typing stays responsive, but it is
    // rebuilt whenever the text changes underneath it (an image was just inserted).
    val fieldValue = remember(value, selection) {
        TextFieldValue(text = value, selection = selection)
    }

    BasicTextField(
        value = fieldValue,
        onValueChange = { next ->
            if (next.text == value) onSelectionChange(next.selection)
            else onValueChange(next.text, next.selection)
        },
        textStyle = textStyle,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier,
        decorationBox = { inner ->
            if (value.isEmpty()) {
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
