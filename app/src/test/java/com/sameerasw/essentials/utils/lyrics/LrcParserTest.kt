package com.sameerasw.essentials.utils.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesStampsAndSortsLines() {
        val lines = LrcParser.parse(
            """
            [ar:Someone]
            [00:12.50]Second line
            [00:01.00]First line
            [01:02.123]Third line
            """.trimIndent(),
        )
        assertEquals(listOf(1_000L, 12_500L, 62_123L), lines.map { it.startMs })
        assertEquals(listOf("First line", "Second line", "Third line"), lines.map { it.text })
    }

    @Test
    fun repeatedStampsBecomeOneLineEach() {
        val lines = LrcParser.parse("[00:05.00][00:30.00]Chorus")
        assertEquals(listOf(5_000L, 30_000L), lines.map { it.startMs })
        assertTrue(lines.all { it.text == "Chorus" })
    }

    @Test
    fun appliesOffsetAndDropsWordStamps() {
        val lines = LrcParser.parse("[offset:+500]\n[00:02.00]<00:02.00>Hello <00:02.40>world")
        assertEquals(1_500L, lines.single().startMs)
        assertEquals("Hello world", lines.single().text)
    }

    @Test
    fun lineAtFollowsPosition() {
        val lyrics = TrackLyrics(LrcParser.parse("[00:01.00]One\n[00:05.00]\n[00:08.00]Three"))
        assertNull(lyrics.lineAt(500))
        assertEquals("One", lyrics.lineAt(1_000)?.text)
        assertEquals("One", lyrics.lineAt(4_999)?.text)
        // An empty stamped line is an instrumental gap.
        assertNull(lyrics.lineAt(6_000))
        assertEquals("Three", lyrics.lineAt(60_000)?.text)
        assertEquals(5_000L, lyrics.nextStartAfter(2_000))
        assertNull(lyrics.nextStartAfter(9_000))
    }

    @Test
    fun spicyLineMatchesTidiedNames() {
        val line = SpicyLyricsBridge.SpicyLine("words", "Blinding Lights", "The Weeknd", "k", 0L)
        assertTrue(SpicyLyricsBridge.matches(line, "Blinding Lights - Remastered", "The Weeknd"))
        assertTrue(SpicyLyricsBridge.matches(line, "blinding lights", ""))
        assertFalse(SpicyLyricsBridge.matches(line, "Save Your Tears", "The Weeknd"))
    }
}
