package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.lyrics.spicy.parser.CjkDetector
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import com.tx24.spicyplayer.lyrics.spicy.romanization.HumanRomanization
import com.tx24.spicyplayer.lyrics.spicy.romanization.RomanizationService
import com.tx24.spicyplayer.lyrics.spicy.romanization.Script
import com.tx24.spicyplayer.lyrics.spicy.romanization.ScriptDetector
import com.tx24.spicyplayer.network.data.LyricsBlends
import com.tx24.spicyplayer.network.data.LyricsOrigins
import com.tx24.spicyplayer.network.data.RemoteLyricsSelection
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType

/** Keeps the selected provider's best format and provenance through to the renderer. */
object RemoteLyricsAdapter {
    private val lrcStamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,4}))?]")
    private val lrcOffset = Regex("^\\[offset:\\s*([+-]?\\d+)\\s*]\\s*$", RegexOption.IGNORE_CASE)

    /** [romanize]: false leaves the words the source didn't romanize as they are (the widget shows none). */
    fun render(
        selection: RemoteLyricsSelection,
        durationMs: Long,
        splitBackgroundVocals: Boolean = true,
        romanize: Boolean = true,
    ): LyricsState.Ready {
        val payload = selection.payload
        val attribution = payload.attribution
        val parsedTtml = payload.ttmlLyrics?.takeIf(String::isNotBlank)?.let { ttml ->
            TtmlLyricsParser.parse(ttml.byteInputStream())
        }
        val ttmlLines = parsedTtml?.lines?.let { parsedLines ->
            parsedLines.map { line ->
                TimedLine(
                    startMs = line.startMs,
                    endMs = line.endMs,
                    words = line.words.map { word ->
                        TimedWord(word.text, word.startMs, word.endMs, word.isPartOfWord, word.romanizedText)
                    },
                    role = line.role,
                    groupId = line.groupId,
                    agent = line.agent,
                    oppositeAligned = line.oppositeAligned,
                    translation = line.translation,
                    untimed = line.untimed,
                )
            }.takeIf(List<TimedLine>::isNotEmpty)
        }
        // Plain text becomes untimed lines, rendered by the lyrics view's static mode like the
        // reference, so it gets the same layout, romanization and credits as synced lyrics.
        val read = (ttmlLines ?: parseLrc(payload.syncedLyrics, durationMs)).ifEmpty { staticLines(payload.plainLyrics) }
        // Background vocals written in brackets at a synced line's ends get lines of their own.
        val synced = selection.quality == RemoteLyricsQuality.WORD_SYNCED || selection.quality == RemoteLyricsQuality.LINE_SYNCED
        val parsed = if (synced && splitBackgroundVocals) BackgroundVocals.split(read) else read
        // Word-synced text is already split per syllable; line-timed text can arrive as one
        // word per line, which the layout (it wraps between words only) can never wrap.
        val unromanized = if (selection.quality == RemoteLyricsQuality.WORD_SYNCED) parsed else parsed.map(::wrappable)
        require(unromanized.isNotEmpty()) { "Selected lyrics contain no displayable text" }
        // On-device romanization fills only the words the source left unromanized.
        val romanized = if (!romanize) unromanized else {
            val computed = RomanizationService.romanize(unromanized.map { line -> line.words.map(TimedWord::text) })
            unromanized.mapIndexed { l, line ->
                line.copy(words = line.words.mapIndexed { w, word -> word.copy(romanized = word.romanized ?: computed[l][w]) })
            }
        }
        // Lines timed only as a whole are drawn as line-synced, which wraps between words. They
        // are split after romanizing, so a line read as one span keeps its romaji's spaces.
        val lineTimed = if (selection.quality == RemoteLyricsQuality.WORD_SYNCED) MixedSync.lineTimed(romanized) else null
        val lines = if (lineTimed == null || true !in lineTimed) romanized
            else romanized.mapIndexed { l, line -> if (lineTimed[l]) wrappable(line).copy(lineTimed = true) else line }
        return LyricsState.Ready(
            lines = lines,
            provider = attribution?.providerName ?: selection.source.displayName,
            // Credited by where the lyrics come from, the same whichever service fetched them.
            source = LyricsOrigins.originOf(LyricsOrigins.BUILT_IN, selection.source.id, attribution?.originName)
                ?.takeIf { LyricsBlends.byId(selection.source.id) == null }?.credit
                ?: attribution?.originName ?: selection.source.displayName,
            maker = attribution?.maker,
            uploader = attribution?.uploader,
            songwriters = (attribution?.songwriters.orEmpty() + parsedTtml?.songwriters.orEmpty())
                .map { decodeEntities(it).trim() }.filter(String::isNotBlank).distinct(),
            sourceRomanized = unromanized.any { line -> line.words.any { it.romanized != null } },
            translationLanguage = parsedTtml?.translationLanguage,
            lyricsType = when (selection.quality) {
                RemoteLyricsQuality.WORD_SYNCED -> LyricsType.Syllable
                RemoteLyricsQuality.LINE_SYNCED -> LyricsType.Line
                else -> LyricsType.Static
            },
        )
    }

    /**
     * [lyrics] with Genius's human romanization ([genius], its lyric lines) laid over the lines it
     * lines up with. Only lines that were romanized get it; the rest, and lines Genius doesn't
     * match confidently, keep what they had.
     */
    fun withHumanRomanization(lyrics: LyricsState.Ready, genius: List<String>): LyricsState.Ready {
        val cuts = HumanRomanization.apply(
            ours = lyrics.lines.map { line -> line.words.map { it.romanized ?: it.text } },
            genius = genius,
            background = lyrics.lines.map { it.role == LineRole.BACKGROUND },
            groups = lyrics.lines.map { it.groupId },
        )
        return lyrics.copy(lines = lyrics.lines.mapIndexed { i, line ->
            val cut = cuts[i]
            if (cut == null || line.words.none { it.romanized != null }) line
            else line.copy(words = line.words.mapIndexed { w, word -> word.copy(romanized = cut[w]) })
        })
    }

    /** The lead lines of [lyrics] that are romanized on the device, as their own text. */
    fun romanizedTexts(lyrics: LyricsState.Ready): List<String> = lyrics.lines
        .filter { line -> line.role != LineRole.BACKGROUND && line.words.any { it.romanized != null } }
        .map { line -> line.words.joinToString("") { it.text }.trim() }

    /** Whether [lyrics] are in a script Genius writes romanizations for (Japanese, Korean, Chinese). */
    fun wantsHumanRomanization(lyrics: LyricsState.Ready): Boolean {
        if (lyrics.sourceRomanized || lyrics.lines.none { line -> line.words.any { it.romanized != null } }) return false
        val scripts = ScriptDetector.detect(lyrics.lines.joinToString("\n") { line -> line.words.joinToString("") { it.text } })
        return scripts.any { it == Script.JAPANESE || it == Script.KOREAN || it == Script.CHINESE }
    }

    /** Some sources escape twice ("Tom &amp;amp; Jerry"), so one XML decode still leaves "&amp;". */
    fun decodeEntities(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    /**
     * Splits at spaces, and CJK per character (glued), so long line-timed lines can wrap.
     *
     * A source romanization doesn't line up with the original's pieces, so it becomes pieces of
     * its own after them: blank in the original view, while the original's are blank when
     * romanized. The layout gives blank pieces no room.
     */
    fun wrappable(line: TimedLine): TimedLine = line.copy(words = line.words.flatMap { word ->
        val pieces = wrapPieces(word.text, word.attached)
        val roman = word.romanized?.let { wrapPieces(it, word.attached) }
        when {
            roman != null && (pieces.size > 1 || roman.size > 1) ->
                pieces.map { (text, attached) -> TimedWord(text, word.startMs, word.endMs, attached, romanized = "") } +
                    roman.map { (text, attached) -> TimedWord("", word.startMs, word.endMs, attached, romanized = text) }
            roman != null || pieces.size <= 1 -> listOf(word)
            else -> pieces.map { (text, attached) -> TimedWord(text, word.startMs, word.endMs, attached) }
        }
    })

    private fun wrapPieces(text: String, attached: Boolean): List<Pair<String, Boolean>> =
        text.split(Regex("\\s+")).filter(String::isNotEmpty).flatMapIndexed { tokenIdx, token ->
            // Cut by index: growing a String a character at a time is quadratic on a long token.
            val runs = mutableListOf<String>()
            var start = 0
            for (i in 1..token.length) {
                if (i == token.length || CjkDetector.isCjk(token[i]) || CjkDetector.isCjk(token[i - 1])) {
                    runs += token.substring(start, i)
                    start = i
                }
            }
            runs.mapIndexed { runIdx, run -> run to if (runIdx > 0) true else tokenIdx == 0 && attached && !text.first().isWhitespace() }
        }

    private fun staticLines(plain: String?): List<TimedLine> = plain.orEmpty().lines()
        .map(String::trim).filter(String::isNotEmpty)
        .map { text -> TimedLine(0L, 0L, listOf(TimedWord(text, 0L, 0L, false))) }

    private fun parseLrc(raw: String?, durationMs: Long): List<TimedLine> {
        // A positive offset shows the lyrics earlier, as in [LrcConverter].
        val offset = raw.orEmpty().lineSequence().firstNotNullOfOrNull { lrcOffset.find(it.trim()) }
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val entries = raw.orEmpty().lineSequence().flatMap { line ->
            val text = line.replace(lrcStamp, "").trim()
            if (text.isBlank()) emptySequence() else lrcStamp.findAll(line).map { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3].let { if (it.isBlank()) 0L else it.padEnd(3, '0').take(3).toLong() }
                ((minutes * 60 + seconds) * 1_000 + fraction - offset).coerceAtLeast(0L) to text
            }
        }.distinct().sortedBy(Pair<Long, String>::first).toList()
        return entries.mapIndexed { index, (start, text) ->
            // A line stamped the same as this one (a translation under it) doesn't end it.
            val end = entries.asSequence().drop(index + 1).map { it.first }.firstOrNull { it > start }
                ?: durationMs.takeIf { it > start }
                ?: start + 4_000
            TimedLine(start, end.coerceAtLeast(start + 1), listOf(TimedWord(text, start, end, false)))
        }
    }
}
