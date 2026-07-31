package com.justaleks.syncnotes.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the assistant that can be checked without a phone or an API key.
 * Everything here has a counterpart in the web app, so a divergence between the
 * two clients shows up as a failure rather than as a subtly different answer.
 */
class AiLogicTest {

    private val note = """
        # Trip

        ![Map of the route](https://example.com/map.png?token=abc#w=420&align=right&wrap=1)

        Some text.

        ![](<https://example.com/a b.png#w=100>)

        ![dupe](https://example.com/map.png?token=abc)

        ![local](content://media/external/images/1)

        ![titled](https://example.com/t.png "a title")

        Not an image: [link](https://example.com/page)
    """.trimIndent()

    @Test
    fun `extracts images in order, without the layout fragment`() {
        val images = extractImages(note)

        assertEquals(
            listOf(
                NoteImage("https://example.com/map.png?token=abc", "Map of the route"),
                NoteImage("https://example.com/a b.png", ""),
                NoteImage("https://example.com/t.png", "titled"),
            ),
            images,
        )
    }

    @Test
    fun `ignores non-http sources, duplicates and plain links`() {
        val urls = extractImages(note).map { it.url }

        assertFalse(urls.any { it.startsWith("content://") })
        assertEquals(urls.size, urls.distinct().size)
        assertFalse(urls.contains("https://example.com/page"))
    }

    @Test
    fun `a note with no images attaches nothing`() {
        assertTrue(extractImages("just words, and a stray ![broken](  ").isEmpty())
    }

    @Test
    fun `model filter keeps chat models and drops the rest`() {
        assertTrue(isUsableChatModel(AiProvider.ANTHROPIC, "claude-sonnet-5"))
        assertFalse(isUsableChatModel(AiProvider.ANTHROPIC, "claude-3-5-sonnet-20241022"))

        assertTrue(isUsableChatModel(AiProvider.OPENAI, "gpt-5.4-mini"))
        assertFalse(isUsableChatModel(AiProvider.OPENAI, "text-embedding-3-large"))
        assertFalse(isUsableChatModel(AiProvider.OPENAI, "gpt-4o-audio-preview"))
        assertFalse(isUsableChatModel(AiProvider.OPENAI, "gpt-3.5-turbo"))
        assertFalse(isUsableChatModel(AiProvider.OPENAI, "dall-e-3"))
    }

    @Test
    fun `vision support matches what the provider actually accepts`() {
        assertTrue(supportsVision(AiProvider.ANTHROPIC, "claude-haiku-4-5"))
        assertTrue(supportsVision(AiProvider.OPENAI, "gpt-5.4-mini"))
        assertTrue(supportsVision(AiProvider.OPENAI, "gpt-4o"))
        assertTrue(supportsVision(AiProvider.OPENAI, "o3"))
        assertFalse(supportsVision(AiProvider.OPENAI, "gpt-3.5-turbo"))
    }

    @Test
    fun `default model is the balanced pick, not the flagship`() {
        val available = listOf("claude-haiku-4-5", "claude-opus-5", "claude-sonnet-5")
        assertEquals("claude-sonnet-5", defaultModel(AiProvider.ANTHROPIC, available))
    }

    @Test
    fun `default model falls back when the recommended ones are missing`() {
        assertEquals(
            "gpt-9-something",
            defaultModel(AiProvider.OPENAI, listOf("gpt-9-something")),
        )
        assertEquals("", defaultModel(AiProvider.OPENAI, emptyList()))
    }
}
