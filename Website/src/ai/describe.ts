import type { SuggestedEdit } from './edits'
import { imageSpans, type NoteImage } from './vision'

/**
 * Alt text the model wrote for one of the attached images, keyed by the number
 * it was labelled with in the request.
 */
export type ImageDescription = { image: number; alt: string }

/**
 * The note itself is part of this prompt, not just the pictures.
 *
 * A picture of someone is described far better by a reader who knows the note
 * calls her Itherial, a half-astral-elf cleric of Selûne, than by one who can
 * only say "a woman with horns". The note is where those names live, so it goes
 * in — with the caveat that it is context for naming what is visible, never a
 * licence to describe things the picture does not show.
 */
export function buildDescribePrompt(note: string): string {
  return [
    'Write alt text for the images attached above.',
    '',
    'Reply with ONLY a JSON array and nothing else — no prose, no code fence:',
    '[{"image": 1, "alt": "..."}]',
    '',
    '- "image" is the number the image was labelled with above.',
    '- "alt" says what the image actually shows, for someone who cannot see it:',
    '  the subject, what it is doing, the setting, and any text visible in it.',
    '- One or two sentences at most. Present tense, plain description, and no',
    '  "image of" or "screenshot of" preamble — that is already implied.',
    '- Leave out an image you cannot make out, rather than guessing at it.',
    '',
    'The note these images sit in follows. Read it first and use its own words:',
    'if it names the character, place or thing in a picture, or gives their',
    'species, role, or the scene they belong to, say that instead of describing',
    'them generically — "Itherial in her gold and ivory armour" beats "a woman in',
    'armour". Only name something when the picture plainly shows it; the note is',
    'there to tell you what you are looking at, not to be described in its place.',
    '',
    '---',
    note,
  ].join('\n')
}

/** Escapes the characters that would break out of the `![…]` label. */
const safeAlt = (text: string) => text.replace(/[[\]\n]/g, ' ').replace(/\s+/g, ' ').trim()

export function parseDescriptions(raw: string): ImageDescription[] | null {
  const start = raw.indexOf('[')
  const end = raw.lastIndexOf(']')
  if (start === -1 || end <= start) return null

  let parsed: unknown
  try {
    parsed = JSON.parse(raw.slice(start, end + 1))
  } catch {
    return null
  }
  if (!Array.isArray(parsed)) return null

  const out: ImageDescription[] = []
  for (const item of parsed) {
    if (typeof item !== 'object' || item === null) continue
    const { image, alt } = item as Record<string, unknown>
    if (typeof image !== 'number' || typeof alt !== 'string') continue
    if (!safeAlt(alt)) continue
    out.push({ image, alt: safeAlt(alt) })
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
export function describeEdits(
  source: string,
  attached: NoteImage[],
  descriptions: ImageDescription[],
): SuggestedEdit[] {
  const edits: SuggestedEdit[] = []

  const spans = imageSpans(source)

  for (const { image, alt } of descriptions) {
    const target = attached[image - 1]
    if (!target) continue

    const here = spans.filter((s) => s.url === target.url && s.alt.trim() !== alt)

    here.forEach((span, i) => {
      // Only the label changes: the src comes back from the note verbatim, so
      // the URL and its layout fragment survive exactly as they were.
      const replacement = span.raw.replace(/^!\[[^\]]*\]/, `![${alt}]`)
      if (replacement === span.raw) return

      const named = span.alt.trim()
        ? `Describe "${span.alt.trim()}"`
        : 'Add a description to this image'
      // The same picture can sit in a note twice, and two identical-looking rows
      // would read as a duplicate rather than as two places to change.
      const where = here.length > 1 ? ` — ${i + 1} of ${here.length} places it appears` : ''

      edits.push({
        id: `${span.start}`,
        find: span.raw,
        replace: replacement,
        why: named + where,
      })
    })
  }

  return edits
}
