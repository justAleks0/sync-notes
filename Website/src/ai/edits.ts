export type SuggestedEdit = {
  /** Stable key for the checkbox list. */
  id: string
  /** Text copied verbatim from the note. */
  find: string
  /** What it should say instead. Empty means delete. */
  replace: string
  /** One short sentence, shown next to the tick-box. */
  why: string
}

/**
 * Whether an edit can still be applied to the text in front of us.
 *
 * The model quotes the note back to us, and quoting is exactly the thing it is
 * least reliable at — a smart quote turned straight, a trimmed space, a word
 * remembered rather than copied. So every suggestion is checked against the real
 * text before it is offered, and one that no longer matches is shown as
 * unavailable rather than silently dropped or, worse, applied to the wrong place.
 */
export type EditStatus = 'ready' | 'ambiguous' | 'missing'

export function statusOf(source: string, edit: SuggestedEdit): EditStatus {
  if (edit.find === '') return 'missing'
  const first = source.indexOf(edit.find)
  if (first === -1) return 'missing'
  return source.indexOf(edit.find, first + 1) === -1 ? 'ready' : 'ambiguous'
}

export function buildEditPrompt(text: string): string {
  return [
    'Review the following note and list the specific edits worth making:',
    'spelling, grammar, wording, clarity, contradictions.',
    '',
    'Reply with ONLY a JSON array and nothing else — no prose, no code fence.',
    'Each element is {"find": "...", "replace": "...", "why": "..."} where:',
    '',
    '- "find" is text copied from the note exactly, character for character,',
    '  including its punctuation and capitalisation. Choose the shortest snippet',
    '  that appears exactly once in the note.',
    '- "replace" is what that text should say instead. Use an empty string to delete it.',
    '- "why" is at most one short sentence in plain language.',
    '',
    'Do not restructure the note, invent facts, or alter image links or URLs.',
    'At most 12 edits, most important first. If nothing needs changing, reply with [].',
    '',
    '---',
    text,
  ].join('\n')
}

/**
 * Pulls the edit list out of a model reply.
 *
 * Tolerant on purpose: models wrap JSON in code fences or add a line of
 * introduction however firmly they are asked not to, and that is not a good
 * enough reason to show the user a failure.
 */
export function parseEdits(raw: string): SuggestedEdit[] | null {
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

  const edits: SuggestedEdit[] = []
  parsed.forEach((item, i) => {
    if (typeof item !== 'object' || item === null) return
    const { find, replace, why } = item as Record<string, unknown>
    if (typeof find !== 'string' || typeof replace !== 'string') return
    if (find === '' || find === replace) return

    edits.push({
      id: `${i}`,
      find,
      replace,
      why: typeof why === 'string' && why.trim() ? why.trim() : 'Suggested change.',
    })
  })

  return edits
}

/**
 * Applies the chosen edits to [source], in order.
 *
 * Each replacement is done against the text as it stands rather than against
 * precomputed offsets, because an earlier edit shifts everything after it. An
 * edit whose text has been consumed by an earlier one is reported back as
 * skipped instead of being forced through somewhere it does not belong.
 */
export function applyEdits(
  source: string,
  edits: SuggestedEdit[],
): { text: string; applied: SuggestedEdit[]; skipped: SuggestedEdit[] } {
  let text = source
  const applied: SuggestedEdit[] = []
  const skipped: SuggestedEdit[] = []

  for (const edit of edits) {
    const at = text.indexOf(edit.find)
    if (at === -1) {
      skipped.push(edit)
      continue
    }
    // Spliced rather than String.replace: a "$&" in the replacement would
    // otherwise be treated as a backreference and quietly duplicate text.
    text = text.slice(0, at) + edit.replace + text.slice(at + edit.find.length)
    applied.push(edit)
  }

  return { text, applied, skipped }
}
