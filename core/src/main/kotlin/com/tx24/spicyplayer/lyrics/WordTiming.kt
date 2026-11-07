package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.lyrics.spicy.parser.CjkDetector

/**
 * Word times for lines that are only timed as a whole (LRC from LRCLIB, NetEase and the like).
 *
 * Such a line arrives with every word stamped with the line's own start and end, and the end is
 * simply the next line's stamp, so it also covers any breath, held note or instrumental before
 * the next line. Wiping or pulsing each word over that whole span lands every word at once and
 * runs late. Here the line's words are laid out one after another instead:
 *
 * - The sung part of the line is estimated from how much there is to sing: about
 *   [MS_PER_SYLLABLE] per syllable plus [MS_PER_LINE], never past [SUNG_SHARE] of the span to the
 *   next line (a short span is a dense line), and never shorter than [MIN_SUNG_MS] or the span.
 * - Each word gets a share of that by its syllables, with a little extra for its onset and more
 *   for the line's last word, which singers hold.
 *
 * Only data already in hand is used: the line stamps and the words' own text. Word-timed lines
 * (TTML syllables) are left exactly as they came.
 */
object WordTiming {
    /** A sung syllable, at a relaxed pace (ballads sit near it, rap far under it). */
    const val MS_PER_SYLLABLE = 330L
    /** A line's lead-in and release on top of its syllables. */
    const val MS_PER_LINE = 350L
    /** The most of the span to the next line the singing takes: the rest is the breath before it. */
    const val SUNG_SHARE = 0.92f
    /** A sung line is at least this long (or the whole span, if shorter). */
    const val MIN_SUNG_MS = 600L
    /** A word's extra weight for its onset, in syllables. */
    private const val ONSET_WEIGHT = 0.35f
    /** The line's last word is held: this many syllables extra. */
    private const val HELD_WEIGHT = 0.8f

    /**
     * [lines] with word times spread over each line that is timed only as a whole. With
     * [LyricsType.Line] lyrics every line is; with [LyricsType.Syllable] lyrics only the lines
     * [TimedLine.lineTimed] marks are. Static lyrics, untimed lines and interludes are returned
     * unchanged.
     */
    fun spread(lines: List<TimedLine>, type: LyricsType): List<TimedLine> = when (type) {
        LyricsType.Static -> lines
        LyricsType.Line -> lines.map { if (isWholeLine(it)) spreadLine(it) else it }
        LyricsType.Syllable -> lines.map { if (it.lineTimed && isWholeLine(it)) spreadLine(it) else it }
    }

    /** Whether [line] is timed only as a whole: every word carries the same start and end. */
    fun isWholeLine(line: TimedLine): Boolean {
        if (line.untimed || line.role == LineRole.INTERLUDE || line.words.isEmpty()) return false
        val first = line.words.first()
        return line.words.all { it.startMs == first.startMs && it.endMs == first.endMs }
    }

    /** One whole-timed line's words, laid one after another over its estimated sung part. */
    fun spreadLine(line: TimedLine): TimedLine {
        val start = line.words.first().startMs
        val span = (line.words.first().endMs - start).coerceAtLeast(1L)
        // The original's pieces and the source's romanization pieces (blank in the original view)
        // are two readings of the same singing: each is laid over the same sung part on its own.
        val shown = line.words.indices.filter { line.words[it].text.isNotBlank() }
        val romanOnly = line.words.indices.filter { line.words[it].text.isBlank() && !line.words[it].romanized.isNullOrBlank() }
        val syllables = shown.sumOf { syllables(line.words[it].text).toDouble() }.toFloat()
            .takeIf { it > 0f } ?: romanOnly.sumOf { syllables(line.words[it].romanized!!).toDouble() }.toFloat()
        val sung = sungMs(syllables, span)
        val times = arrayOfNulls<LongArray>(line.words.size)
        layOut(shown, start, sung) { syllables(line.words[it].text) }.forEach { (i, t) -> times[i] = t }
        layOut(romanOnly, start, sung) { syllables(line.words[it].romanized!!) }.forEach { (i, t) -> times[i] = t }
        val words = line.words.mapIndexed { i, word ->
            val t = times[i]
            if (t == null) word else word.copy(startMs = t[0], endMs = t[1])
        }
        return line.copy(words = words)
    }

    /** How long a line of [syllables] is sung, given [spanMs] until the next line. */
    fun sungMs(syllables: Float, spanMs: Long): Long {
        val natural = (syllables * MS_PER_SYLLABLE + MS_PER_LINE).toLong()
        val dense = (spanMs * SUNG_SHARE).toLong()
        return minOf(natural, dense).coerceAtLeast(minOf(MIN_SUNG_MS, spanMs)).coerceAtMost(spanMs).coerceAtLeast(1L)
    }

    /** [indices]' words one after another from [start] over [sungMs], weighted by [weightOf]'s syllables. */
    private inline fun layOut(indices: List<Int>, start: Long, sungMs: Long, weightOf: (Int) -> Float): List<Pair<Int, LongArray>> {
        if (indices.isEmpty()) return emptyList()
        val weights = indices.mapIndexed { n, i ->
            weightOf(i) + ONSET_WEIGHT + if (n == indices.lastIndex) HELD_WEIGHT else 0f
        }
        val total = weights.sum().takeIf { it > 0f } ?: return emptyList()
        var cursor = 0f
        return indices.mapIndexed { n, i ->
            val from = start + (cursor / total * sungMs).toLong()
            cursor += weights[n]
            val to = if (n == indices.lastIndex) start + sungMs else start + (cursor / total * sungMs).toLong()
            i to longArrayOf(from, to.coerceAtLeast(from + 1))
        }
    }

    /**
     * About how many syllables [text] is sung in: a CJK character or a Hangul block is one, a run
     * of vowels in a Latin, Greek or Cyrillic word is one, and other scripts count a syllable per
     * two or three letters. Anything with letters is at least one.
     */
    fun syllables(text: String): Float {
        var count = 0f
        var otherLetters = 0
        var inVowels = false
        var letters = 0
        for (c in text) {
            when {
                CjkDetector.isCjk(c) || c in '가'..'힣' -> {
                    count += 1f
                    inVowels = false
                }
                c.isLetter() -> {
                    letters++
                    val vowel = c.lowercaseChar() in VOWELS
                    if (vowel && !inVowels) count += 1f
                    inVowels = vowel
                    if (!isAlphabetic(c)) otherLetters++
                }
                else -> inVowels = false
            }
        }
        if (otherLetters > 0) count += otherLetters / 2.5f
        return if (letters > 0 || count > 0f) count.coerceAtLeast(1f) else 0f
    }

    /** Scripts whose vowels [VOWELS] knows. */
    private fun isAlphabetic(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.BASIC_LATIN ||
            block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT ||
            block == Character.UnicodeBlock.LATIN_EXTENDED_A ||
            block == Character.UnicodeBlock.LATIN_EXTENDED_B ||
            block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL ||
            block == Character.UnicodeBlock.GREEK ||
            block == Character.UnicodeBlock.CYRILLIC
    }

    private val VOWELS = (
        "aeiouyàáâãäåæèéêëìíîïòóôõöøùúûüýÿāăąēĕėęěīĭįıōŏőœūŭůűųơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ" +
            "αεηιουωάέήίόύώϊϋΐΰ" +
            "аеёиоуыэюяіїєў"
        ).toSet()
}
