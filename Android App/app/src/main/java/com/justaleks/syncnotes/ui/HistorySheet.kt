package com.justaleks.syncnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justaleks.syncnotes.data.Revision
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/** "2 minutes ago" reads better than a timestamp for anything from today. */
fun relativeTime(at: Date?): String {
    if (at == null) return "just now"

    val seconds = (System.currentTimeMillis() - at.time) / 1000
    if (seconds < 60) return "just now"

    val minutes = seconds / 60
    if (minutes < 60) return "$minutes minute${if (minutes == 1L) "" else "s"} ago"

    val hours = minutes / 60
    if (hours < 24) return "$hours hour${if (hours == 1L) "" else "s"} ago"

    val days = hours / 24
    if (days < 7) return "$days day${if (days == 1L) "" else "s"} ago"

    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(at)
}

/**
 * The note's earlier versions, and the way back to one of them. Mirrors the web
 * editor's History panel — same documents underneath, so a version written on the
 * laptop is restorable here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    revisions: List<Revision>?,
    /** What the note says right now, to mark the live version in the list. */
    currentTitle: String,
    currentBody: String,
    onRestore: (Revision) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf<Revision?>(null) }

    // Restoring an old version makes its text current again, so several entries can
    // match what is on screen. Only the newest of them is the one being looked at.
    val liveIndex = revisions?.indexOfFirst {
        it.title == currentTitle && it.body == currentBody
    } ?: -1

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val chosen = selected
            if (chosen == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("History", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            revisions == null -> "Loading…"
                            revisions.size == 1 -> "1 saved version"
                            else -> "${revisions.size} saved versions"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (revisions != null && revisions.isEmpty()) {
                    Text(
                        "No versions yet. One is kept every couple of minutes while you " +
                            "write, and whenever a lot changes at once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val list = revisions.orEmpty()
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(list) { index, revision ->
                        // How much this version differs in size from the one that came
                        // after it — the quickest way to spot "this is where the note
                        // lost half its text".
                        val newer = list.getOrNull(index - 1)
                        RevisionRow(
                            revision = revision,
                            delta = if (newer == null) 0 else revision.body.length - newer.body.length,
                            live = index == liveIndex,
                            onClick = { selected = revision },
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { selected = null }) { Text("← All versions") }
                    Text(
                        relativeTime(chosen.at),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        chosen.title.ifEmpty { "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (chosen.body.isBlank()) {
                        Text(
                            "This version was empty.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Markdown(
                            content = chosen.body,
                            imageTransformer = LayoutAwareImageTransformer,
                            colors = markdownColor(),
                            typography = markdownTypography(),
                        )
                    }
                }

                val unchanged = chosen.title == currentTitle && chosen.body == currentBody
                // Restoring writes the note, so the version being left is checkpointed
                // first — a restore can always be undone by restoring the entry it makes.
                Button(onClick = { onRestore(chosen) }, enabled = !unchanged) {
                    Text(if (unchanged) "This is the current version" else "Restore this version")
                }
            }
        }
    }
}

@Composable
private fun RevisionRow(
    revision: Revision,
    delta: Int,
    live: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(relativeTime(revision.at), style = MaterialTheme.typography.bodyMedium)
            if (live) {
                Text(
                    "· current",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            buildString {
                append(revision.title.ifEmpty { "Untitled" })
                if (delta != 0) {
                    append(" · ")
                    append(if (delta > 0) "+$delta" else "-${abs(delta)}")
                    append(" chars")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
    }
}
