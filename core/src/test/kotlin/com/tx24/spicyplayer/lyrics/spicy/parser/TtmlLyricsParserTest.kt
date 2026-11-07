package com.tx24.spicyplayer.lyrics.spicy.parser

import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtmlLyricsParserTest {
    private fun doc(body: String, timing: String? = "Word", head: String = "") =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"${timing?.let { " itunes:timing=\"$it\"" }.orEmpty()}><head><metadata>$head</metadata></head><body>$body</body></tt>"""

    private fun parse(xml: String) = TtmlLyricsParser.parse(xml.byteInputStream())

    @Test fun aSpanIsOneSyllableWithItsOwnTiming() {
        val words = parse(doc("""<div><p begin="1s" end="3s"><span begin="1s" end="2s">two words</span> <span begin="2s" end="3s">well-known</span></p></div>""")).lines.single().words
        assertEquals(listOf("two words", "well-known"), words.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), words.map { it.startMs })
    }

    @Test fun aLineCoversItsOwnWordsWhateverItsTagSays() {
        // KuGou's Bohemian Rhapsody: "Galileo" written as a zero-length line, its word sung for 0.8s.
        val line = parse(doc("""<div><p begin="195.973s" end="195.973s"><span begin="195.973s" end="196.777s">Galileo</span></p></div>""")).lines.single()
        assertEquals(195_973L to 196_777L, line.startMs to line.endMs)
        val late = parse(doc("""<div><p begin="12s" end="13s"><span begin="11.5s" end="12.5s">Early</span> <span begin="12.5s" end="14s">late</span></p></div>""")).lines.single()
        assertEquals(11_500L to 14_000L, late.startMs to late.endMs)
    }

    @Test fun syllablesJoinOnlyWhenTheirTagsTouch() {
        val p = """<p begin="1" end="5"><span begin="1" end="2">Ma</span><span begin="2" end="3">ma,</span><span begin="3" end="4">mi</span> <span begin="4" end="5">a</span></p>"""
        val words = parse(doc("<div>$p</div>")).lines.single().words
        // "ma" glues to "Ma"; a comma ends the word; whitespace between tags ends it too.
        assertEquals(listOf(false, true, false, false), words.map { it.isPartOfWord })
    }

    @Test fun aBackgroundSpanInBetweenEndsTheWord() {
        val p = """<p begin="1" end="3"><span begin="1" end="2">Ma</span><span ttm:role="x-bg"><span begin="1" end="2">(oh)</span></span><span begin="2" end="3">ma</span></p>"""
        val lines = parse(doc("<div>$p</div>")).lines
        assertEquals(listOf(false, false), lines[0].words.map { it.isPartOfWord })
        assertEquals("oh", lines[1].words.single().text)
    }

    @Test fun backgroundVocalsLoseEveryParenthesis() {
        val p = """<p begin="1" end="3"><span begin="1" end="2">A</span><span ttm:role="x-bg"><span begin="1" end="2">(ooh (yeah))</span></span></p>"""
        assertEquals("ooh yeah", parse(doc("<div>$p</div>")).lines[1].words.single().text)
    }

    @Test fun onlyDeclaredV2AndV2000SingFromTheOtherSide() {
        val head = """<ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/><ttm:agent type="person" xml:id="v3"/>"""
        val body = listOf("v1", "v2", "v3").joinToString("") { agent ->
            """<p begin="1" end="2" ttm:agent="$agent"><span begin="1" end="2">x</span></p>"""
        }
        assertEquals(listOf(false, true, false), parse(doc("<div>$body</div>", head = head)).lines.map { it.oppositeAligned })
    }

    @Test fun instrumentalSectionsAndEmptyLinesAreSkipped() {
        val body = """<div itunes:songPart="Instrumental"><p begin="0" end="1"><span begin="0" end="1">skip</span></p></div>""" +
            """<div><p begin="1" end="2"><span begin="1" end="2"> </span></p><p begin="2" end="3"><span begin="2" end="3">keep</span></p></div>"""
        assertEquals(listOf("keep"), parse(doc(body)).lines.map { it.words.single().text })
    }

    @Test fun aByteOrderMarkOrWhitespaceBeforeTheDeclarationIsIgnored() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            doc("""<div><p begin="1s" end="2s"><span begin="1s" end="2s">Hello</span></p></div>""")
        for (prefix in listOf("\uFEFF", "\n  ", "\uFEFF\r\n")) {
            assertEquals("Hello", parse(prefix + xml).lines.single().words.single().text)
        }
    }

    @Test fun nbspReadsAsANoBreakSpace() {
        val p = """<p begin="1s" end="2s"><span begin="1s" end="2s">Hello&nbsp;there</span></p>"""
        assertEquals("Hello\u00A0there", parse(doc("<div>$p</div>")).lines.single().words.single().text)
    }

    @Test fun aSpanEndingBeforeItBeginsRunsToTheNextSpan() {
        val p = """<p begin="1s" end="4s"><span begin="1s" end="2s">One</span> <span begin="3s" end="2s">two</span> <span begin="3.5s" end="4s">three</span></p>"""
        val words = parse(doc("<div>$p</div>")).lines.single().words
        assertEquals(listOf(1_000L to 2_000L, 3_000L to 3_500L, 3_500L to 4_000L), words.map { it.startMs to it.endMs })
    }

    @Test fun aLineEndingBeforeItBeginsEndsWhereItBegins() {
        val line = parse(doc("""<div><p begin="5s" end="3s">Words</p><p begin="6s" end="7s"><span begin="6s" end="7s">x</span></p></div>""")).lines.first()
        assertEquals(5_000L to 5_000L, line.startMs to line.endMs)
        val lineSynced = parse(doc("""<div><p begin="5s" end="3s">Words</p></div>""", timing = "Line")).lines.single()
        assertEquals(5_000L to 5_000L, lineSynced.startMs to lineSynced.endMs)
    }

    @Test fun untimedSpansShareTheGapUntilTheNextTimedOne() {
        // In the middle: two untimed spans split 2s-4s; at the end: the last runs to the line's end.
        val p = """<p begin="1s" end="6s"><span begin="1s" end="2s">A</span> <span xml:lang="en">b</span> <span xml:lang="en">c</span> """ +
            """<span begin="4s" end="5s">D</span> <span xml:lang="en">e</span></p>"""
        val words = parse(doc("<div>$p</div>")).lines.single().words
        assertEquals(listOf(1_000L to 2_000L, 2_000L to 3_000L, 3_000L to 4_000L, 4_000L to 5_000L, 5_000L to 6_000L),
            words.map { it.startMs to it.endMs })
    }

    @Test fun readsEveryTimeFormat() {
        assertEquals(0.3, TtmlLyricsParser.convertTimeToSeconds("300ms")!!, 1e-9)
        assertEquals(90.0, TtmlLyricsParser.convertTimeToSeconds("1.5m")!!, 1e-9)
        assertEquals(3723.5, TtmlLyricsParser.convertTimeToSeconds("01:02:03.5")!!, 1e-9)
        assertEquals(63.25, TtmlLyricsParser.convertTimeToSeconds("1:03.25")!!, 1e-9)
        assertEquals(12.5, TtmlLyricsParser.convertTimeToSeconds("12.5s")!!, 1e-9)
        assertNull(TtmlLyricsParser.convertTimeToSeconds("10f"))
    }

    @Test fun transliterationsAreMatchedByTiming() {
        val head = """<iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><transliterations><transliteration><text for="L1">""" +
            """<span begin="00:00:02.000" end="00:00:03.000">de</span><span begin="1s" end="2s">nan</span></text></transliteration></transliterations></iTunesMetadata>"""
        val p = """<p begin="1s" end="3s" itunes:key="L1"><span begin="1s" end="2s">なん</span><span begin="2s" end="3s">で</span></p>"""
        assertEquals(listOf("nan", "de"), parse(doc("<div>$p</div>", head = head)).lines.single().words.map { it.romanizedText })
    }

    @Test fun oneTimedSpanPerLineWithoutADeclaredTimingIsLineSynced() {
        val parsed = parse(doc("""<div><p begin="1s" end="2s"><span begin="1s" end="2s">hello there</span></p></div>""", timing = null))
        assertEquals(LyricsType.Line, parsed.type)
        assertEquals("hello there", parsed.lines.single().words.single().text)
    }

    @Test fun aLineWithoutTimingStartsWithItsFirstSyllable() {
        val lines = parse(doc("""<div begin="0s"><p><span begin="4s" end="5s">late</span><span begin="5s" end="6s">r</span></p></div>""")).lines
        assertEquals(4_000L, lines.single().startMs)
        assertEquals(6_000L, lines.single().endMs)
        assertEquals(LineRole.LEAD, lines.single().role)
    }

    @Test fun everyBackgroundVocalOnALineIsKept() {
        val p = """<p begin="1" end="4"><span begin="1" end="2">A</span><span ttm:role="x-bg"><span begin="1" end="2">(oh)</span></span>""" +
            """<span ttm:role="x-bg"><span begin="3" end="4">(yeah)</span></span></p>"""
        val lines = parse(doc("<div>$p</div>")).lines
        assertEquals(listOf(LineRole.LEAD, LineRole.BACKGROUND, LineRole.BACKGROUND), lines.map { it.role })
        assertEquals(listOf("oh", "yeah"), lines.drop(1).map { it.words.single().text })
        assertEquals(4_000L, lines[0].endMs)
    }

    @Test fun lineTimedTransliterationsKeepTheSpacesBetweenSpans() {
        val head = """<iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><transliterations><transliteration>""" +
            """<text for="L1"><span begin="1s" end="2s">konnichiwa</span> <span begin="2s" end="3s">sekai</span><span ttm:role="x-bg"><span begin="2s" end="3s">oh</span></span></text>""" +
            """<text for="L2">plain  text</text></transliteration></transliterations></iTunesMetadata>"""
        val body = """<div><p begin="1s" end="3s" itunes:key="L1">こんにちは世界</p><p begin="3s" end="4s" itunes:key="L2">平文</p></div>"""
        val lines = parse(doc(body, timing = "Line", head = head)).lines
        assertEquals(listOf("konnichiwa sekai", "plain text"), lines.map { it.words.single().romanizedText })
    }

    @Test fun aLineWithOnlyItsRomanizationIsKept() {
        val p = """<p begin="1" end="2"><span ttm:role="x-roman">romaji</span></p>"""
        val line = parse(doc("<div>$p</div>", timing = "Line")).lines.single()
        assertEquals("", line.words.single().text)
        assertEquals("romaji", line.words.single().romanizedText)
    }
}
