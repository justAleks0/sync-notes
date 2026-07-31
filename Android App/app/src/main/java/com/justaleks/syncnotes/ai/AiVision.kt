package com.justaleks.syncnotes.ai

data class NoteImage(
    /** The bare URL, with any layout fragment stripped off. */
    val url: String,
    /** The markdown alt text, used to label the image for the model. */
    val alt: String,
)

/**
 * A note can hold dozens of images. Every one of them costs tokens and adds
 * latency, and past a handful they stop informing the answer — so send the first
 * few and tell the user that is what happened.
 */
const val MAX_IMAGES = 8

/**
 * Matches `![alt](src)`, including the `<…>` form used when the URL contains
 * spaces or brackets, and the optional `"title"` after the URL.
 */
private val IMAGE_MD = Regex("""!\[([^\]]*)]\(\s*(<[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\s*\)""")

/**
 * Pulls the images out of a chunk of note markdown, in the order they appear.
 *
 * Only http(s) survives: the providers fetch these URLs from their own servers,
 * so a `content://` or `file://` src would be meaningless to them. Every stored
 * image here is a Firebase Storage download URL, which is what that fetch needs.
 */
fun extractImages(markdown: String): List<NoteImage> {
    val found = mutableListOf<NoteImage>()
    val seen = mutableSetOf<String>()

    for (match in IMAGE_MD.findAll(markdown)) {
        val raw = match.groupValues[2].let {
            if (it.startsWith("<")) it.substring(1, it.length - 1) else it
        }
        // The layout fragment (#w=420&align=…) is ours, not part of the image.
        val url = raw.substringBefore('#')

        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
        if (!seen.add(url)) continue

        found += NoteImage(url = url, alt = match.groupValues[1].trim())
    }

    return found
}
