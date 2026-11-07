package com.tx24.spicyplayer.network.data.blend

import com.tx24.spicyplayer.network.data.RemoteLyricsPayload
import com.tx24.spicyplayer.network.data.blend.BlendText.lineText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFurnitureTest {
    private fun line(text: String, start: Double) = BlendLine(text, start, start + 3.0)

    /** One word a syllable, [step] seconds apart. */
    private fun worded(text: String, start: Double, step: Double): BlendLine {
        val words = text.split(' ')
        val syls = words.mapIndexed { i, w -> BlendSyllable(w, start + i * step, start + (i + 1) * step, partOfWord = false) }
        return BlendLine(null, start, syls.last().end, BlendGroup(syls, start, syls.last().end))
    }

    @Test
    fun `credits at either end, section headers and the title card go`() {
        val lines = listOf(
            line("Don't Feed the Dark - Project Vela", 0.0),
            line("作词 : Someone", 1.0),
            line("Producers : Quincy Jones", 2.0),
            line("[Verse 1]", 3.0),
            line("Hold the light up high", 4.0),
            line("Chorus:", 8.0),
            line("Never let it die", 9.0),
            line("Mixed at Studio Nine", 12.0),
            line("未经著作权人许可 不得翻唱", 13.0),
        )
        val kept = LyricsFurniture.kept(lines, "Don't Feed the Dark", "Project Vela").map { lineText(lines[it]) }
        assertEquals(listOf("Hold the light up high", "Never let it die"), kept)
    }

    @Test
    fun `a credit run longer than the reach goes whole, full-width colons and all`() {
        val roles = listOf("Lyrics by", "Drums", "Percussion", "Bass Guitar", "Synths", "Pads", "Piano", "Electric Guitars",
            "Programmed Horns", "Live Horns", "Vocal Produced", "Mix Engineer")
        val lines = listOf(line("Dynamite - BTS", 0.0)) + roles.mapIndexed { i, r -> line("$r：David Stewart", i + 1.0) } +
            listOf(line("Cos ah ah I'm in the stars tonight", 20.0), line("So watch me bring the fire", 24.0))
        assertEquals(listOf("Cos ah ah I'm in the stars tonight", "So watch me bring the fire"),
            LyricsFurniture.kept(lines, "Dynamite", "BTS").map { lineText(lines[it]) })
    }

    @Test
    fun `an exact title on a card stands without the byline`() {
        val lines = listOf(line("unravel - TK from 凛冽时雨", 0.0), line("Oshiete oshiete yo", 5.0), line("Sono shikumi wo", 9.0))
        assertEquals(listOf(1, 2), LyricsFurniture.kept(lines, "unravel", "TK from 凛として時雨"))
    }

    @Test
    fun `translations squeezed in after each sung line are dropped`() {
        val sung = listOf("Ten feet two inches of flower man", "Walking on the edge of town", "Nobody knows his name", "Petals falling down")
        val lines = sung.flatMapIndexed { i, text ->
            val at = i * 5.0
            // Each translation goes by in the gap before the next line, 8 characters in 0.3s.
            listOf(worded(text, at, 0.5), worded("十 尺 二 寸 的 花 少 侠", at + 4.5, 0.04))
        }
        assertEquals(setOf(1, 3, 5, 7), LyricsFurniture.translations(lines))
    }

    @Test
    fun `a real bilingual song keeps every line`() {
        val lines = listOf("I walk alone tonight", "我一个人走", "Under the city light", "在城市的灯下", "You were here", "你曾在这里")
            .mapIndexed { i, text -> worded(text, i * 4.0, if (' ' in text) 0.5 else 0.6) }
        assertTrue(LyricsFurniture.translations(lines).isEmpty())
    }

    @Test
    fun `a title card with a featured artist and the artist spelled its own way goes`() {
        val lrc = "[00:00.61]Restart Refine ( feat. GUMI) - dØtcØmmie/ GUMI\n[00:16.83]You cannot escape my mind\n[00:18.74]Restart refine"
        assertEquals("[00:16.83]You cannot escape my mind\n[00:18.74]Restart refine",
            LyricsFurniture.clean(RemoteLyricsPayload(syncedLyrics = lrc), "Restart Refine", "d0tc0mmie").syncedLyrics)
    }

    @Test
    fun `a payload loses its furniture in the form it came`() {
        val lrc = "[00:00.00]作词 : Someone\n[00:05.00]Hold the light up high\n[00:09.00]Never let it die"
        assertEquals("[00:05.00]Hold the light up high\n[00:09.00]Never let it die",
            LyricsFurniture.clean(RemoteLyricsPayload(syncedLyrics = lrc), "Song", "Artist").syncedLyrics)

        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>""" +
            """<p begin="0.000s" end="4.000s">作词 : Someone</p>""" +
            """<p begin="5.000s" end="8.000s">Hold the light up high</p>""" +
            """<p begin="9.000s" end="12.000s">Never let it die</p></div></body></tt>"""
        val cleaned = LyricsFurniture.clean(RemoteLyricsPayload(ttmlLyrics = ttml), "Song", "Artist").ttmlLyrics!!
        assertTrue(cleaned, "作词" !in cleaned && "Hold the light up high" in cleaned && "Never let it die" in cleaned)

        // Nothing to take out: the very same payload back.
        val plain = RemoteLyricsPayload(plainLyrics = "Hold the light up high\nNever let it die")
        assertTrue(plain === LyricsFurniture.clean(plain, "Song", "Artist"))
    }
}
