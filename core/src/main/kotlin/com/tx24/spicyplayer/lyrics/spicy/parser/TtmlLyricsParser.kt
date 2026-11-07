package com.tx24.spicyplayer.lyrics.spicy.parser

import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsFooter
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.lyrics.spicy.models.ParsedLyrics
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * TTML lyrics parser. Its rules are written against fast-xml-parser's view of the XML (as web
 * clients read these documents), and kept that way:
 * - Names are matched as written, prefix included (`ttm:role`, `itunes:key`, `xml:id`).
 * - An element with no attributes and no child elements is plain text there, so an attribute-
 *   less `<span>` is not a syllable (it only counts towards a line's text). An attribute-less
 *   `<p>` with text is a line with no timing, kept in its place ([placeUntimed]).
 * - A syllable is one timed `<span>`, whole: its text is never split and its timing never shared.
 * - A syllable joins the next one when its closing tag touches the next vocal span's opening
 *   tag and neither side has whitespace between them (nor a comma before).
 */
object TtmlLyricsParser {

    fun parse(inputStream: InputStream): ParsedLyrics =
        try {
            parseDocument(readTree(inputStream)) ?: ParsedLyrics(emptyList())
        } catch (e: Exception) {
            // Malformed TTML shows no lyrics instead of crashing the song-change collector.
            ParsedLyrics(emptyList())
        }

    // --- A minimal XML tree ---------------------------------------------------------------

    private sealed interface Node
    private class Text(val value: String) : Node
    private class Element(val name: String, val attrs: Map<String, String>) : Node {
        val children = mutableListOf<Node>()
        fun elements(name: String) = children.filterIsInstance<Element>().filter { it.name == name }
        fun element(name: String) = elements(name).firstOrNull()
        /** The element's own text, not its children's (fast-xml-parser's `#text`). */
        val ownText: String get() = children.filterIsInstance<Text>().joinToString("") { it.value }
        /** fast-xml-parser turns an element without attributes or child elements into a string. */
        val isPlain: Boolean get() = attrs.isEmpty() && children.none { it is Element }
        operator fun get(attr: String): String? = attrs[attr]
    }

    private fun readTree(input: InputStream): Element {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // A byte-order mark or whitespace ahead of the `<?xml?>` declaration is a parse error.
        val text = input.reader(Charsets.UTF_8).readText().trimStart { it == '\uFEFF' || it.isWhitespace() }
        parser.setInput(StringReader(text))
        // HTML's `&nbsp;` is not an XML entity, but some catalogues write it. A parser that can't
        // take extra entities still reads every document without one.
        runCatching { parser.defineEntityReplacementText("nbsp", "\u00A0") }
        val stack = ArrayDeque<Element>()
        var root: Element? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                    val element = Element(parser.name, attrs)
                    stack.lastOrNull()?.children?.add(element) ?: run { root = element }
                    stack.addLast(element)
                }
                XmlPullParser.END_TAG -> stack.removeLastOrNull()
                XmlPullParser.TEXT -> stack.lastOrNull()?.children?.add(Text(parser.text))
            }
            event = parser.next()
        }
        return requireNotNull(root) { "Empty document" }
    }

    // --- Helpers ---------------------------------------------------------------------------

    private fun isSpanObject(node: Element?) = node != null && node.name == "span" && !node.isPlain
    /** A span carrying lyrics text: anything with a ttm:role is metadata. */
    private fun isVocalSpan(node: Element?) = isSpanObject(node) && node!!["ttm:role"] == null

    private val zeroWidth = Regex("[\u200B\u200E\u200F\u2060\uFEFF]")
    private fun stripZeroWidth(text: String) = text.replace(zeroWidth, "")
    private fun hasLyricsText(text: String?) = text != null && stripZeroWidth(text).isNotBlank()

    private class TimedSpan(val begin: String, val end: String, val beginS: Double?, val endS: Double?, val text: String)
    /**
     * One `<text for>` entry: spans mirroring the lead, those under an x-bg wrapper, and the lead's
     * whole text with the spacing between spans kept (an entry can also be plain text, no spans).
     */
    private class TransliterationLine(
        val spans: MutableList<TimedSpan> = mutableListOf(),
        val background: MutableList<TimedSpan> = mutableListOf(),
        var text: String = "",
    )

    private class Syllable(val text: String, val roman: String?, val partOfWord: Boolean, val start: Double?, val end: Double?)
    private class RoleTexts(val roman: String?, val translation: String?, val translationLanguage: String?)
    /** One `<text for>` translation entry: the lead's text and, in order, its background vocals'. */
    private class TranslatedLine(val lead: String, val backgrounds: List<String>)
    private class Translations(val byKey: Map<String, TranslatedLine>, val language: String?)

    private fun parseDocument(root: Element): ParsedLyrics? {
        if (root.name != "tt") return null
        val body = root.element("body") ?: return null
        val divs = getDivs(body)
        if (divs.isEmpty()) return null

        val type = when (val timing = root["itunes:timing"]) {
            null -> inferLyricsType(divs)
            else -> when (timing.lowercase()) {
                "none", "static" -> LyricsType.Static
                "word", "syllable" -> LyricsType.Syllable
                "line" -> LyricsType.Line
                else -> return null
            }
        }

        val metadata = root.element("head")?.element("metadata")
        val songwriters = getSongwriters(metadata)
        val transliterations = getTransliterations(metadata)
        val translated = TranslationLookup(getTranslations(metadata))
        val oppositeAgents = metadata?.elements("ttm:agent").orEmpty()
            .filter { !it.isPlain && (it["xml:id"] == "v2" || it["xml:id"] == "v2000") }
            .mapNotNull { it["xml:id"] }.toSet()

        val lines = when (type) {
            LyricsType.Static -> staticLines(divs, translated)
            LyricsType.Line -> lineSyncedLines(divs, transliterations, translated)
            // v1 is the main vocal; a declared v2 or v2000 sings from the other side.
            LyricsType.Syllable -> syllableLines(divs, body, transliterations, translated) { agent -> agent in oppositeAgents }
        }.let(::placeUntimed)
        if (lines.isEmpty()) return null
        return ParsedLyrics(lines = lines, footer = LyricsFooter(songwriters), type = type,
            translationLanguage = translated.language.takeIf { lines.any { it.translation != null } })
    }

    /** Divs, tolerating documents that hang `<p>` straight off `<body>`. */
    private fun getDivs(body: Element): List<Element> {
        val divs = body.elements("div").filter { !it.isPlain }
        if (divs.isNotEmpty()) return divs
        val ps = body.elements("p")
        if (ps.isEmpty()) return emptyList()
        return listOf(Element("div", emptyMap()).also { it.children.addAll(ps) })
    }

    private fun inferLyricsType(divs: List<Element>): LyricsType {
        var sawTiming = false
        for (p in divs.flatMap { it.elements("p") }) {
            if (p.isPlain) continue
            val timedVocalSpans = p.elements("span").count { isVocalSpan(it) && !it["begin"].isNullOrEmpty() }
            if (timedVocalSpans > 1) return LyricsType.Syllable
            if (timedVocalSpans == 1) sawTiming = true
            if (!p["begin"].isNullOrEmpty()) sawTiming = true
        }
        return if (sawTiming) LyricsType.Line else LyricsType.Static
    }

    private fun staticLines(divs: List<Element>, translated: TranslationLookup): List<Line> = divs
        .filter { it["itunes:songPart"] != "Instrumental" && it["itunes:songPart"] != "Outro" }
        .flatMap { it.elements("p") }
        .mapNotNull { p ->
            val text = getLineText(p).orEmpty()
            // Static lyrics only take the inline x-roman span, never iTunesMetadata.
            val roman = inlineRoleTexts(p).roman?.trim().orEmpty()
            if (!hasLyricsText(text) && !hasLyricsText(roman)) return@mapNotNull null
            Line(listOf(Word(display(text), 0L, 0L, romanizedText = roman.ifEmpty { null })), 0L, 0L,
                translation = translated.lead(p))
        }

    private fun lineSyncedLines(
        divs: List<Element>,
        transliterations: Map<String, TransliterationLine>?,
        translated: TranslationLookup,
    ): List<Line> = divs
        .filter { it["itunes:songPart"] != "Instrumental" }
        .flatMap { it.elements("p") }
        .mapNotNull { p ->
            val vocalSpans = p.elements("span").filter(::isVocalSpan)
            val start = convertTimeToSeconds(p["begin"] ?: vocalSpans.firstOrNull()?.get("begin"))
            val end = convertTimeToSeconds(p["end"] ?: vocalSpans.lastOrNull()?.get("end"))
            val text = getLineText(p)
            val entry = p["itunes:key"]?.let { transliterations?.get(it) }
            val roman = when {
                !entry?.text.isNullOrEmpty() -> entry!!.text
                !entry?.spans.isNullOrEmpty() -> entry!!.spans.joinToString("") { it.text }
                else -> inlineRoleTexts(p).roman
            }?.trim()?.takeIf(String::isNotEmpty)
            if (!hasLyricsText(text) && !hasLyricsText(roman)) return@mapNotNull null
            val startMs = ms(start) ?: 0L
            val endMs = (ms(end) ?: startMs).coerceAtLeast(startMs)
            // Line-synced lines are never duet-aligned from TTML; our own converter says so explicitly.
            val opposite = p["spicy:oppositeAligned"]?.toBooleanStrictOrNull() ?: false
            Line(listOf(Word(display(text.orEmpty()), startMs, endMs, romanizedText = roman)), startMs, endMs,
                agent = p["ttm:agent"], oppositeAligned = opposite, translation = translated.lead(p), untimed = start == null)
        }

    private fun syllableLines(
        divs: List<Element>,
        body: Element,
        transliterations: Map<String, TransliterationLine>?,
        translated: TranslationLookup,
        isOppositeAgent: (String?) -> Boolean,
    ): List<Line> {
        val lines = mutableListOf<Line>()
        var groupId = 0
        for (div in divs) {
            if (div["itunes:songPart"] == "Instrumental") continue
            for (p in div.elements("p")) {
                // A bare `<p>` is a line with no timing: kept, in its place, when it has text.
                if (p.isPlain && !hasLyricsText(p.ownText)) continue
                val agent = p["ttm:agent"]?.ifEmpty { null } ?: div["ttm:agent"]?.ifEmpty { null } ?: body["ttm:agent"]
                val opposite = p["spicy:oppositeAligned"]?.toBooleanStrictOrNull() ?: isOppositeAgent(agent)
                val lineEntry = p["itunes:key"]?.let { transliterations?.get(it) }
                val pSpans = p.elements("span")

                val lead = buildSyllables(p, pSpans, lineEntry?.spans, stripParentheses = false).toMutableList()
                val leadRoman = inlineRoleTexts(p).roman?.trim().orEmpty()
                // A line-timed <p> in a word-timed document keeps its text as one syllable.
                if (lead.isEmpty()) {
                    val fallback = getLineText(p).orEmpty().trim()
                    if (fallback.isNotEmpty() || leadRoman.isNotEmpty()) {
                        val start = convertTimeToSeconds(p["begin"])
                        lead += Syllable(fallback, leadRoman.ifEmpty { null }, false, start, endAfter(start, convertTimeToSeconds(p["end"])))
                    }
                }
                // The line's window covers its own words too: catalogues write zero-length lines
                // ("Galileo" in KuGou's Bohemian Rhapsody), which would read as a gap and show the
                // interlude dots, and hold the scroll, while the words are being sung.
                var leadStart = listOfNotNull(convertTimeToSeconds(p["begin"]), lead.mapNotNull { it.start }.minOrNull()).minOrNull()
                var leadEnd = listOfNotNull(convertTimeToSeconds(p["end"]), lead.mapNotNull { it.end }.maxOrNull()).maxOrNull()
                // Where the lead's own words can run to, before background vocals widen the line.
                val wordsEnd = leadEnd

                val backgrounds = pSpans.filter { isSpanObject(it) && it["ttm:role"] == "x-bg" }.mapIndexedNotNull { bgIndex, bgSpan ->
                    val bgChildren = bgSpan.elements("span")
                    val bgVocal = bgChildren.filter(::isVocalSpan)
                    val bgEntry = bgSpan["itunes:key"]?.let { transliterations?.get(it) }
                    val syllables = buildSyllables(bgSpan, bgChildren, pickBackgroundTransliterations(bgEntry, lineEntry),
                        stripParentheses = true)
                    if (syllables.none { hasLyricsText(it.text) || hasLyricsText(it.roman) }) return@mapIndexedNotNull null
                    val start = convertTimeToSeconds(bgVocal.firstOrNull()?.get("begin"))
                        ?: convertTimeToSeconds(bgSpan["begin"]) ?: syllables.first().start
                    val end = convertTimeToSeconds(bgVocal.lastOrNull()?.get("end"))
                        ?: convertTimeToSeconds(bgSpan["end"]) ?: syllables.last().end
                    // Background vocals can start before, or run past, the lead line; the lead's
                    // window covers them (so an early "(ooh)" makes its line active).
                    if (end != null && (leadEnd == null || end > leadEnd!!)) leadEnd = end
                    if (start != null && (leadStart == null || start < leadStart!!)) leadStart = start
                    Background(syllables, start, end, translated.background(p, bgSpan, bgIndex))
                }

                val empty = lead.none { hasLyricsText(it.text) || hasLyricsText(it.roman) } && backgrounds.isEmpty()
                if (empty) continue

                val id = groupId++
                val leadStartMs = ms(leadStart) ?: 0L
                val untimed = leadStart == null
                val leadWords = toWords(lead, leadStartMs, (ms(wordsEnd) ?: leadStartMs).coerceAtLeast(leadStartMs))
                lines += Line(leadWords, leadStartMs, (ms(leadEnd) ?: leadStartMs).coerceAtLeast(leadStartMs),
                    agent = agent, role = LineRole.LEAD, groupId = id, oppositeAligned = opposite, translation = translated.lead(p),
                    untimed = untimed)
                backgrounds.forEach { (syllables, start, end, translation) ->
                    val startMs = ms(start) ?: leadStartMs
                    val endMs = (ms(end) ?: startMs).coerceAtLeast(startMs)
                    lines += Line(toWords(syllables, startMs, endMs), startMs, endMs,
                        agent = agent, role = LineRole.BACKGROUND, groupId = id, oppositeAligned = opposite, translation = translation,
                        untimed = untimed)
                }
            }
        }
        return lines
    }

    /**
     * Synced lyrics' lines with no timing at all, put in their place: in the gap between the timed
     * lines around them, shared evenly by a run of them, so the lyrics scroll through them in
     * order. One before the first timed line starts the song; one after the last gets
     * [UNTIMED_MS]. A line and its background vocals count as one.
     */
    private fun placeUntimed(lines: List<Line>): List<Line> {
        if (lines.none { it.untimed }) return lines
        val groups = mutableListOf<MutableList<Line>>()
        lines.forEach { line ->
            val last = groups.lastOrNull()
            if (last != null && line.isBackground && line.groupId != null && line.groupId == last.first().groupId) last += line
            else groups += mutableListOf(line)
        }
        val out = mutableListOf<Line>()
        var i = 0
        while (i < groups.size) {
            if (!groups[i].first().untimed) { out += groups[i++]; continue }
            var j = i
            while (j < groups.size && groups[j].first().untimed) j++
            val from = groups.subList(0, i).flatten().filterNot { it.untimed }.maxOfOrNull { it.endMs } ?: 0L
            val next = groups.subList(j, groups.size).flatten().firstOrNull { !it.untimed }?.startMs
            val until = maxOf(from, next ?: (from + UNTIMED_MS * (j - i)))
            // The run is sung all at once, over the whole gap: nothing says which line comes when.
            for (k in i until j) {
                groups[k].forEach { line ->
                    out += line.copy(startMs = from, endMs = until, words = line.words.map { it.copy(startMs = from, endMs = until) })
                }
            }
            i = j
        }
        return out
    }

    /** How long a line with no timing after the last timed one stays. */
    private const val UNTIMED_MS = 4_000L

    /**
     * The reference marks a syllable that continues into the next; our words mark the one glued to
     * the previous. A syllable missing its end runs until the next one starts (or the line ends);
     * a run of syllables with no timing at all shares that gap evenly.
     */
    private fun toWords(syllables: List<Syllable>, lineStartMs: Long, lineEndMs: Long): List<Word> {
        val starts = syllables.map { ms(it.start) }
        fun nextStart(after: Int) = starts.drop(after + 1).firstNotNullOfOrNull { it } ?: lineEndMs
        val times = mutableListOf<Pair<Long, Long>>()
        var previousEnd = lineStartMs
        var i = 0
        while (i < syllables.size) {
            val s = syllables[i]
            if (starts[i] == null && s.end == null) {
                var j = i
                while (j < syllables.size && starts[j] == null && syllables[j].end == null) j++
                val until = nextStart(j - 1).coerceAtLeast(previousEnd)
                val step = (until - previousEnd) / (j - i)
                for (k in i until j) times += (previousEnd + step * (k - i)) to (if (k == j - 1) until else previousEnd + step * (k - i + 1))
                previousEnd = until
                i = j
                continue
            }
            val start = starts[i] ?: previousEnd
            val end = (ms(s.end) ?: nextStart(i)).coerceAtLeast(start)
            times += start to end
            previousEnd = end
            i++
        }
        return syllables.mapIndexed { index, s ->
            val (start, end) = times[index]
            Word(display(s.text), start, end, isPartOfWord = index > 0 && syllables[index - 1].partOfWord,
                romanizedText = s.roman?.let(::display))
        }
    }

    /** A span's end, unless it comes before its begin. */
    private fun endAfter(start: Double?, end: Double?) = end?.takeIf { start == null || it >= start }

    private fun buildSyllables(
        parent: Element,
        siblings: List<Element>,
        transliterations: List<TimedSpan>?,
        stripParentheses: Boolean,
    ): List<Syllable> {
        fun clean(value: String) = if (stripParentheses) value.trim().replace(Regex("[()]"), "").trim() else value.trim()
        return siblings.mapIndexedNotNull { index, node ->
            if (!isVocalSpan(node)) return@mapIndexedNotNull null
            val rawText = node.ownText
            val text = clean(rawText)
            if (text.isEmpty()) return@mapIndexedNotNull null

            // Only a following *vocal* span, with no text at all between the tags, continues a word.
            val next = siblings.getOrNull(index + 1)
            val touching = node["begin"].isNullOrEmpty().not() && node["end"].isNullOrEmpty().not() &&
                parent.children.getOrNull(parent.children.indexOf(node) + 1) is Element
            val partOfWord = touching && isVocalSpan(next) &&
                !next!!.ownText.firstOrNull().isWs() &&
                !rawText.trim().endsWith(',') && !rawText.lastOrNull().isWs()

            val roman = findTransliteratedText(transliterations, node["begin"], node["end"])?.let { clean(it.text) }
            val start = convertTimeToSeconds(node["begin"])
            Syllable(text, roman?.ifEmpty { null }, partOfWord, start, endAfter(start, convertTimeToSeconds(node["end"])))
        }
    }

    private fun Char?.isWs() = this != null && isWhitespace()

    /** A line's text: its spans' text (space-joined where they don't carry spacing), else its own. */
    private fun getLineText(p: Element): String? {
        if (p.isPlain) return p.ownText
        val texts = p.elements("span")
            .filter { it.isPlain || isVocalSpan(it) }
            .map { it.ownText }
            .filter(String::isNotEmpty)
        if (texts.isNotEmpty()) {
            return texts.reduce { acc, cur ->
                val needsSpace = !acc.last().isWhitespace() && !cur.first().isWhitespace()
                acc + (if (needsSpace) " " else "") + cur
            }
        }
        return p.ownText.takeIf { p.children.any { it is Text } }
    }

    private data class Background(val syllables: List<Syllable>, val start: Double?, val end: Double?, val translation: String?)

    private fun inlineRoleTexts(p: Element): RoleTexts {
        fun role(name: String) = p.elements("span").firstOrNull { isSpanObject(it) && it["ttm:role"] == name }
        val translation = role("x-translation")
        return RoleTexts(role("x-roman")?.ownText, translation?.let(::leadText)?.let(::collapse)?.takeIf(::hasLyricsText),
            translation?.get("xml:lang"))
    }

    private fun collapse(text: String) = stripZeroWidth(text).replace(Regex("\\s+"), " ").trim()

    /**
     * Each line's translation: an x-translation span inside the `<p>` (or inside its x-bg span),
     * else the head's entry for the line's `itunes:key`. A background vocal's head entry is the
     * one for its own key, else the x-bg spans in its line's entry, in order.
     */
    private class TranslationLookup(private val head: Translations?) {
        /** The language the translations are in, when the document says. */
        var language: String? = head?.language
            private set

        fun lead(p: Element): String? = inline(p) ?: p["itunes:key"]?.let { head?.byKey?.get(it) }?.lead?.takeIf(::hasLyricsText)

        fun background(p: Element, bgSpan: Element, index: Int): String? = inline(bgSpan)
            ?: bgSpan["itunes:key"]?.let { head?.byKey?.get(it) }?.lead?.takeIf(::hasLyricsText)
            ?: p["itunes:key"]?.let { head?.byKey?.get(it) }?.backgrounds?.getOrNull(index)?.takeIf(::hasLyricsText)

        private fun inline(node: Element): String? {
            val texts = inlineRoleTexts(node)
            if (texts.translation != null && language == null) language = texts.translationLanguage
            return texts.translation
        }
    }

    /** The head's translations: the first `<translation>` block, which is in one language. */
    private fun getTranslations(metadata: Element?): Translations? {
        for (itm in metadata?.elements("iTunesMetadata").orEmpty()) {
            val block = itm.element("translations")?.elements("translation")?.firstOrNull() ?: continue
            val byKey = block.elements("text").mapNotNull { entry ->
                val key = entry["for"] ?: return@mapNotNull null
                key to TranslatedLine(collapse(leadText(entry)), backgroundTexts(entry).map(::collapse))
            }.toMap()
            if (byKey.isNotEmpty()) return Translations(byKey, block["xml:lang"])
        }
        return null
    }

    /** The text of each x-bg span under [node], in document order. */
    private fun backgroundTexts(node: Element): List<String> = node.children.filterIsInstance<Element>().flatMap { child ->
        if (child["ttm:role"] == "x-bg") listOf(child.children.joinToString("") { (it as? Text)?.value ?: (it as? Element)?.let(::leadText).orEmpty() })
        else backgroundTexts(child)
    }

    private fun getSongwriters(metadata: Element?): List<String> {
        val itm = metadata?.elements("iTunesMetadata").orEmpty()
        val entry = itm.firstOrNull { it.element("songwriters") != null } ?: itm.firstOrNull()
        return entry?.element("songwriters")?.elements("songwriter").orEmpty()
            .map { it.ownText.trim() }.filter(String::isNotEmpty)
    }

    private fun getTransliterations(metadata: Element?): Map<String, TransliterationLine>? {
        val map = linkedMapOf<String, TransliterationLine>()
        for (itm in metadata?.elements("iTunesMetadata").orEmpty()) {
            for (block in itm.element("transliterations")?.elements("transliteration").orEmpty()) {
                for (entry in block.elements("text")) {
                    val key = entry["for"] ?: continue
                    val line = TransliterationLine()
                    collectTransliterationSpans(entry.elements("span"), line.spans, line.background)
                    line.text = leadText(entry).replace(Regex("\\s+"), " ").trim()
                    if (line.spans.isEmpty() && line.background.isEmpty() && line.text.isEmpty()) continue
                    map[key]?.let {
                        it.spans += line.spans
                        it.background += line.background
                        it.text = listOf(it.text, line.text).filter(String::isNotEmpty).joinToString(" ")
                    } ?: run { map[key] = line }
                }
            }
        }
        return map.ifEmpty { null }
    }

    /** All the text under [node], the spaces between spans included, leaving out x-bg wrappers. */
    private fun leadText(node: Element): String = node.children.joinToString("") { child ->
        when {
            child is Text -> child.value
            child is Element && child["ttm:role"] == "x-bg" -> ""
            child is Element -> leadText(child)
            else -> ""
        }
    }

    /** Timed spans are leaves; wrappers are descended into, x-bg ones into the background list. */
    private fun collectTransliterationSpans(nodes: List<Element>, out: MutableList<TimedSpan>, background: MutableList<TimedSpan>) {
        for (node in nodes) {
            if (!isSpanObject(node)) continue
            val children = node.elements("span")
            if (children.isNotEmpty()) {
                collectTransliterationSpans(children, if (node["ttm:role"] == "x-bg") background else out, background)
                continue
            }
            val begin = node["begin"] ?: continue
            val end = node["end"] ?: continue
            out += TimedSpan(begin, end, convertTimeToSeconds(begin), convertTimeToSeconds(end), node.ownText)
        }
    }

    /** A dedicated entry for the x-bg span, else an x-bg wrapper in the line's entry, else the lead spans. */
    private fun pickBackgroundTransliterations(bgEntry: TransliterationLine?, lineEntry: TransliterationLine?): List<TimedSpan>? {
        for (entry in listOfNotNull(bgEntry, lineEntry)) {
            if (entry.background.isNotEmpty()) return entry.background
            if (entry.spans.isNotEmpty()) return entry.spans
        }
        return null
    }

    /** Matched by timing, written the same or within 2ms ("10.5s" vs "00:00:10.500"). */
    private fun findTransliteratedText(spans: List<TimedSpan>?, begin: String?, end: String?): TimedSpan? {
        if (spans.isNullOrEmpty() || begin.isNullOrEmpty() || end.isNullOrEmpty()) return null
        spans.firstOrNull { it.begin == begin && it.end == end }?.let { return it }
        val b = convertTimeToSeconds(begin) ?: return null
        val e = convertTimeToSeconds(end) ?: return null
        return spans.firstOrNull {
            it.beginS != null && it.endS != null && abs(it.beginS - b) <= 0.002 && abs(it.endS - e) <= 0.002
        }
    }

    private val offsetTime = Regex("^([+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+))(ms|h|m|s|f|t)?$")

    /** Clock time `[hh:]mm:ss[.fraction]` (a 4th part, SMPTE frames, dropped) or offset time `12.5s`/`300ms`/`2m`/`1.5h`. */
    fun convertTimeToSeconds(value: String?): Double? {
        val time = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        fun finite(s: Double) = s.takeIf { it.isFinite() && it >= 0 }
        if (':' in time) {
            val parts = time.split(':')
            if (parts.size !in 2..4) return null
            val reversed = parts.reversed()
            val offset = if (parts.size == 4) 1 else 0
            var seconds = 0.0
            for (i in offset until reversed.size) {
                val part = leadingFloat(reversed[i]) ?: return null
                seconds += part * 60.0.pow(i - offset)
            }
            return finite(seconds)
        }
        val match = offsetTime.find(time) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2]) {
            "ms" -> finite(number / 1000)
            "h" -> finite(number * 3600)
            "m" -> finite(number * 60)
            "s", "" -> finite(number)
            else -> null // frames and ticks need a frame/tick rate
        }
    }

    /** JavaScript parseFloat: the longest leading number, ignoring what follows. */
    private fun leadingFloat(s: String): Double? =
        Regex("^\\s*[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?").find(s)?.value?.trim()?.toDoubleOrNull()

    private fun ms(seconds: Double?): Long? = seconds?.let { (it * 1000).roundToLong() }

    /** The reference strips these when rendering; nothing else reads the word text. */
    private fun display(text: String) = stripZeroWidth(text).trim()
}
