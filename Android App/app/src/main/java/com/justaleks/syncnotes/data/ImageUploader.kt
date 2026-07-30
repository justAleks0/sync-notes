package com.justaleks.syncnotes.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Anything bigger than this is refused before it is uploaded. */
const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

/**
 * Uploads note images to users/{uid}/notes/{noteId}/… — the same path shape as
 * Firestore, so the storage rules stay a plain ownership check.
 */
object ImageUploader {

    private val storage: FirebaseStorage get() = FirebaseStorage.getInstance()

    suspend fun upload(context: Context, uid: String, noteId: String, uri: Uri): String =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val type = resolver.getType(uri) ?: "image/*"
            require(type.startsWith("image/")) { "That file isn't an image." }

            val name = displayName(context, uri)
            val size = fileSize(context, uri)
            require(size <= MAX_IMAGE_BYTES) {
                "That image is ${"%.1f".format(size / 1024.0 / 1024.0)} MB - the limit is 10 MB."
            }

            val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "image" }
            val ref = storage.reference.child(
                "users/$uid/notes/$noteId/${System.currentTimeMillis()}-$safeName"
            )

            resolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "Couldn't read that image." }
                ref.putStream(stream).await()
            }
            ref.downloadUrl.await().toString()
        }

    /** Markdown image syntax, with the filename as alt text so it degrades readably. */
    fun markdownFor(context: Context, uri: Uri, url: String): String {
        val alt = displayName(context, uri).substringBeforeLast('.').replace(Regex("[\\[\\]]"), "")
        return "![$alt]($url)"
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        } ?: "image"

    private fun fileSize(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                cursor.getLong(column)
            } else {
                0L
            }
        } ?: 0L
}

/** Firebase Storage errors are opaque; the common ones deserve real sentences. */
fun uploadErrorMessage(e: Exception): String = when ((e as? StorageException)?.errorCode) {
    StorageException.ERROR_NOT_AUTHENTICATED -> "Sign in again to upload images."
    StorageException.ERROR_NOT_AUTHORIZED ->
        "Not allowed to upload. Are the storage rules deployed?"
    StorageException.ERROR_QUOTA_EXCEEDED -> "Storage quota exceeded for this project."
    StorageException.ERROR_BUCKET_NOT_FOUND ->
        "Storage isn't set up for this project yet - enable it in the Firebase console."
    StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> "Upload timed out. Check your connection."
    else -> e.message ?: "Upload failed."
}
