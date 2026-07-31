package com.justaleks.syncnotes.ai

import org.json.JSONArray

/**
 * Alt text the model wrote for one of the attached images, keyed by the number
 * it was labelled with in the request.
 */
data class ImageDescription(val image: Int, val alt: String)

/**
 * The note itself is part of this prompt, not just the pictures.
 *
 * A picture of someone is described far better by a reader who knows the note
 * calls her Itherial, a half-astral-elf cleric of Selûne, than by one who can
 * only say "a woman with horns". The note is where those names live, so it goes
 * in — with the caveat that it is context for naming what is visible, never a
 * licence to describe things the picture does not show.
 */
fun buildDescribePrompt(note: String): String = listOf(
    "Write alt text for the images attached above.",
    "",
    "Reply with ONLY a JSON array and nothing else — no prose, no code fence:",
    "[{\"image\": 1, \"alt\": \"...\"}]",
    "",
    "- \"image\" is the number the image was labelled with above. The text quoted",
    "  beside that number is the alt text the image has now, which is often just",
    "  a filename and worth replacing — but see the point about good ones below.",
    "- \"alt\" says what the image actually shows, for someone who cannot see it:",
    "  the subject, what it is doing, the setting, and any text visible in it.",
    "- One or two sentences at most. Present tense, plain description, and no",
    "  \"image of\" or \"screenshot of\" preamble — that is already implied.",
    "- Leave out an image you cannot make out, rather than guessing at it.",
    "",
    "Write the set as a set, not one at a time. They will be read together, one",
    "after another in the same note, so:",
    "",
    "- No two should read alike. Where several show the same subject, establish",
    "  who or what it is once and then give each image what is different about",
    "  it — the pose, the angle, the setting, the moment, what has changed.",
    "- Do not repeat the same shared detail in every description. If every",
    "  picture has the same armour and the same hair, that belongs in one of",
    "  them, not in all of them.",
    "- Some images may already have a real description rather than a filename.",
    "  Read those too: do not restate what they already say, and leave an image",
    "  out of your reply entirely if its existing description is already good.",
    "",
    "The note these images sit in follows. Read it first and use its own words:",
    "if it names the character, place or thing in a picture, or gives their",
    "species, role, or the scene they belong to, say that instead of describing",
    "them generically — \"Itherial in her gold and ivory armour\" beats \"a woman",
    "in armour\". Only name something when the picture plainly shows it; the note",
    "is there to tell you what you are looking at, not to be described in its place.",
    "",
    "---",
    note,
).joinToString("\n")

/** Content words only — the shared scaffolding of a sentence proves nothing. */
private fun contentWords(text: String): Set<String> =
    text.lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length > 3 }
        .toSet()

/**
 * How much of the shorter description is contained in the longer one.
 *
 * Containment rather than symmetric overlap: "Itherial in gold armour" sitting
 * inside "Itherial in gold armour, standing in a forest" is exactly the
 * repetition worth catching, and a symmetric measure would score that pair as
 * only half-alike and let it through.
 */
private fun sameness(a: String, b: String): Double {
    val left = contentWords(a)
    val right = contentWords(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0

    val shared = left.count { it in right }
    return shared.toDouble() / minOf(left.size, right.size)
}

/** Past this, two descriptions are saying the same thing in different words. */
private const val TOO_ALIKE = 0.8

/** Escapes the characters that would break out of the `![…]` label. */
private fun safeAlt(text: String) =
    text.replace(Regex("""[\[\]\n]"""), " ").replace(Regex("""\s+"""), " ").trim()

fun parseDescriptions(raw: String): List<ImageDescription>? {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start == -1 || end <= start) return null

    val array = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull() ?: return null

    val out = mutableListOf<ImageDescription>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        if (!item.has("image")) continue
        val alt = safeAlt(item.optString("alt"))
        if (alt.isEmpty()) continue
        out += ImageDescription(item.optInt("image"), alt)
    }
    return out
}

/**
 * Turns the model's descriptions into ordinary suggested edits, so they arrive
 * in the same tick-box list as everything else and can be taken one at a time.
 *
 * [attached] is the deduplicated list that was sent, so image 1 means
 * attached[0]. A picture used twice in a note gets one description and both
 * copies offered — they are the same picture, and disagreeing alt text on them
 * would be worse than repeating it.
 */
fun describeEdits(
    source: String,
    attached: List<NoteImage>,
    descriptions: List<ImageDescription>,
): List<SuggestedEdit> {
    val edits = mutableListOf<SuggestedEdit>()
    val spans = imageSpans(source)

    // The prompt asks for descriptions that differ from one another; this is what
    // notices when they do not. A model repeating itself across five pictures of
    // the same character is the failure this feature invites, and a note on the
    // row beats the user finding five near-identical captions afterwards.
    val alike = mutableMapOf<Int, String>()
    descriptions.forEachIndexed { i, (image, alt) ->
        val clash = descriptions.take(i).firstOrNull { sameness(alt, it.alt) >= TOO_ALIKE }
        if (clash != null) {
            alike[image] = "reads much like image ${clash.image}'s"
            return@forEachIndexed
        }

        // And against descriptions the note already carries on its other images.
        val existing = spans.firstOrNull {
            it.url != attached.getOrNull(image - 1)?.url &&
                it.alt.isNotBlank() &&
                sameness(alt, it.alt) >= TOO_ALIKE
        }
        if (existing != null) alike[image] = "repeats a description already in the note"
    }

    for ((image, alt) in descriptions) {
        val target = attached.getOrNull(image - 1) ?: continue
        val here = spans.filter { it.url == target.url && it.alt.trim() != alt }

        here.forEachIndexed { i, span ->
            // Only the label changes: the src comes back from the note verbatim,
            // so the URL and its layout fragment survive exactly as they were.
            val replacement = span.raw.replaceFirst(Regex("""^!\[[^\]]*]"""), "![$alt]")
            if (replacement == span.raw) return@forEachIndexed

            val named = if (span.alt.isNotBlank()) "Describe \"${span.alt.trim()}\""
            else "Add a description to this image"
            // The same picture can sit in a note twice, and two identical-looking
            // rows would read as a duplicate rather than as two places to change.
            val where = if (here.size > 1) " — ${i + 1} of ${here.size} places it appears" else ""
            val repeated = alike[image]?.let { " — $it" }.orEmpty()

            edits += SuggestedEdit(
                id = span.start.toString(),
                find = span.raw,
                replace = replacement,
                why = named + where + repeated,
            )
        }
    }

    return edits
}
