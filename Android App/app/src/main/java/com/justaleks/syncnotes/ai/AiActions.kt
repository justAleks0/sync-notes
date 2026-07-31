package com.justaleks.syncnotes.ai

/**
 * How a result is offered back — replacing the source, added after it, or as a
 * list of individual edits to tick off.
 */
enum class AiResult { REPLACE, APPEND, EDITS }

data class AiAction(
    val id: String,
    val label: String,
    val hint: String,
    val result: AiResult,
    val build: (text: String, title: String) -> String,
)

val SYSTEM_PROMPT = listOf(
    "You are a writing assistant inside a notes app.",
    // The single most common complaint about this feature was the model ignoring
    // what the note already was and substituting its own idea of the note. This
    // line exists to head that off, and the individual actions repeat it in their
    // own terms rather than relying on it alone.
    "Work from what is already there. Before writing anything, take stock of the note as it stands: the headings and lists it already has, its voice, its level of detail, and what it already says. Build on that. Never replace it with your own idea of how such a note ought to look.",
    "Keep every fact, name, number and link the note already contains unless you were explicitly asked to remove it, and never invent details it does not contain.",
    "The note is markdown. Preserve the existing markdown structure and any image links exactly as written — never alter a URL.",
    "When images are attached they are the ones embedded in the note, given in order and labelled with their alt text. Read them as part of the note, not as a separate question, and keep referring to them by that alt text. You may edit an image's alt text if it is wrong or missing, but never its URL and never its position in the text.",
    "Return only the requested content. No preamble, no commentary, no code fences around the whole answer.",
    "Match the existing voice and level of detail. If the note is terse notes-to-self, stay terse.",
).joinToString(" ")

/**
 * The edit list is machine-read, so the usual "write like the note" instructions
 * are not just unnecessary here — they actively invite prose around the JSON.
 */
val EDIT_SYSTEM_PROMPT = listOf(
    "You are a copy editor working inside a notes app.",
    "You reply with JSON only. No preamble, no commentary, no code fences.",
    "The note is markdown. Never alter image links or URLs, and never restructure the note.",
    "Quote the note exactly when you cite it — the app matches your text against the real note character for character, and an inexact quote is discarded.",
).joinToString(" ")

val ACTIONS: List<AiAction> = listOf(
    AiAction(
        id = "improve",
        label = "Improve writing",
        hint = "Tighten and clarify without changing meaning",
        result = AiResult.REPLACE,
        build = { text, _ ->
            "Rewrite the note below for clarity and flow.\n\n" +
                "Keep the meaning, every fact, and roughly the same length. Keep all existing " +
                "markdown exactly where it is — headings stay headings, lists stay lists, " +
                "emphasis and links survive untouched. Do not reorganise, do not merge or " +
                "split sections, and do not add information.\n" +
                "Return the complete note, not only the parts you touched.\n\n---\n$text"
        },
    ),
    AiAction(
        id = "summarise",
        label = "Summarise",
        hint = "A short summary added at the top",
        result = AiResult.APPEND,
        build = { text, _ ->
            "Write a short summary of the following note as markdown bullet points. " +
                "Five bullets at most.\n\n---\n$text"
        },
    ),
    AiAction(
        id = "continue",
        label = "Continue writing",
        hint = "Carry on from where the note stops",
        result = AiResult.APPEND,
        build = { text, title ->
            "Continue the note below, titled \"$title\".\n\n" +
                "Read what is there first, then carry on from where it stops — in the same " +
                "voice, at the same level of detail, and under whatever structure it already " +
                "uses. If it is a bulleted list, add bullets; if it is prose, write prose.\n" +
                "Write only the new material: the next paragraph or section. Do not repeat, " +
                "summarise, or re-introduce anything already written, and do not restate the " +
                "title.\n\n---\n$text"
        },
    ),
    AiAction(
        id = "sections",
        label = "Add structure",
        hint = "Organise into headings and sections",
        result = AiResult.REPLACE,
        build = { text, _ ->
            "Improve how the note below is organised.\n\n" +
                "Start from the structure it already has. Keep every existing heading, list " +
                "and section that works, at the same level and in the same order. Add a " +
                "heading or group related lines together only where the note genuinely lacks " +
                "structure and a reader would benefit. Where a part is already well " +
                "organised, return it exactly as it is.\n\n" +
                "Do not re-order content to suit a shape you prefer, do not rename headings " +
                "that already say what they mean, do not merge distinct points, and do not " +
                "drop anything: every sentence in the note must still be present in your " +
                "answer.\nReturn the complete note.\n\n---\n$text"
        },
    ),
    AiAction(
        id = "suggest",
        label = "Suggest edits",
        hint = "Each one offered separately — take the ones you want",
        result = AiResult.EDITS,
        build = { text, _ -> buildEditPrompt(text) },
    ),
)

/**
 * A free-form instruction. The result replaces the whole note, so the model has to
 * be told to return the whole note — left ambiguous, it tends to reply with only
 * the part it rewrote, which then silently swallows everything else.
 */
fun buildCustomPrompt(instruction: String, text: String): String =
    "${instruction.trim()}\n\n" +
        "Apply that to the note below, and only that. Everything the instruction does not " +
        "ask you to change must come back exactly as it is — same wording, same headings, " +
        "same lists, same links.\n" +
        "Return the complete note, not just the part you changed.\n\n---\n$text"
