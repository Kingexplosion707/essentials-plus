package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcConverterTest {
    private fun parse(lrc: String) = TtmlLyricsParser.parse(LrcConverter.toTtml(lrc)!!.byteInputStream())

    @Test fun lineTimedLrcBecomesLineTimedTtml() {
        val parsed = parse(
            """
            [ti:Song]
            [ar:Artist]
            [00:01.00]First line
            [00:04.50]Second & <last>
            [00:08.00]
            """.trimIndent(),
        )
        assertEquals(LyricsType.Line, parsed.type)
        assertEquals(listOf(1_000L, 4_500L), parsed.lines.map { it.startMs })
        // A line ends where the next stamp, blank or not, starts.
        assertEquals(listOf(4_500L, 8_000L), parsed.lines.map { it.endMs })
        assertEquals("Second & <last>", parsed.lines[1].words.joinToString("") { it.text }.trim())
    }

    @Test fun repeatedStampsAndOffset() {
        val parsed = parse("[offset:500]\n[00:10.00][00:20.00]Chorus\n[00:15.00]Verse")
        assertEquals(listOf(9_500L, 14_500L, 19_500L), parsed.lines.map { it.startMs })
    }

    @Test fun enhancedLrcKeepsWordTiming() {
        val parsed = parse("[00:01.00]<00:01.00>Hel<00:01.50>lo <00:02.00>world<00:02.80>\n[00:05.00]<00:05.00>Next<00:05.40>")
        assertEquals(LyricsType.Syllable, parsed.type)
        val words = parsed.lines[0].words
        assertEquals(listOf("Hel", "lo", "world"), words.map { it.text.trim() })
        assertEquals(listOf(1_000L, 1_500L, 2_000L), words.map { it.startMs })
        assertEquals(listOf(1_500L, 2_000L, 2_800L), words.map { it.endMs })
        // "Hel" + "lo" have no space between them: one word, "lo" continuing it.
        assertEquals(listOf(false, true, false), words.map { it.isPartOfWord })
    }

    @Test fun enhancedLrcWithoutClosingStamp() {
        // Text before the first word stamp starts with the line; the open last word runs to the
        // next line, but no more than a few seconds.
        val parsed = parse("[00:01.00]Hello <00:01.60>world\n[00:10.00]<00:10.00>Again<00:10.50>")
        val words = parsed.lines[0].words
        assertEquals(listOf("Hello", "world"), words.map { it.text.trim() })
        assertEquals(listOf(1_000L, 1_600L), words.map { it.startMs })
        assertEquals(4_600L, words[1].endMs)
    }

    @Test fun tagsAndDetection() {
        val tags = LrcConverter.tags("[ti: Lemon ]\n[ar:米津玄師]\n[00:01.00]x")
        assertEquals("Lemon", tags.title)
        assertEquals("米津玄師", tags.artist)
        assertTrue(LrcConverter.isLrc("[ar:x]\n[01:02.03]words"))
        assertFalse(LrcConverter.isLrc("<tt xmlns=\"http://www.w3.org/ns/ttml\"/>"))
        assertNull(LrcConverter.toTtml("[ti:Only tags]\n[00:01.00]"))
    }

    @Test fun aLineStampedTheSameAsTheNextIsNotEndedByIt() {
        val parsed = parse("[00:01.00]こんにちは\n[00:01.00]Hello\n[00:05.00]Next")
        assertEquals(listOf(5_000L, 5_000L, 10_000L), parsed.lines.map { it.endMs })
    }

    @Test fun aPlainLineInEnhancedLrcRunsToTheNextLine() {
        val parsed = parse("[00:01.00]<00:01.00>Hi<00:02.00>\n[00:10.00]plain line\n[00:20.00]<00:20.00>End<00:21.00>")
        assertEquals(20_000L, parsed.lines[1].endMs)
    }

    @Test fun fourDigitFractionsAreRead() {
        val parsed = parse("[00:01.1234]One\n[00:02.00]Two")
        assertEquals(listOf(1_123L, 2_000L), parsed.lines.map { it.startMs })
    }
}
