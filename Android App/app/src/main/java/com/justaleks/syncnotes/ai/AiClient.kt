package com.justaleks.syncnotes.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** A provider replied with an error status; [body] is its raw JSON. */
class AiException(val status: Int, val body: String) : IOException("HTTP $status")

private const val ANTHROPIC_BASE = "https://api.anthropic.com/v1"
private const val OPENAI_BASE = "https://api.openai.com/v1"

/** Long enough for a slow first token, short enough that a dead link gives up. */
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 120_000

/**
 * Talks to Anthropic and OpenAI directly from the phone, with the user's own key.
 *
 * Hand-rolled over HttpURLConnection rather than pulling in a vendor SDK: the two
 * calls this app makes are "list models" and "stream one message", both of which
 * are a POST and a line-oriented reader. Neither vendor ships a Kotlin/Android SDK
 * that would earn its size here, and the web app already proves the shape of the
 * requests.
 */
object AiClient {

    /**
     * Model IDs move faster than any list we could hard-code, so ask the provider
     * with the user's own key instead of shipping a guess that 404s next quarter.
     */
    suspend fun listModels(provider: AiProvider, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val url = if (provider == AiProvider.ANTHROPIC) {
                "$ANTHROPIC_BASE/models?limit=100"
            } else {
                "$OPENAI_BASE/models"
            }

            val connection = open(url, provider, apiKey, post = false)
            val body = try {
                if (connection.responseCode >= 400) throw errorFor(connection)
                connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }

            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until data.length())
                .map { data.getJSONObject(it).optString("id") }
                .filter { it.isNotEmpty() && isUsableChatModel(provider, it) }
                .sorted()
        }

    /**
     * Streams a completion, calling [onText] with each chunk from the IO thread.
     *
     * Streaming rather than awaiting the whole response: these requests can run for
     * a while on a long note, and a request that returns nothing until it is
     * finished both feels broken and risks a read timeout.
     */
    suspend fun stream(
        settings: AiSettings,
        system: String,
        prompt: String,
        images: List<NoteImage>,
        onText: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val anthropic = settings.provider == AiProvider.ANTHROPIC
        val url = if (anthropic) "$ANTHROPIC_BASE/messages" else "$OPENAI_BASE/chat/completions"
        val connection = open(url, settings.provider, settings.apiKey, post = true)

        // A blocking read does not notice coroutine cancellation, so Stop closes the
        // socket out from under it instead — the read then throws and unwinds.
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion {
            runCatching { connection.disconnect() }
        }

        try {
            val payload = if (anthropic) {
                anthropicBody(settings.model, system, prompt, images)
            } else {
                openaiBody(settings.model, system, prompt, images)
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }

            if (connection.responseCode >= 400) throw errorFor(connection)

            connection.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    ensureActive()
                    if (!line.startsWith("data:")) continue
                    val chunk = line.removePrefix("data:").trim()
                    if (chunk.isEmpty() || chunk == "[DONE]") continue
                    deltaText(chunk, anthropic)?.let(onText)
                }
            }
        } catch (e: IOException) {
            // Stop disconnects mid-read, which surfaces as an IO failure rather than
            // a cancellation. Report it as the cancellation it actually was.
            ensureActive()
            throw e
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    private fun open(
        url: String,
        provider: AiProvider,
        apiKey: String,
        post: Boolean,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            if (post) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            if (provider == AiProvider.ANTHROPIC) {
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

    private fun errorFor(connection: HttpURLConnection): AiException =
        AiException(
            status = connection.responseCode,
            body = connection.errorStream?.bufferedReader()?.readText().orEmpty(),
        )

    /** Pulls the text out of one SSE frame, or null for the frames that carry none. */
    private fun deltaText(chunk: String, anthropic: Boolean): String? = runCatching {
        val json = JSONObject(chunk)
        if (anthropic) {
            if (json.optString("type") != "content_block_delta") return null
            json.optJSONObject("delta")?.optString("text")?.takeIf { it.isNotEmpty() }
        } else {
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optString("content")
                ?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    /** Introduces the attachments so the model knows they belong to the note. */
    private fun imageIntro(images: List<NoteImage>): String =
        if (images.size == 1) {
            "The note contains this image. Take what it shows into account."
        } else {
            "The note contains these ${images.size} images, in the order they appear. " +
                "Take what they show into account."
        }

    /** Labels each image with its alt text, so the answer can refer to it by name. */
    private fun imageLabel(image: NoteImage, index: Int): String =
        if (image.alt.isNotEmpty()) "Image ${index + 1} — \"${image.alt}\":" else "Image ${index + 1}:"

    private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)

    /**
     * Both APIs fetch an image URL from their own servers, which is why the note's
     * Firebase Storage links are passed through as-is rather than downloaded and
     * re-encoded here. Re-encoding would push megabytes of base64 through the
     * phone's memory and over its connection twice, for nothing.
     */
    private fun anthropicBody(
        model: String,
        system: String,
        prompt: String,
        images: List<NoteImage>,
    ): JSONObject {
        val content = JSONArray()
        if (images.isNotEmpty()) {
            content.put(textBlock(imageIntro(images)))
            images.forEachIndexed { i, image ->
                content.put(textBlock(imageLabel(image, i)))
                content.put(
                    JSONObject()
                        .put("type", "image")
                        .put("source", JSONObject().put("type", "url").put("url", image.url)),
                )
            }
        }
        content.put(textBlock(prompt))

        return JSONObject()
            .put("model", model)
            .put("max_tokens", 16000)
            .put("system", system)
            .put("stream", true)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
    }

    private fun openaiBody(
        model: String,
        system: String,
        prompt: String,
        images: List<NoteImage>,
    ): JSONObject {
        val userContent: Any = if (images.isEmpty()) {
            prompt
        } else {
            JSONArray().apply {
                put(textBlock(imageIntro(images)))
                images.forEachIndexed { i, image ->
                    put(textBlock(imageLabel(image, i)))
                    put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", image.url)),
                    )
                }
                put(textBlock(prompt))
            }
        }

        return JSONObject()
            .put("model", model)
            .put("stream", true)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", userContent)),
            )
    }
}

/** Vendor errors are wordy and inconsistent; reduce them to something actionable. */
fun aiErrorMessage(e: Throwable): String = when {
    e is CancellationException -> ""
    e is AiException -> when (e.status) {
        401 -> "That API key was rejected. Check it in Settings."
        403 -> "That key doesn't have access to this model."
        404 -> "That model does not exist for this key. Pick another in Settings."
        429 -> "Rate limited or out of credit. Wait a moment, or check your billing."
        // The usual cause of a 400 is an image the provider could not fetch or
        // decode, and the fix is one tick-box away.
        400 -> if (Regex("image|media|url|unsupported", RegexOption.IGNORE_CASE)
                .containsMatchIn(e.body)
        ) {
            "The model couldn't read one of the note's images. Turn off \"Show the images\" and try again."
        } else {
            vendorMessage(e.body) ?: "That request was rejected."
        }
        in 500..599 -> "The provider is having trouble. Try again."
        else -> vendorMessage(e.body) ?: "Something went wrong."
    }
    e is IOException -> "Couldn't reach the provider. Check your connection, then try again."
    else -> e.message ?: "Something went wrong."
}

/** Both vendors nest the human-readable part at error.message. */
private fun vendorMessage(body: String): String? = runCatching {
    JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
}.getOrNull()
