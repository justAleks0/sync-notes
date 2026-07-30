package com.justaleks.syncnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justaleks.syncnotes.data.Note
import kotlinx.coroutines.delay

private const val AUTOSAVE_DELAY_MS = 600L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    note: Note,
    onSave: (title: String, body: String) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    var dirty by remember(note.id) { mutableStateOf(false) }

    // Adopt edits that arrived from another device, but never clobber what the
    // user is actively typing here.
    LaunchedEffect(note.title, note.body, dirty) {
        if (!dirty) {
            title = note.title
            body = note.body
        }
    }

    // Debounced autosave — no save button, the way a notes app should work.
    LaunchedEffect(dirty, title, body) {
        if (dirty) {
            delay(AUTOSAVE_DELAY_MS)
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
                        if (dirty || note.pending) "Saving…" else "Saved",
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
            EditorField(
                value = title,
                onValueChange = { title = it; dirty = true },
                placeholder = "Title",
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            )
            EditorField(
                value = body,
                onValueChange = { body = it; dirty = true },
                placeholder = "Start writing…",
                textStyle = LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp),
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
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
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
