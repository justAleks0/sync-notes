package com.justaleks.syncnotes.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Date
import kotlin.math.abs

data class Revision(
    val id: String,
    val title: String,
    val body: String,
    val at: Date?,
)

/**
 * How often the note is worth snapshotting while someone is typing. Autosave runs
 * every 600ms of quiet, and a revision per keystroke-pause would be thousands of
 * useless entries — the history has to read like a list of moments, not a keylog.
 */
private const val CHECKPOINT_GAP_MS = 2 * 60 * 1000L

/** …unless this much text appeared or vanished, which is worth keeping regardless. */
private const val BIG_CHANGE_CHARS = 200

/** Older revisions are dropped past this. Fifty covers days of real editing. */
const val KEEP_REVISIONS = 50

/**
 * Point-in-time snapshots of a note, under users/{uid}/notes/{noteId}/revisions —
 * the same documents the web app writes, so history follows the note across
 * devices rather than being per-client.
 */
object RevisionsRepository {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun revisionsCol(uid: String, noteId: String) =
        db.collection("users").document(uid)
            .collection("notes").document(noteId)
            .collection("revisions")

    private fun toRevision(doc: DocumentSnapshot): Revision = Revision(
        id = doc.id,
        title = doc.getString("title").orEmpty(),
        body = doc.getString("body").orEmpty(),
        // A pending serverTimestamp reads as null, which would sort a revision you
        // just created to the bottom. The estimate keeps it where it belongs.
        at = (doc.get("at", ServerTimestampBehavior.ESTIMATE) as? Timestamp)?.toDate(),
    )

    /**
     * Live list of a note's revisions, newest first.
     *
     * Pruning happens here rather than on write: the snapshot has already paid for
     * these documents, so trimming the tail costs nothing extra.
     */
    fun revisions(uid: String, noteId: String): Flow<List<Revision>> = callbackFlow {
        val registration = revisionsCol(uid, noteId)
            .orderBy("at", Query.Direction.DESCENDING)
            .limit((KEEP_REVISIONS + 25).toLong())
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                val all = snap.documents.map(::toRevision)
                trySend(all.take(KEEP_REVISIONS))

                snap.documents.drop(KEEP_REVISIONS).forEach { it.reference.delete() }
            }
        awaitClose { registration.remove() }
    }

    /**
     * The newest revision per note, so the common case — deciding not to write one —
     * costs no reads at all. Absent means "not looked up yet".
     */
    private val newest = mutableMapOf<String, Revision?>()

    private suspend fun newestRevision(uid: String, noteId: String): Revision? {
        val key = "$uid/$noteId"
        if (newest.containsKey(key)) return newest[key]

        val snap = revisionsCol(uid, noteId)
            .orderBy("at", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .await()

        val found = snap.documents.firstOrNull()?.let(::toRevision)
        newest[key] = found
        return found
    }

    private fun worthKeeping(last: Revision?, title: String, body: String): Boolean {
        // Nothing recorded yet: this is the note's starting point, and losing it
        // would make the first session of editing unrecoverable.
        if (last == null) return true
        if (last.title == title && last.body == body) return false

        val elapsed = last.at?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        if (elapsed >= CHECKPOINT_GAP_MS) return true

        return abs(body.length - last.body.length) >= BIG_CHANGE_CHARS
    }

    /**
     * Offers the current text as a checkpoint. Most calls do nothing — that is the
     * point. Pass [always] for moments the user would expect to find in the list no
     * matter the timing, like the state just before a restore.
     */
    suspend fun record(
        uid: String,
        noteId: String,
        title: String,
        body: String,
        always: Boolean = false,
    ) {
        val last = newestRevision(uid, noteId)
        if (!always && !worthKeeping(last, title, body)) return
        // Two identical entries in a row are noise whatever the reason for the call.
        if (last != null && last.title == title && last.body == body) return

        val ref = revisionsCol(uid, noteId).document()
        ref.set(
            mapOf(
                "title" to title,
                "body" to body,
                "at" to FieldValue.serverTimestamp(),
            )
        ).await()

        // The server's value has not come back yet; now is close enough for the gap
        // check, and always errs towards waiting longer before the next checkpoint.
        newest["$uid/$noteId"] = Revision(ref.id, title, body, Date())
    }

    /**
     * Firestore does not delete subcollections with their parent, so a deleted note
     * would otherwise leave its history behind forever, unreachable by any screen.
     */
    suspend fun deleteAll(uid: String, noteId: String) {
        newest.remove("$uid/$noteId")
        val snap = revisionsCol(uid, noteId).get().await()
        snap.documents.forEach { it.reference.delete().await() }
    }
}
