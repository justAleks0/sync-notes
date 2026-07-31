package com.justaleks.syncnotes.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.justaleks.syncnotes.AssistState
import com.justaleks.syncnotes.ai.ACTIONS
import com.justaleks.syncnotes.ai.AiSettings
import com.justaleks.syncnotes.ai.MAX_IMAGES
import com.justaleks.syncnotes.ai.NoteImage
import com.justaleks.syncnotes.ai.buildCustomPrompt
import com.justaleks.syncnotes.ai.extractImages
import com.justaleks.syncnotes.ai.supportsVision
import kotlinx.coroutines.launch

/**
 * The phone's equivalent of the web editor's Assist panel — same actions, same
 * prompts, same rule that nothing reaches the note until the user says where it
 * goes. A bottom sheet rather than a floating panel because it has to coexist
 * with the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssistSheet(
    state: AssistState,
    settings: AiSettings,
    title: String,
    /** The selected text if there is a selection, otherwise the whole body. */
    source: String,
    scopeIsSelection: Boolean,
    onRun: (prompt: String, actionId: String?, images: List<NoteImage>) -> Unit,
    onStop: () -> Unit,
    onReplace: (String) -> Unit,
    onAppend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var custom by remember { mutableStateOf("") }
    var withImages by remember { mutableStateOf(true) }

    val images = remember(source) { extractImages(source) }
    val canSee = supportsVision(settings.provider, settings.model)
    // Sliced here rather than in the extractor so the sheet can say how many were
    // left behind.
    val attached = if (canSee && withImages) images.take(MAX_IMAGES) else emptyList()

    val outputScroll = rememberScrollState()
    LaunchedEffect(state.output) { outputScroll.scrollTo(outputScroll.maxValue) }

    val canApply = state.output.isNotBlank() && !state.running

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = (if (scopeIsSelection) "On your selection" else "On this note") +
                    " · ${settings.model}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (images.isNotEmpty()) {
                if (canSee) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = withImages,
                            onCheckedChange = { withImages = it },
                            enabled = !state.running,
                        )
                        Text(
                            text = buildString {
                                append("Show ")
                                append(
                                    if (images.size == 1) "the image"
                                    else "the ${images.size} images"
                                )
                                append(" to the model")
                                if (images.size > MAX_IMAGES) append(" — the first $MAX_IMAGES")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        "${settings.model} can't read images. Pick a model that can in " +
                            "Settings to have them taken into account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ACTIONS.forEach { action ->
                    val selected = state.actionId == action.id
                    AssistChip(
                        onClick = { onRun(action.build(source, title), action.id, attached) },
                        enabled = !state.running,
                        label = { Text(action.label) },
                        colors = if (selected) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                labelColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            AssistChipDefaults.assistChipColors()
                        },
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    placeholder = { Text("Or ask for something specific…") },
                    singleLine = true,
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onRun(buildCustomPrompt(custom, source), null, attached)
                    },
                    enabled = !state.running && custom.isNotBlank(),
                ) { Text("Ask") }
            }

            if (state.error.isNotEmpty()) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.output.isNotEmpty() || state.running) {
                SelectionContainer {
                    Text(
                        text = state.output.ifEmpty { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 260.dp)
                            .verticalScroll(outputScroll),
                    )
                }
            }

            if (state.running) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onReplace(state.output.trim()); onDismiss() },
                        enabled = canApply,
                    ) {
                        Text(if (scopeIsSelection) "Replace selection" else "Replace note")
                    }
                    OutlinedButton(
                        onClick = { onAppend(state.output.trim()); onDismiss() },
                        enabled = canApply,
                    ) { Text("Insert below") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText(
                                            "Sync Notes",
                                            state.output.trim(),
                                        )
                                    )
                                )
                            }
                        },
                        enabled = canApply,
                    ) { Text("Copy") }
                }
            }
        }
    }
}
