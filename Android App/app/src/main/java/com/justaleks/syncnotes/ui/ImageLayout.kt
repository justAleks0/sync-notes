package com.justaleks.syncnotes.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer

enum class ImageAlign { LEFT, CENTER, RIGHT }

data class ImageLayout(val width: Int?, val align: ImageAlign)

private const val MIN_WIDTH = 60
private const val MAX_WIDTH = 2000

/**
 * Layout lives in the URL fragment — `…/image.png#w=420&align=center` — written by
 * the resize and alignment controls on web. Fragments are never sent to a server, so
 * the link still loads anywhere; this just reads back what the web editor recorded.
 *
 * Must stay in step with Website/src/imageMeta.ts.
 */
fun parseImageLink(link: String): Pair<String, ImageLayout> {
    val hash = link.indexOf('#')
    if (hash == -1) return link to ImageLayout(null, ImageAlign.LEFT)

    val url = link.substring(0, hash)
    val params = link.substring(hash + 1)
        .split('&')
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
        }
        .toMap()

    val width = params["w"]?.toIntOrNull()?.coerceIn(MIN_WIDTH, MAX_WIDTH)
    val align = when (params["align"]) {
        "center" -> ImageAlign.CENTER
        "right" -> ImageAlign.RIGHT
        else -> ImageAlign.LEFT
    }
    return url to ImageLayout(width, align)
}

/**
 * Wraps Coil's transformer so images honour the width and alignment set on web.
 * Without this the phone would render every image full-width, disagreeing with the
 * desktop view of the same note.
 */
object LayoutAwareImageTransformer : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        val (url, layout) = parseImageLink(link)
        val base = Coil3ImageTransformerImpl.transform(url)

        return base.copy(
            modifier = if (layout.width != null) {
                base.modifier.width(layout.width.dp)
            } else {
                base.modifier
            },
            alignment = when (layout.align) {
                ImageAlign.CENTER -> Alignment.Center
                ImageAlign.RIGHT -> Alignment.CenterEnd
                ImageAlign.LEFT -> Alignment.CenterStart
            },
        )
    }
}
