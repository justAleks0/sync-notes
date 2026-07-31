package com.justaleks.syncnotes.ai

data class Recommendation(
    val id: String,
    val label: String,
    /** Why this one, for this app specifically. */
    val note: String,
    val inputPerM: Double,
    val outputPerM: Double,
)

/**
 * The same curated list the web editor offers, for the same reason: this app
 * rewrites, summarises, continues and restructures a single note. Short input,
 * short output, and a person waiting on the result — so latency matters and deep
 * reasoning does not.
 *
 * Frontier and "pro"/reasoning models are deliberately absent. They remain
 * selectable under "All chat models".
 *
 * Prices are USD per 1M tokens, correct as of July 2026 — indicative only.
 */
val RECOMMENDED: Map<AiProvider, List<Recommendation>> = mapOf(
    AiProvider.ANTHROPIC to listOf(
        Recommendation(
            id = "claude-haiku-4-5",
            label = "Haiku 4.5 — fastest, cheapest",
            note = "Ample for summarising and tidying. Noticeably the quickest to respond.",
            inputPerM = 1.0,
            outputPerM = 5.0,
        ),
        Recommendation(
            id = "claude-sonnet-5",
            label = "Sonnet 5 — balanced (recommended)",
            note = "Best prose quality per unit of latency. The default.",
            inputPerM = 3.0,
            outputPerM = 15.0,
        ),
        Recommendation(
            id = "claude-opus-5",
            label = "Opus 5 — highest quality",
            note = "Worth it for restructuring a long note; overkill for a one-line rewrite.",
            inputPerM = 5.0,
            outputPerM = 25.0,
        ),
    ),
    AiProvider.OPENAI to listOf(
        Recommendation(
            id = "gpt-5.4-nano",
            label = "GPT-5.4 nano — fastest, cheapest",
            note = "Fine for summaries and light edits.",
            inputPerM = 0.2,
            outputPerM = 1.25,
        ),
        Recommendation(
            id = "gpt-5.4-mini",
            label = "GPT-5.4 mini — balanced (recommended)",
            note = "The sensible default: good writing, still fast.",
            inputPerM = 0.75,
            outputPerM = 4.5,
        ),
        Recommendation(
            id = "gpt-5.4",
            label = "GPT-5.4 — highest quality",
            note = "Better at restructuring and critique; slower and ~6x the cost of mini.",
            inputPerM = 2.5,
            outputPerM = 15.0,
        ),
    ),
)

/**
 * Models that exist on the account but cannot answer a chat request, plus ones
 * OpenAI has deprecated. Without this the picker showed over a hundred entries,
 * most of them audio, image, or embedding models that would simply error.
 */
private val NOT_CHAT = Regex(
    "audio|realtime|transcribe|tts|whisper|image|dall-e|embedding|moderation|" +
        "search|instruct|computer-use|deep-research|codex",
    RegexOption.IGNORE_CASE,
)

private val DEPRECATED = Regex(
    """^(gpt-3|gpt-4($|-turbo|-\d)|gpt-4o$|gpt-4\.5|gpt-4\.1-nano|o1|o3-mini|o4-mini)|chat-latest$|preview""",
    RegexOption.IGNORE_CASE,
)

/** Dated snapshots duplicate their alias and just make the list longer. */
private val SNAPSHOT = Regex("""-\d{8}$|-\d{4}-\d{2}-\d{2}$""")

private val MODERN_OPENAI = Regex("""^(gpt|o\d)""", RegexOption.IGNORE_CASE)

fun isUsableChatModel(provider: AiProvider, id: String): Boolean {
    if (SNAPSHOT.containsMatchIn(id)) return false
    if (provider == AiProvider.ANTHROPIC) return id.startsWith("claude-")
    if (NOT_CHAT.containsMatchIn(id)) return false
    if (DEPRECATED.containsMatchIn(id)) return false
    return MODERN_OPENAI.containsMatchIn(id)
}

private val OPENAI_VISION = Regex("""^(gpt-4o|gpt-4\.1|gpt-5|o[34])""", RegexOption.IGNORE_CASE)

/**
 * Whether the model can be shown the images in a note.
 *
 * Worth checking rather than always attaching: a text-only model rejects the
 * whole request when an image arrives, so the note's words would fail to go
 * through as well.
 */
fun supportsVision(provider: AiProvider, id: String): Boolean =
    if (provider == AiProvider.ANTHROPIC) id.startsWith("claude-")
    else OPENAI_VISION.containsMatchIn(id)

/** The balanced pick — deliberately not the flagship. */
fun defaultModel(provider: AiProvider, available: List<String>): String {
    val recommended = RECOMMENDED.getValue(provider)
    val balanced = recommended.getOrNull(1) ?: recommended.first()

    for (candidate in listOf(balanced) + recommended) {
        if (available.contains(candidate.id)) return candidate.id
    }
    return available.firstOrNull().orEmpty()
}

fun findRecommendation(provider: AiProvider, id: String): Recommendation? =
    RECOMMENDED.getValue(provider).firstOrNull { it.id == id }

/**
 * A rough monthly figure for realistic use, because per-million-token prices are
 * impossible to intuit. Assumes ~50 actions a month at ~1500 tokens in and ~500
 * out — which is why the honest answer here is that price barely matters.
 */
fun monthlyEstimate(r: Recommendation): String {
    val cost = 0.075 * r.inputPerM + 0.025 * r.outputPerM
    return if (cost < 0.1) "under 10¢/month" else "about $%.2f/month".format(cost)
}
