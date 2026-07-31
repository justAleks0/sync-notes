import { parseImageSrc } from '../imageMeta'

export type NoteImage = {
  /** The bare URL, with any layout fragment stripped off. */
  url: string
  /** The markdown alt text, used to label the image for the model. */
  alt: string
}

/**
 * A note can hold dozens of images. Every one of them costs tokens and adds
 * latency, and past a handful they stop informing the answer — so send the first
 * few and tell the user that is what happened.
 */
export const MAX_IMAGES = 8

/**
 * Matches `![alt](src)`, including the `<…>` form used when the URL contains
 * spaces or brackets, and the optional `"title"` after the URL.
 */
const IMAGE_MD = /!\[([^\]]*)\]\(\s*(<[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\s*\)/g

/**
 * Pulls the images out of a chunk of note markdown, in the order they appear.
 *
 * Only http(s) survives: the providers fetch these URLs from their own servers,
 * so a `blob:` or `data:` src would be either meaningless to them or enormous.
 * In this app every stored image is a Firebase Storage download URL, which is
 * exactly what that fetch needs.
 */
export function extractImages(markdown: string): NoteImage[] {
  const found: NoteImage[] = []
  const seen = new Set<string>()

  for (const match of markdown.matchAll(IMAGE_MD)) {
    const raw = match[2].startsWith('<') ? match[2].slice(1, -1) : match[2]
    // The layout fragment (#w=420&align=…) is ours, not part of the image.
    const { url } = parseImageSrc(raw)

    if (!/^https?:\/\//i.test(url)) continue
    if (seen.has(url)) continue

    seen.add(url)
    found.push({ url, alt: match[1].trim() })
  }

  return found
}
