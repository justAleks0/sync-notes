package com.justaleks.syncnotes.ui

import android.content.ClipData
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.justaleks.syncnotes.AssistState
import com.justaleks.syncnotes.ai.ACTIONS
import com.justaleks.syncnotes.ai.AiResult
import com.justaleks.syncnotes.ai.AiSettings
import com.justaleks.syncnotes.ai.DiffKind
import com.justaleks.syncnotes.ai.DiffRow
import com.justaleks.syncnotes.ai.EditStatus
import com.justaleks.syncnotes.ai.SuggestedEdit
import com.justaleks.syncnotes.ai.applyEdits
import com.justaleks.syncnotes.ai.diffLines
import com.justaleks.syncnotes.ai.statusOf
import com.justaleks.syncnotes.ai.summarise
import com.justaleks.syncnotes.ai.withContext
import com.justaleks.syncnotes.ai.MAX_IMAGES
import com.justaleks.syncnotes.ai.NoteImage
import com.justaleks.syncnotes.ai.buildCustomPrompt
import com.justaleks.syncnotes.ai.extractImages
import com.justaleks.syncnotes.ai.supportsVision
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The before-and-after, line by line.
 *
 * Without this the only way to know what an action did was to already know the
 * note by heart — and a model that quietly rewrites a section you liked looks
 * exactly like one that did the job.
 */
@Composable
private fun DiffView(rows: List<DiffRow>) {
    val added = MaterialTheme.colorScheme.primary
    val removed = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
        items(rows.size) { index ->
            val row = rows[index]
            val line = row.line

            if (line == null) {
                Text(
                    "${row.hidden} unchanged ${if (row.hidden == 1) "line" else "lines"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.padding(vertical = 3.dp, horizontal = 12.dp),
                )
                return@items
            }

            val tint = when (line.kind) {
                DiffKind.ADD -> added
                DiffKind.REMOVE -> removed
                DiffKind.SAME -> muted
            }
            val mark = when (line.kind) {
                DiffKind.ADD -> "+ "
                DiffKind.REMOVE -> "− "
                DiffKind.SAME -> "  "
            }

            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = tint)) { append(mark) }
                    val words = line.words
                    if (words == null) {
                        withStyle(SpanStyle(color = if (line.kind == DiffKind.SAME) muted else tint)) {
                            append(line.text.ifEmpty { " " })
                        }
                    } else {
                        // Only the words that actually moved are tinted, so a
                        // one-word fix reads as a one-word fix.
                        for (part in words) {
                            withStyle(SpanStyle(color = if (part.changed) tint else muted)) {
                                append(part.text)
                            }
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * The suggestions themselves, each with the exact before and after so the change
 * can be judged without hunting for it in the note.
 */
@Composable
private fun EditList(
    edits: List<SuggestedEdit>,
    source: String,
    chosen: Set<String>,
    onToggle: (String) -> Unit,
) {
    if (edits.isEmpty()) {
        Text(
            "Nothing to change — it reads fine as it is.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
        items(edits, key = { it.id }) { edit ->
            val status = statusOf(source, edit)
            val missing = status == EditStatus.MISSING

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (missing) Modifier
                        else Modifier.clickable { onToggle(edit.id) }
                    )
                    .padding(vertical = 4.dp),
            ) {
                Checkbox(
                    checked = chosen.contains(edit.id),
                    onCheckedChange = { onToggle(edit.id) },
                    enabled = !missing,
                )
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(edit.why, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.error,
                                    textDecoration = TextDecoration.LineThrough,
                                )
                            ) { append(edit.find) }
                            if (edit.replace.isNotEmpty()) {
                                append("  ")
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                    append(edit.replace)
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (missing) {
                        Text(
                            "That text isn't in the note any more — skipping this one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (status == EditStatus.AMBIGUOUS) {
                        Text(
                            "Appears more than once; the first is changed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

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
    onRun: (
        prompt: String,
        actionId: String?,
        images: List<NoteImage>,
        wantsEdits: Boolean,
        describeIn: String?,
    ) -> Unit,
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

    var elapsed by remember(state.running) { mutableStateOf(0f) }
    LaunchedEffect(state.running) {
        if (!state.running) return@LaunchedEffect
        val started = System.currentTimeMillis()
        while (true) {
            elapsed = (System.currentTimeMillis() - started) / 1000f
            delay(100)
        }
    }

    // A custom instruction replaces the note too, so it gets a diff like the rest.
    val settled = !state.running && state.edits == null && state.output.isNotBlank()
    val replaces = ACTIONS.firstOrNull { it.id == state.actionId }?.result != AiResult.APPEND
    val diff = remember(settled, replaces, source, state.output) {
        if (settled && replaces) diffLines(source, state.output.trim()) else null
    }
    val change = diff?.let { summarise(it) }
    val diffRows = diff?.let { withContext(it) }

    // Everything that can still be applied starts ticked: the common case is
    // wanting most of them.
    var chosen by remember(state.edits) {
        mutableStateOf(
            state.edits.orEmpty()
                .filter { statusOf(source, it) != EditStatus.MISSING }
                .map { it.id }
                .toSet()
        )
    }

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
                    // Describing pictures needs pictures, and a model that can see them.
                    val blocked = action.needsImages && attached.isEmpty()
                    AssistChip(
                        onClick = {
                            onRun(
                                action.build(source, title),
                                action.id,
                                attached,
                                action.result == AiResult.EDITS,
                                if (action.needsImages) source else null,
                            )
                        },
                        enabled = !state.running && !blocked,
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
                        onRun(buildCustomPrompt(custom, source), null, attached, false, null)
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

            if (state.running) {
                // A fast answer and a broken one look identical without something
                // on screen that is visibly counting.
                Text(
                    "Working… %.1fs".format(elapsed) +
                        if (state.output.isNotEmpty()) " · ${state.output.length} characters so far"
                        else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (change != null) {
                Text(
                    if (change.unchanged) "The model returned the note unchanged."
                    else "${change.removed} ${if (change.removed == 1) "line" else "lines"} " +
                        "replaced by ${change.added}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (change.unchanged) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val edits = state.edits
            if (edits != null) {
                EditList(
                    edits = edits,
                    source = source,
                    chosen = chosen,
                    onToggle = { id ->
                        chosen = if (chosen.contains(id)) chosen - id else chosen + id
                    },
                )
            } else if (diffRows != null && change?.unchanged == false) {
                DiffView(diffRows)
            } else if (state.output.isNotEmpty() || state.running) {
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
            } else if (edits != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Same rule as everywhere else here: nothing reaches the note
                    // until the user says which parts of it should.
                    Button(
                        onClick = {
                            val picked = edits.filter { chosen.contains(it.id) }
                            onReplace(applyEdits(source, picked))
                            onDismiss()
                        },
                        enabled = chosen.isNotEmpty(),
                    ) {
                        Text(
                            if (chosen.isEmpty()) "Nothing selected"
                            else "Apply ${chosen.size} ${if (chosen.size == 1) "edit" else "edits"}"
                        )
                    }
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                }
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
