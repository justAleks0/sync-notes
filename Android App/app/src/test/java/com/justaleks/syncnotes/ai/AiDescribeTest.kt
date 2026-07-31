package com.justaleks.syncnotes.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Describing a note's images is the one AI action whose output is built by the
 * app rather than pasted from the model, so the mapping and the repetition check
 * are ordinary code and testable without a key.
 */
class AiDescribeTest {

    private val note = listOf(
        "Name: Itherial. Half astral elf cleric of Selune.",
        "",
        "![Itherial in gold and ivory armour, standing in a forest.](https://f/o/existing.png)",
        "",
        "![IMG_001](https://f/o/a.png?alt=media&token=abc#w=240)",
        "",
        "![](https://f/o/b.png)",
    ).joinToString("\n")

    private val attached = extractImages(note)

    private fun edits(json: String) = describeEdits(note, attached, parseDescriptions(json)!!)

    @Test
    fun `descriptions replace only the alt text, leaving url and layout alone`() {
        val result = edits("""[{"image":2,"alt":"Itherial kneeling at a moonlit altar."}]""")

        assertEquals(1, result.size)
        assertEquals(
            "![Itherial kneeling at a moonlit altar.](https://f/o/a.png?alt=media&token=abc#w=240)",
            result[0].replace,
        )
    }

    @Test
    fun `an image number the note does not have is ignored`() {
        assertTrue(edits("""[{"image":9,"alt":"Nothing to attach this to."}]""").isEmpty())
    }

    @Test
    fun `an image with no alt text is offered as an addition`() {
        val result = edits("""[{"image":3,"alt":"A glowing blue crystal in a dark wood."}]""")

        assertEquals("Add a description to this image", result[0].why)
    }

    @Test
    fun `a description repeating another in the same batch is flagged`() {
        val result = edits(
            """[
              {"image":2,"alt":"Itherial kneeling at a moonlit altar, sword on her knees."},
              {"image":3,"alt":"Itherial kneeling at the moonlit altar with her sword on her knees."}
            ]"""
        )

        assertFalse(result[0].why.contains("reads much like"))
        assertTrue(result[1].why.contains("reads much like image 2's"))
    }

    @Test
    fun `a description repeating alt text already in the note is flagged`() {
        val result = edits(
            """[{"image":2,"alt":"Itherial in gold and ivory armour, standing in a forest."}]"""
        )

        assertTrue(result[0].why.contains("repeats a description already in the note"))
    }

    @Test
    fun `genuinely different scenes of the same subject are not flagged`() {
        val result = edits(
            """[
              {"image":2,"alt":"Itherial mid-swing with her sword, sparks around the blade."},
              {"image":3,"alt":"Itherial crouched beside a glowing crystal at night."}
            ]"""
        )

        assertTrue(result.none { it.why.contains("reads much like") })
        assertTrue(result.none { it.why.contains("repeats a description") })
    }
}
