package com.tx24.spicyplayer.translation

import com.tx24.spicyplayer.lyrics.spicy.romanization.HumanRomanization
import com.tx24.spicyplayer.network.data.blend.SequenceMatcher
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

data class GeniusTranslationPair(val originals: List<String>, val translations: List<String>, val sourceLanguage: String?) {
    companion object {
        /**
         * The lines of [original] and [translation] that pair up ([PageAlignment]). Translators
         * merge two sung lines into one, split one into two, and lay out sections their own way,
         * so the pages are lined up by length rather than by count. Two lines sharing one
         * translated line both get it; one line split in two gets both, joined.
         */
        fun fromText(original: String, translation: String, sourceLanguage: String? = null): GeniusTranslationPair? {
            val pairs = PageAlignment.align(PageAlignment.lines(original), PageAlignment.lines(translation))
                .flatMap { (ours, theirs) ->
                    if (ours.isEmpty() || theirs.isEmpty()) emptyList()
                    else theirs.joinToString(" ") { it.text }.let { text -> ours.map { it.text to text } }
                }
            return if (pairs.isEmpty()) null
            else GeniusTranslationPair(pairs.map { it.first }, pairs.map { it.second }, languageCode(sourceLanguage))
        }
    }
}

/**
 * Lines up a Genius page with its translation page, the way parallel texts are aligned: a
 * translation's length follows its original's, so the cheapest monotonic path through one line
 * to one, two to one (merged), one to two (split) and skipped lines, priced by how far each
 * step's lengths are from the page's own ratio, finds which lines belong together. Section
 * starts anchor it: a merge never crosses one, and two sections starting together are likely
 * the same section.
 */
object PageAlignment {
    /** A lyric line, the header of its section ("verse 1"), and whether it starts the section. */
    data class PageLine(val text: String, val header: String?, val startsSection: Boolean)

    /** A page's lyric lines with their sections. */
    fun lines(text: String): List<PageLine> {
        val out = mutableListOf<PageLine>()
        var header: String? = null
        val section = mutableListOf<String>()
        fun flush() {
            HumanRomanization.cleanLines(section.joinToString("\n")).forEachIndexed { i, line -> out += PageLine(line, header, i == 0) }
            section.clear()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                flush()
                // "[Verse 1: Hyein]" and "[Verse 1]" are the same section.
                header = line.substring(1, line.length - 1).substringBefore(':').trim().lowercase(Locale.ROOT)
            } else section += raw
        }
        flush()
        return out
    }

    /** The steps of the cheapest alignment, in order: the original lines and translated lines of each. */
    fun align(ours: List<PageLine>, theirs: List<PageLine>): List<Pair<List<PageLine>, List<PageLine>>> {
        val n = ours.size
        val m = theirs.size
        if (n == 0 || m == 0) return emptyList()
        val ratio = theirs.sumOf { size(it.text) }.toDouble() / ours.sumOf { size(it.text) }.coerceAtLeast(1)
        fun length(original: Int, translated: Int): Double = ln((translated + 4.0) / (original * ratio + 4.0)).let { it * it * 6 }
        fun anchor(i: Int, j: Int): Double {
            val a = ours[i]
            val b = theirs[j]
            return when {
                a.startsSection && b.startsSection -> if (a.header == b.header) -1.5 else -0.5
                a.startsSection != b.startsSection -> 0.8
                else -> 0.0
            }
        }
        val cost = Array(n + 1) { DoubleArray(m + 1) { Double.POSITIVE_INFINITY } }
        val step = Array(n + 1) { IntArray(m + 1) }
        cost[0][0] = 0.0
        fun relax(i: Int, j: Int, di: Int, dj: Int, price: Double) {
            val total = cost[i][j] + price
            if (total < cost[i + di][j + dj]) { cost[i + di][j + dj] = total; step[i + di][j + dj] = di * 4 + dj }
        }
        for (i in 0..n) for (j in 0..m) {
            if (cost[i][j] == Double.POSITIVE_INFINITY) continue
            if (i < n && j < m) relax(i, j, 1, 1, length(size(ours[i].text), size(theirs[j].text)) + anchor(i, j))
            if (i + 1 < n && j < m && !ours[i + 1].startsSection) {
                relax(i, j, 2, 1, MERGE + length(size(ours[i].text) + size(ours[i + 1].text), size(theirs[j].text)) + anchor(i, j))
            }
            if (i < n && j + 1 < m && !theirs[j + 1].startsSection) {
                relax(i, j, 1, 2, MERGE + length(size(ours[i].text), size(theirs[j].text) + size(theirs[j + 1].text)) + anchor(i, j))
            }
            if (i < n) relax(i, j, 1, 0, SKIP)
            if (j < m) relax(i, j, 0, 1, SKIP)
        }
        val out = ArrayDeque<Pair<List<PageLine>, List<PageLine>>>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val di = step[i][j] / 4
            val dj = step[i][j] % 4
            out.addFirst(ours.subList(i - di, i) to theirs.subList(j - dj, j))
            i -= di
            j -= dj
        }
        return out
    }

    /** How much a line says: a CJK character carries about two Latin letters' worth. */
    private fun size(text: String): Int = text.sumOf { c ->
        when {
            c.isWhitespace() -> 0
            c.code >= 0x2E80 -> 2
            else -> 1
        }.toInt()
    }.coerceAtLeast(1)

    private const val MERGE = 2.2
    private const val SKIP = 4.5
}

/** Matches original script in order; weak or ambiguous matches leave the machine line alone. */
object HumanTranslation {
    fun align(document: TranslationDocument, pair: GeniusTranslationPair): List<String?> {
        val out = MutableList<String?>(document.lines.size) { null }
        if (pair.originals.isEmpty() || pair.originals.size != pair.translations.size) return out
        val leadsWithBackground = document.lines.filter { it.address.backgroundIndex != null }.map { it.address.lineIndex }.toSet()
        val ours = document.lines.filter { !it.boundary && it.address.backgroundIndex == null }
        val mine = ours.map { key(it.text) }
        val theirs = pair.originals.map { key(it) }
        val withoutBackground = pair.originals.map { key(it.replace(BRACKETED, " ")) }
        val score = Array(ours.size + 1) { DoubleArray(theirs.size + 1) }
        val move = Array(ours.size + 1) { IntArray(theirs.size + 1) }
        fun candidate(i: Int, j: Int) = if (ours[i].address.lineIndex in leadsWithBackground) withoutBackground[j] else theirs[j]
        for (i in 1..ours.size) {
            for (j in 1..theirs.size) {
                val dropOurs = score[i - 1][j]
                val dropTheirs = score[i][j - 1]
                var best = maxOf(dropOurs, dropTheirs)
                var step = if (dropOurs >= dropTheirs) 2 else 3
                val a = mine[i - 1]
                val b = candidate(i - 1, j - 1)
                val similarity = if (a.isEmpty() || b.isEmpty()) 0.0 else SequenceMatcher.of(a, b).ratio()
                if (similarity >= MIN_SCORE) {
                    val take = score[i - 1][j - 1] + similarity - MIN_SCORE + 0.01
                    if (take > best) { best = take; step = 1 }
                }
                score[i][j] = best
                move[i][j] = step
            }
        }
        fun translated(i: Int, j: Int): String? = pair.translations[j].let {
            if (ours[i].address.lineIndex in leadsWithBackground) it.replace(BRACKETED, " ").replace(Regex("\\s+"), " ").trim() else it
        }.takeIf(String::isNotBlank)
        var i = ours.size
        var j = theirs.size
        while (i > 0 && j > 0) {
            when (move[i][j]) {
                1 -> { out[ours[i - 1].index] = translated(i - 1, j - 1); i--; j-- }
                2 -> i--
                else -> j--
            }
        }
        // A collapsed repeated chorus may reuse an exact original only when every occurrence
        // on the page has the same translation. Different human readings stay ambiguous.
        ours.forEachIndexed { index, line ->
            val exact = theirs.indices.filter { mine[index].isNotEmpty() && mine[index] == candidate(index, it) }
            val readings = exact.mapNotNull { translated(index, it) }.distinct()
            if (exact.size > 1 && readings.size > 1) out[line.index] = null
            else if (out[line.index] == null && readings.size == 1) out[line.index] = readings.single()
            // A mixed song's page keeps the lines already in the target language as they are.
            if (out[line.index]?.let { sameText(it, line.text) } == true) out[line.index] = null
        }
        return out
    }

    private fun key(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private val BRACKETED = Regex("\\([^()]*\\)|\\[[^\\[\\]]*]")
    private const val MIN_SCORE = 0.85
}

/** The lyrics' [own] translation first, then Genius's ([human]), then the [machine] one. */
fun combineTranslations(
    key: TranslationKey, human: List<String?>, machine: List<String?>, language: String?,
    languages: List<String?> = human.map { null },
    own: List<String?> = human.map { null },
    /** Who translated the machine lines: the key's provider, unless a fallback stood in. */
    machineProvider: TranslationProvider = key.provider,
): TranslationResult {
    require(human.size == machine.size && own.size == human.size)
    val texts = human.indices.map {
        own[it]?.takeIf(String::isNotBlank) ?: human[it]?.takeIf(String::isNotBlank) ?: machine[it]?.takeIf(String::isNotBlank)
    }
    val origins = texts.indices.map { index ->
        when {
            texts[index] == null -> null
            !own[index].isNullOrBlank() -> TranslationOrigin.Lyrics
            !human[index].isNullOrBlank() -> TranslationOrigin.Genius
            else -> TranslationOrigin.valueOf(machineProvider.name)
        }
    }
    return TranslationResult(key, texts, language, origins, languages)
}
