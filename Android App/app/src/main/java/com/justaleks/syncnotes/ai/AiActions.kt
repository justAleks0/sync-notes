package com.justaleks.syncnotes.ai

/** How a result is offered back — replacing the source, or added after it. */
enum class AiResult { REPLACE, APPEND }

data class AiAction(
    val id: String,
    val label: String,
    val hint: String,
    val result: AiResult,
    val build: (text: String, title: String) -> String,
)

val SYSTEM_PROMPT = listOf(
    "You are a writing assistant inside a notes app.",
    "The note is markdown. Preserve the existing markdown structure and any image links exactly as written — never alter a URL.",
    "When images are attached they are the ones embedded in the note, given in order and labelled with their alt text. Read them as part of the note, not as a separate question, and keep referring to them by that alt text. You may edit an image's alt text if it is wrong or missing, but never its URL and never its position in the text.",
    "Return only the requested content. No preamble, no commentary, no code fences around the whole answer.",
    "Match the existing voice and level of detail. If the note is terse notes-to-self, stay terse.",
).joinToString(" ")

val ACTIONS: List<AiAction> = listOf(
    AiAction(
        id = "improve",
        label = "Improve writing",
        hint = "Tighten and clarify without changing meaning",
        result = AiResult.REPLACE,
        build = { text, _ ->
            "Rewrite the following for clarity and flow. Keep the meaning, the facts, and " +
                "roughly the same length. Do not add new information.\n\n---\n$text"
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
            "Continue the following note titled \"$title\". Write the next paragraph or " +
                "section only — do not repeat what is already there.\n\n---\n$text"
        },
    ),
    AiAction(
        id = "sections",
        label = "Add structure",
        hint = "Organise into headings and sections",
        result = AiResult.REPLACE,
        build = { text, _ ->
            "Reorganise the following note with markdown headings and sections so it is " +
                "easier to scan. Keep all the existing content — do not delete anything or " +
                "invent new facts.\n\n---\n$text"
        },
    ),
    AiAction(
        id = "suggest",
        label = "Suggest edits",
        hint = "Notes on what to change, nothing rewritten",
        result = AiResult.APPEND,
        build = { text, _ ->
            "Review the following note and suggest specific improvements as a markdown " +
                "checklist. Point at concrete problems — gaps, unclear passages, " +
                "contradictions. Do not rewrite the note.\n\n---\n$text"
        },
    ),
)

fun buildCustomPrompt(instruction: String, text: String): String =
    "${instruction.trim()}\n\nApply that to the following note content.\n\n---\n$text"
