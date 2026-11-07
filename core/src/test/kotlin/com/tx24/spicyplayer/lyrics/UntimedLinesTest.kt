package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines with no timing at all inside synced lyrics: kept in their place, lit whole. */
class UntimedLinesTest {
    private fun parse(ttml: String) = TtmlLyricsParser.parse(ttml.byteInputStream())

    private val head = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word"><body><div>"""

    @Test fun `untimed lines in word-synced lyrics are sung together over the gap they sit in`() {
        val lyrics = parse("""$head
            <p begin="1s" end="2s"><span begin="1s" end="1.5s">One</span> <span begin="1.5s" end="2s">two</span></p>
            <p itunes:key="L2">Static line</p>
            <p>Bare line</p>
            <p begin="8s" end="9s"><span begin="8s" end="9s">Three</span></p>
        </div></body></tt>""")
        assertEquals(listOf("One", "Static line", "Bare line", "Three"), lyrics.lines.map { line -> line.words.joinToString(" ") { it.text }.substringBefore(" two") })
        assertEquals(listOf(false, true, true, false), lyrics.lines.map { it.untimed })
        assertEquals(listOf(1_000L, 2_000L, 2_000L, 8_000L), lyrics.lines.map { it.startMs })
        assertEquals(listOf(2_000L, 8_000L, 8_000L, 9_000L), lyrics.lines.map { it.endMs })
        // The words follow their line, so nothing is drawn at the start of the song.
        assertEquals(2_000L, lyrics.lines[1].words.single().startMs)
    }

    @Test fun `an untimed line first starts the song, one last gets a few seconds`() {
        val lyrics = parse("""$head
            <p itunes:key="L1">Intro</p>
            <p begin="3s" end="4s"><span begin="3s" end="4s">Sung</span></p>
            <p itunes:key="L3">Outro</p>
        </div></body></tt>""")
        assertEquals(listOf(0L to 3_000L, 3_000L to 4_000L, 4_000L to 8_000L), lyrics.lines.map { it.startMs to it.endMs })
    }

    @Test fun `line-synced lyrics place untimed lines the same way`() {
        val lyrics = parse("""<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Line" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"><body><div>
            <p begin="1s" end="2s">First</p><p itunes:key="x">Between</p><p begin="4s" end="5s">Last</p>
        </div></body></tt>""")
        assertEquals(listOf(false, true, false), lyrics.lines.map { it.untimed })
        assertEquals(2_000L to 4_000L, lyrics.lines[1].startMs to lyrics.lines[1].endMs)
    }

    @Test fun `timed lyrics are unchanged`() {
        val lyrics = parse("""$head<p begin="1s" end="2s"><span begin="1s" end="2s">One</span></p></div></body></tt>""")
        assertFalse(lyrics.lines.single().untimed)
        assertEquals(LineRole.LEAD, lyrics.lines.single().role)
    }

    private fun line(text: String, untimed: Boolean = false, vararg words: String) = TimedLine(0, 1_000,
        (if (words.isEmpty()) listOf(text) else words.toList()).map { TimedWord(it, 0, 1_000, false) }, untimed = untimed)

    @Test fun `untimed lines are drawn line-synced without swaying the timed ones`() {
        val verdicts = MixedSync.lineTimed(listOf(
            line("", false, "word", "by", "word"),
            line("one span of several words", untimed = true),
            line("", false, "Hey"),
        ))
        assertEquals(listOf(false, true, false), verdicts)
        assertTrue(MixedSync.lineTimed(listOf(line("Hey", untimed = true))).single())
    }
}
