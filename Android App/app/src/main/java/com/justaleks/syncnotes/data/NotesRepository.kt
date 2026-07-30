package com.justaleks.syncnotes.data

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

data class Note(
    val id: String,
    val title: String,
    val body: String,
    val updatedAt: Date?,
    /** True while a local edit is still queued and not yet acknowledged by the server. */
    val pending: Boolean,
)

/**
 * Notes live under users/{uid}/notes/{noteId} — the same shape the web and desktop
 * apps use, so all three clients see the same documents.
 */
object NotesRepository {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun notesCol(uid: String) = db.collection("users").document(uid).collection("notes")

    /** Emits the signed-in user, or null, and re-emits on every sign-in/sign-out. */
    fun authState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** Live note list for one user, newest edit first. */
    fun notes(uid: String): Flow<List<Note>> = callbackFlow {
        val registration = notesCol(uid)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                trySend(
                    snap.documents.map { doc ->
                        Note(
                            id = doc.id,
                            title = doc.getString("title").orEmpty(),
                            body = doc.getString("body").orEmpty(),
                            updatedAt = (doc.get("updatedAt") as? Timestamp)?.toDate(),
                            pending = doc.metadata.hasPendingWrites(),
                        )
                    }
                )
            }
        awaitClose { registration.remove() }
    }

    suspend fun createNote(uid: String): String {
        val ref = notesCol(uid).document()
        ref.set(
            mapOf(
                "title" to "",
                "body" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        return ref.id
    }

    fun saveNote(uid: String, id: String, title: String, body: String) {
        // Deliberately not awaited: Firestore's local cache applies the write
        // immediately and replays it to the server whenever the phone is online.
        notesCol(uid).document(id).update(
            mapOf(
                "title" to title,
                "body" to body,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        )
    }

    fun deleteNote(uid: String, id: String) {
        notesCol(uid).document(id).delete()
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    suspend fun register(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password).await()
    }

    suspend fun signInWithGoogle(context: Context) {
        auth.signInWithCredential(GoogleSignIn.requestCredential(context)).await()
    }

    /** Attaches Google to the account that is already signed in. */
    suspend fun linkGoogle(context: Context) {
        val user = auth.currentUser ?: error("Not signed in.")
        user.linkWithCredential(GoogleSignIn.requestCredential(context)).await()
        user.reload().await()
    }

    suspend fun unlinkProvider(providerId: String) {
        val user = auth.currentUser ?: error("Not signed in.")
        user.unlink(providerId).await()
        user.reload().await()
    }

    fun signOut() = auth.signOut()

    /** Which sign-in methods this account has, e.g. ["password", "google.com"]. */
    fun providerIds(): List<String> =
        auth.currentUser?.providerData?.map { it.providerId }?.filter { it != "firebase" }.orEmpty()

    suspend fun updateDisplayName(name: String) {
        val user = auth.currentUser ?: error("Not signed in.")
        user.updateProfile(
            UserProfileChangeRequest.Builder()
                .setDisplayName(name.trim().ifEmpty { null })
                .build()
        ).await()
        user.reload().await()
    }

    /**
     * Sets a password on an account that has none (a Google-only account), or changes
     * an existing one. Adding is a link, not an update — Firebase treats email/password
     * as a separate identity attached to the same user.
     */
    suspend fun setOrChangePassword(password: String) {
        val user = auth.currentUser ?: error("Not signed in.")
        if (providerIds().contains("password")) {
            user.updatePassword(password).await()
        } else {
            val email = user.email ?: error("This account has no email address.")
            user.linkWithCredential(EmailAuthProvider.getCredential(email, password)).await()
        }
        user.reload().await()
    }

    /** Firebase rejects password changes when the sign-in is more than a few minutes old. */
    suspend fun reauthenticate(password: String) {
        val user = auth.currentUser ?: error("Not signed in.")
        val email = user.email ?: error("This account has no email address.")
        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
    }
}
