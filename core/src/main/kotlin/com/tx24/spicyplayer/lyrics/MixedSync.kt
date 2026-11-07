package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.lyrics.spicy.parser.CjkDetector

/**
 * Word-synced lyrics that time some lines only as a whole. Apple Music and some Chinese
 * sources mix the two: an English line timed word by word, then a Japanese one that is a single
 * span. Those lines are drawn the way line-synced lyrics are, rather than wiped like one long word.
 * A line with no timing at all ([TimedLine.untimed]) is drawn that way too, lit whole.
 */
object MixedSync {

    /** For each of [lines], whether it is timed only as a whole (one span for the line) or not at all. */
    fun lineTimed(lines: List<TimedLine>): List<Boolean> {
        // An untimed line says nothing about how the timed ones are written.
        val verdicts = lines.map { if (it.untimed) null else verdict(it) }
        if (verdicts.none { it == true } && lines.none { it.untimed }) return lines.map { false }
        // A one-word line can't tell by itself; it goes the way most lines in its script do.
        fun script(line: TimedLine) = line.words.any { word -> word.text.any(CjkDetector::isCjk) }
        val majority = lines.indices.filter { verdicts[it] != null }.groupBy { script(lines[it]) }
            .mapValues { (_, indices) -> indices.count { verdicts[it] == true } * 2 > indices.size }
        return lines.mapIndexed { index, line -> line.untimed || (verdicts[index] ?: majority[script(line)] ?: false) }
    }

    /**
     * True for a line that is one span of several words, null for one span of a single word
     * (which can't tell), false for anything timed in two spans or more, phrases included.
     */
    private fun verdict(line: TimedLine): Boolean? {
        val span = line.words.filter { it.text.isNotBlank() }.singleOrNull() ?: return false
        return if (words(span.text) > 1) true else null
    }

    /**
     * About how many words [text] is: one per space-separated token, and for CJK, which has no
     * spaces, one per three characters (a syllable-synced span is one to three of them).
     */
    fun words(text: String): Int = text.split(WHITESPACE).filter(String::isNotEmpty).sumOf { token ->
        val cjk = token.count(CjkDetector::isCjk)
        val latin = if (token.any { it.isLetterOrDigit() && !CjkDetector.isCjk(it) }) 1 else 0
        maxOf(1, latin + (cjk + 2) / 3)
    }

    private val WHITESPACE = Regex("\\s+")
}
