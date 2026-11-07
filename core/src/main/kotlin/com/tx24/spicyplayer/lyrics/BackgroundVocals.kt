package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import java.util.Locale

/**
 * Background vocals written into a line instead of marked as such: a "(…)" at the start or end
 * of a sung line, "(Oh) I want you (I want you)", is split off as that line's background line,
 * without its brackets. Only the ends of a line: brackets in its middle are as often notes or
 * other words.
 */
object BackgroundVocals {

    fun split(lines: List<TimedLine>): List<TimedLine> {
        var nextGroup = (lines.mapNotNull { it.groupId }.maxOrNull() ?: -1) + 1
        return lines.flatMap { line ->
            val parts = if (line.role == LineRole.LEAD) parts(line) else null
            if (parts == null) listOf(line)
            else {
                val id = line.groupId ?: nextGroup++
                listOf(parts.lead.copy(groupId = id)) + parts.backgrounds.map { it.copy(groupId = id, role = LineRole.BACKGROUND) }
            }
        }
    }

    private class Parts(val lead: TimedLine, val backgrounds: List<TimedLine>)

    /** [line] as its lead and the background parts at its ends, or null when it has none. */
    private fun parts(line: TimedLine): Parts? {
        val words = line.words.filter { it.text.isNotBlank() }
        if (words.isEmpty()) return null
        return if (words.size == 1) splitText(line, words.single()) else splitWords(line, words)
    }

    /** A word-synced line: the words from an opening bracket at the start, and to a closing one at the end. */
    private fun splitWords(line: TimedLine, words: List<TimedWord>): Parts? {
        val leading = group(words.indices, words, opening = true)?.let { end -> 0..end }
        val trailing = group(words.indices.reversed(), words, opening = false)?.let { start -> start..words.lastIndex }
            ?.takeIf { leading == null || it.first > leading.last }
        // A side that isn't sung (a direction like "(x2)") stays in the line.
        val leadingBackground = leading?.let { background(line, words.slice(it)) }
        val trailingBackground = trailing?.let { background(line, words.slice(it)) }
        if (leadingBackground == null && trailingBackground == null) return null
        val from = if (leadingBackground != null) leading!!.last + 1 else 0
        val to = if (trailingBackground != null) trailing!!.first - 1 else words.lastIndex
        val lead = words.slice(from..to)
        if (lead.none { word -> word.text.any(Char::isLetterOrDigit) }) return null
        return Parts(line.copy(words = lead), listOfNotNull(leadingBackground, trailingBackground))
    }

    /**
     * Where the bracket at the line's start (or end, going backwards) closes: the last index of
     * the group, or its first going backwards. Null when that end isn't a bracket.
     */
    private fun group(order: IntProgression, words: List<TimedWord>, opening: Boolean): Int? {
        val edge = words[order.first].text.let { if (opening) it.trimStart().startsWith('(') else it.trimEnd().endsWith(')') }
        if (!edge) return null
        var depth = 0
        for (i in order) {
            val text = words[i].text
            depth += if (opening) text.count { it == '(' } - text.count { it == ')' } else text.count { it == ')' } - text.count { it == '(' }
            if (depth == 0) return i.takeIf { i != order.last }
        }
        return null
    }

    /** [words] in brackets as a background line of their own, or null for a direction like "(x2)". */
    private fun background(line: TimedLine, words: List<TimedWord>): TimedLine? {
        val cleaned = words.mapIndexed { i, word ->
            var text = word.text
            var roman = word.romanized
            if (i == 0) { text = text.trimStart().removePrefix("("); roman = roman?.trimStart()?.removePrefix("(") }
            if (i == words.lastIndex) { text = text.trimEnd().removeSuffix(")"); roman = roman?.trimEnd()?.removeSuffix(")") }
            word.copy(text = text, romanized = roman, attached = i > 0 && word.attached)
        }.filter { it.text.isNotBlank() }
        if (cleaned.isEmpty() || !isVocal(cleaned.joinToString(" ") { it.text })) return null
        val first = cleaned.first().let { it.copy(text = capitalized(it.text), romanized = it.romanized?.let(::capitalized)) }
        val sung = listOf(first) + cleaned.drop(1)
        return line.copy(words = sung, startMs = sung.first().startMs, endMs = sung.last().endMs)
    }

    /** A line timed as one piece: its text is cut, and every part keeps the line's timing. */
    private fun splitText(line: TimedLine, word: TimedWord): Parts? {
        var text = word.text.trim()
        val found = mutableListOf<String>()
        LEADING.matchEntire(text)?.let { match ->
            if (isVocal(match.groupValues[1])) { found += match.groupValues[1]; text = match.groupValues[2] }
        }
        TRAILING.matchEntire(text)?.let { match ->
            if (isVocal(match.groupValues[2])) { found += match.groupValues[2]; text = match.groupValues[1] }
        }
        if (found.isEmpty() || !text.any(Char::isLetterOrDigit)) return null
        // A source romanization can't be cut to match; the on-device one is made after this.
        return Parts(
            line.copy(words = listOf(word.copy(text = text.trim(), romanized = null))),
            found.map { part -> line.copy(words = listOf(word.copy(text = capitalized(part.trim()), romanized = null, attached = false))) },
        )
    }

    /** Sung words, not a direction like "(x2)" or "(Repeat)". */
    private fun isVocal(text: String): Boolean = text.any(Char::isLetter) && !DIRECTION.matches(text.trim())

    private fun capitalized(text: String) = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    private val LEADING = Regex("""^\(([^()]+)\)\s*(.*\S)$""")
    private val TRAILING = Regex("""^(.*\S)\s*\(([^()]+)\)$""")
    private val DIRECTION = Regex("""(?i)x\s*\d+|\d+\s*x|repeat|repeats|chorus|instrumental|interlude""")
}
