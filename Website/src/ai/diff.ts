export type WordPart = { text: string; changed: boolean }

export type DiffLine = {
  kind: 'same' | 'add' | 'remove'
  text: string
  /**
   * Set when a line was replaced by exactly one other line, so the changed words
   * inside it can be picked out. Rewording one sentence is the case where a
   * whole-line diff tells you least.
   */
  words?: WordPart[]
}

/** Longest common subsequence, as the indices of the matching pairs. */
function commonPairs<T>(a: T[], b: T[]): Array<[number, number]> {
  // (n+1)×(m+1) of counts. Notes are lines, not characters, so this stays small.
  const table: number[][] = Array.from({ length: a.length + 1 }, () =>
    new Array<number>(b.length + 1).fill(0),
  )

  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      table[i][j] =
        a[i] === b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1])
    }
  }

  const pairs: Array<[number, number]> = []
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      pairs.push([i, j])
      i++
      j++
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      i++
    } else {
      j++
    }
  }
  return pairs
}

/** Splits into words while keeping the spaces, so the line can be rebuilt exactly. */
function toWords(line: string): string[] {
  return line.match(/\s+|[^\s]+/g) ?? []
}

/** Marks which words differ between two versions of one line. */
function wordParts(before: string, after: string): { before: WordPart[]; after: WordPart[] } {
  const a = toWords(before)
  const b = toWords(after)
  const pairs = commonPairs(a, b)

  const keptA = new Set(pairs.map(([i]) => i))
  const keptB = new Set(pairs.map(([, j]) => j))

  return {
    before: a.map((text, i) => ({ text, changed: !keptA.has(i) })),
    after: b.map((text, j) => ({ text, changed: !keptB.has(j) })),
  }
}

/**
 * A line-by-line diff of the note before and after.
 *
 * Line granularity because the notes are markdown: a heading, a bullet or a
 * paragraph is the unit a person actually reads. Where a single line was swapped
 * for a single other line, the changed words inside it are marked too — otherwise
 * a one-word fix shows up as a whole paragraph rewritten, which is exactly the
 * "what actually changed?" problem this is here to solve.
 */
export function diffLines(before: string, after: string): DiffLine[] {
  const a = before.split('\n')
  const b = after.split('\n')
  const pairs = commonPairs(a, b)

  const out: DiffLine[] = []
  let i = 0
  let j = 0

  const flush = (removed: string[], added: string[]) => {
    if (removed.length === 1 && added.length === 1) {
      const parts = wordParts(removed[0], added[0])
      out.push({ kind: 'remove', text: removed[0], words: parts.before })
      out.push({ kind: 'add', text: added[0], words: parts.after })
      return
    }
    for (const text of removed) out.push({ kind: 'remove', text })
    for (const text of added) out.push({ kind: 'add', text })
  }

  for (const [ai, bj] of [...pairs, [a.length, b.length] as [number, number]]) {
    flush(a.slice(i, ai), b.slice(j, bj))
    if (ai < a.length) out.push({ kind: 'same', text: a[ai] })
    i = ai + 1
    j = bj + 1
  }

  return out
}

export type DiffSummary = { added: number; removed: number; unchanged: boolean }

export function summarise(lines: DiffLine[]): DiffSummary {
  const added = lines.filter((l) => l.kind === 'add').length
  const removed = lines.filter((l) => l.kind === 'remove').length
  return { added, removed, unchanged: added === 0 && removed === 0 }
}

/**
 * Trims the runs of untouched lines down to a little context either side, so a
 * two-word fix in a long note does not arrive as a wall of unchanged text. The
 * gaps are reported so the UI can say how much it folded away.
 */
export type DiffRow = DiffLine | { kind: 'gap'; hidden: number }

export function withContext(lines: DiffLine[], context = 2): DiffRow[] {
  const keep = new Set<number>()
  lines.forEach((line, i) => {
    if (line.kind === 'same') return
    for (let k = i - context; k <= i + context; k++) {
      if (k >= 0 && k < lines.length) keep.add(k)
    }
  })

  const rows: DiffRow[] = []
  let hidden = 0
  lines.forEach((line, i) => {
    if (keep.has(i)) {
      if (hidden > 0) {
        rows.push({ kind: 'gap', hidden })
        hidden = 0
      }
      rows.push(line)
    } else {
      hidden++
    }
  })
  if (hidden > 0) rows.push({ kind: 'gap', hidden })

  return rows
}
