package com.justaleks.syncnotes.ai

data class WordPart(val text: String, val changed: Boolean)

enum class DiffKind { SAME, ADD, REMOVE }

data class DiffLine(
    val kind: DiffKind,
    val text: String,
    /**
     * Set when a line was replaced by exactly one other line, so the changed
     * words inside it can be picked out. Rewording one sentence is the case where
     * a whole-line diff tells you least.
     */
    val words: List<WordPart>? = null,
)

/** Longest common subsequence, as the indices of the matching pairs. */
private fun <T> commonPairs(a: List<T>, b: List<T>): List<Pair<Int, Int>> {
    // (n+1)×(m+1) of counts. Notes are lines, not characters, so this stays small.
    val table = Array(a.size + 1) { IntArray(b.size + 1) }

    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            table[i][j] =
                if (a[i] == b[j]) table[i + 1][j + 1] + 1
                else maxOf(table[i + 1][j], table[i][j + 1])
        }
    }

    val pairs = mutableListOf<Pair<Int, Int>>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> { pairs += i to j; i++; j++ }
            table[i + 1][j] >= table[i][j + 1] -> i++
            else -> j++
        }
    }
    return pairs
}

/** Splits into words while keeping the spaces, so the line can be rebuilt exactly. */
private val WORDS = Regex("""\s+|\S+""")

private fun wordParts(before: String, after: String): Pair<List<WordPart>, List<WordPart>> {
    val a = WORDS.findAll(before).map { it.value }.toList()
    val b = WORDS.findAll(after).map { it.value }.toList()
    val pairs = commonPairs(a, b)

    val keptA = pairs.map { it.first }.toSet()
    val keptB = pairs.map { it.second }.toSet()

    return a.mapIndexed { i, t -> WordPart(t, i !in keptA) } to
        b.mapIndexed { j, t -> WordPart(t, j !in keptB) }
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
fun diffLines(before: String, after: String): List<DiffLine> {
    val a = before.split("\n")
    val b = after.split("\n")
    val pairs = commonPairs(a, b)

    val out = mutableListOf<DiffLine>()
    var i = 0
    var j = 0

    fun flush(removed: List<String>, added: List<String>) {
        if (removed.size == 1 && added.size == 1) {
            val (beforeWords, afterWords) = wordParts(removed[0], added[0])
            out += DiffLine(DiffKind.REMOVE, removed[0], beforeWords)
            out += DiffLine(DiffKind.ADD, added[0], afterWords)
            return
        }
        removed.forEach { out += DiffLine(DiffKind.REMOVE, it) }
        added.forEach { out += DiffLine(DiffKind.ADD, it) }
    }

    for ((ai, bj) in pairs + (a.size to b.size)) {
        flush(a.subList(i, ai), b.subList(j, bj))
        if (ai < a.size) out += DiffLine(DiffKind.SAME, a[ai])
        i = ai + 1
        j = bj + 1
    }

    return out
}

data class DiffSummary(val added: Int, val removed: Int) {
    val unchanged: Boolean get() = added == 0 && removed == 0
}

fun summarise(lines: List<DiffLine>): DiffSummary = DiffSummary(
    added = lines.count { it.kind == DiffKind.ADD },
    removed = lines.count { it.kind == DiffKind.REMOVE },
)

/** A folded run of untouched lines, so a small fix is not buried in a wall of text. */
data class DiffRow(val line: DiffLine?, val hidden: Int = 0)

fun withContext(lines: List<DiffLine>, context: Int = 2): List<DiffRow> {
    val keep = mutableSetOf<Int>()
    lines.forEachIndexed { i, line ->
        if (line.kind == DiffKind.SAME) return@forEachIndexed
        for (k in (i - context)..(i + context)) if (k in lines.indices) keep += k
    }

    val rows = mutableListOf<DiffRow>()
    var hidden = 0
    lines.forEachIndexed { i, line ->
        if (i in keep) {
            if (hidden > 0) { rows += DiffRow(null, hidden); hidden = 0 }
            rows += DiffRow(line)
        } else {
            hidden++
        }
    }
    if (hidden > 0) rows += DiffRow(null, hidden)

    return rows
}
