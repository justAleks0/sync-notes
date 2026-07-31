package com.justaleks.syncnotes.ai

import org.json.JSONArray

data class SuggestedEdit(
    /** Stable key for the tick-box list. */
    val id: String,
    /** Text copied verbatim from the note. */
    val find: String,
    /** What it should say instead. Empty means delete. */
    val replace: String,
    /** One short sentence, shown next to the tick-box. */
    val why: String,
)

/**
 * Whether an edit can still be applied to the text in front of us.
 *
 * The model quotes the note back to us, and quoting is exactly the thing it is
 * least reliable at — a smart quote turned straight, a trimmed space, a word
 * remembered rather than copied. So every suggestion is checked against the real
 * text before it is offered, and one that no longer matches is shown as
 * unavailable rather than silently dropped or, worse, applied to the wrong place.
 */
enum class EditStatus { READY, AMBIGUOUS, MISSING }

fun statusOf(source: String, edit: SuggestedEdit): EditStatus {
    if (edit.find.isEmpty()) return EditStatus.MISSING
    val first = source.indexOf(edit.find)
    if (first == -1) return EditStatus.MISSING
    return if (source.indexOf(edit.find, first + 1) == -1) EditStatus.READY
    else EditStatus.AMBIGUOUS
}

fun buildEditPrompt(text: String): String = listOf(
    "Review the following note and list the specific edits worth making:",
    "spelling, grammar, wording, clarity, contradictions.",
    "",
    "Reply with ONLY a JSON array and nothing else — no prose, no code fence.",
    "Each element is {\"find\": \"...\", \"replace\": \"...\", \"why\": \"...\"} where:",
    "",
    "- \"find\" is text copied from the note exactly, character for character,",
    "  including its punctuation and capitalisation. Choose the shortest snippet",
    "  that appears exactly once in the note.",
    "- \"replace\" is what that text should say instead. Use an empty string to delete it.",
    "- \"why\" is at most one short sentence in plain language.",
    "",
    "Do not restructure the note, invent facts, or alter image links or URLs.",
    "At most 12 edits, most important first. If nothing needs changing, reply with [].",
    "",
    "---",
    text,
).joinToString("\n")

/**
 * Pulls the edit list out of a model reply.
 *
 * Tolerant on purpose: models wrap JSON in code fences or add a line of
 * introduction however firmly they are asked not to, and that is not a good
 * enough reason to show the user a failure.
 */
fun parseEdits(raw: String): List<SuggestedEdit>? {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start == -1 || end <= start) return null

    val array = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull() ?: return null

    val edits = mutableListOf<SuggestedEdit>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val find = item.optString("find")
        val replace = item.optString("replace")
        if (find.isEmpty() || find == replace) continue

        val why = item.optString("why").trim()
        edits += SuggestedEdit(
            id = i.toString(),
            find = find,
            replace = replace,
            why = why.ifEmpty { "Suggested change." },
        )
    }
    return edits
}

/**
 * Applies the chosen edits to [source], in order.
 *
 * Each replacement is done against the text as it stands rather than against
 * precomputed offsets, because an earlier edit shifts everything after it. An
 * edit whose text has been consumed by an earlier one is skipped instead of
 * being forced through somewhere it does not belong.
 */
fun applyEdits(source: String, edits: List<SuggestedEdit>): String {
    var text = source
    for (edit in edits) {
        val at = text.indexOf(edit.find)
        if (at == -1) continue
        text = text.substring(0, at) + edit.replace + text.substring(at + edit.find.length)
    }
    return text
}
