package com.justaleks.syncnotes.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

private const val REPO = "justAleks0/sync-notes"
private const val LATEST_RELEASE = "https://api.github.com/repos/$REPO/releases/latest"

/**
 * Asks GitHub whether this build is the newest APK published, and installs the new
 * one if not. Everything here fails quietly — a broken update check should never be
 * something the user has to deal with while writing a note.
 */
object UpdateChecker {

    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.0.0"

    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
                // GitHub rejects API calls without a User-Agent.
                setRequestProperty("User-Agent", "SyncNotes-Android")
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val body = connection.use { it.inputStream.bufferedReader().readText() }
            val release = JSONObject(body)
            val latest = release.optString("tag_name").removePrefix("v")
            if (latest.isEmpty() || compareVersions(latest, currentVersion(context)) <= 0) {
                return@runCatching null
            }

            val assets = release.optJSONArray("assets") ?: return@runCatching null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return@runCatching UpdateInfo(
                        version = latest,
                        downloadUrl = asset.optString("browser_download_url"),
                        sizeBytes = asset.optLong("size"),
                    )
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Streams the APK into cache, then hands it to the system installer. Android shows
     * its own confirmation prompt — the user still has to approve installing it.
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Only one update file is ever kept, so a failed download can't accumulate.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "sync-notes-${info.version}.apk")

        val connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "SyncNotes-Android")
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
        }

        connection.use { conn ->
            val total = if (info.sizeBytes > 0) info.sizeBytes else conn.contentLength.toLong()
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        received += read
                        if (total > 0) onProgress(((received * 100) / total).toInt())
                    }
                }
            }
        }
        target
    }

    fun installApk(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Compares dotted version strings. Returns >0 when [a] is newer than [b]. */
    private fun compareVersions(a: String, b: String): Int {
        val left = a.split(".").map { it.toIntOrNull() ?: 0 }
        val right = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }
}

private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
    try {
        block(this)
    } finally {
        disconnect()
    }
