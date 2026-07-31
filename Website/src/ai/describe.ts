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
    '- "image" is the number the image was labelled with above. The text quoted',
    '  beside that number is the alt text the image has now, which is often just',
    '  a filename and worth replacing — but see the point about good ones below.',
    '- "alt" says what the image actually shows, for someone who cannot see it:',
    '  the subject, what it is doing, the setting, and any text visible in it.',
    '- One or two sentences at most. Present tense, plain description, and no',
    '  "image of" or "screenshot of" preamble — that is already implied.',
    '- Leave out an image you cannot make out, rather than guessing at it.',
    '',
    'Write the set as a set, not one at a time. They will be read together, one',
    'after another in the same note, so:',
    '',
    '- No two should read alike. Where several show the same subject, establish',
    '  who or what it is once and then give each image what is different about',
    '  it — the pose, the angle, the setting, the moment, what has changed.',
    '- Do not repeat the same shared detail in every description. If every',
    '  picture has the same armour and the same hair, that belongs in one of',
    '  them, not in all of them.',
    '- Some images may already have a real description rather than a filename.',
    '  Read those too: do not restate what they already say, and leave an image',
    '  out of your reply entirely if its existing description is already good.',
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

/** Content words only — the shared scaffolding of a sentence proves nothing. */
const contentWords = (text: string) =>
  new Set(
    text
      .toLowerCase()
      .replace(/[^a-z0-9\s]/g, ' ')
      .split(/\s+/)
      .filter((w) => w.length > 3),
  )

/**
 * How much of the shorter description is contained in the longer one.
 *
 * Containment rather than symmetric overlap: "Itherial in gold armour" sitting
 * inside "Itherial in gold armour, standing in a forest" is exactly the
 * repetition worth catching, and a symmetric measure would score that pair as
 * only half-alike and let it through.
 */
function sameness(a: string, b: string): number {
  const left = contentWords(a)
  const right = contentWords(b)
  if (left.size === 0 || right.size === 0) return 0

  let shared = 0
  for (const word of left) if (right.has(word)) shared++
  return shared / Math.min(left.size, right.size)
}

/** Past this, two descriptions are saying the same thing in different words. */
const TOO_ALIKE = 0.8

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

  // The prompt asks for descriptions that differ from one another; this is what
  // notices when they do not. A model repeating itself across five pictures of
  // the same character is the failure this feature invites, and a note on the
  // row beats the user finding five near-identical captions afterwards.
  const alike = new Map<number, string>()
  descriptions.forEach(({ image, alt }, i) => {
    const clash = descriptions
      .slice(0, i)
      .find((other) => sameness(alt, other.alt) >= TOO_ALIKE)
    if (clash) {
      alike.set(image, `reads much like image ${clash.image}'s`)
      return
    }

    // And against descriptions the note already carries on its other images.
    const existing = spans.find(
      (s) =>
        s.url !== attached[image - 1]?.url &&
        s.alt.trim().length > 0 &&
        sameness(alt, s.alt) >= TOO_ALIKE,
    )
    if (existing) alike.set(image, 'repeats a description already in the note')
  })

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
      const repeated = alike.get(image)

      edits.push({
        id: `${span.start}`,
        find: span.raw,
        replace: replacement,
        why: named + where + (repeated ? ` — ${repeated}` : ''),
      })
    })
  }

  return edits
}
