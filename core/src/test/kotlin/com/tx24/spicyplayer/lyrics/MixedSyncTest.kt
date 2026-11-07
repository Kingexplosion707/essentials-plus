package com.tx24.spicyplayer.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class MixedSyncTest {
    /** A line of [spans], each its own timed word; a leading space in a span marks a word break. */
    private fun line(vararg spans: String) = TimedLine(0, 1_000, spans.map { TimedWord(it.trim(), 0, 1_000, attached = !it.startsWith(" ")) })

    @Test fun `Apple Music's mixed song times its Japanese lines whole and its English by word`() {
        // From LMYK - 0 (zero), as lrc.red serves it (issue #10).
        val lines = listOf(
            line("重なった記憶に迷いが消える"),
            line("鏡を抜けて触れ合うたびに"),
            line("You", " made", " me", " whole,", " you", " made", " me", " whole"),
            line("思い出す", " 孤独な私はいない"),
            line("もう"),
            line("Oh"),
            line("(I", " lose", " myself)"),
        )
        // Two phrase spans are still timed in parts, so that line stays word-synced.
        assertEquals(listOf(true, true, false, false, true, false, false), MixedSync.lineTimed(lines))
    }

    @Test fun `syllable-synced Japanese and character-synced Chinese stay word-synced`() {
        val japanese = line("鏡", "を", "抜け", "て", "触れ", "合う", "たび", "に")
        val chinese = line("我", "爱", "你", "中", "国")
        val held = line("ありふれる", "恋", "と", "違っ", "て")
        assertEquals(listOf(false, false, false), MixedSync.lineTimed(listOf(japanese, chinese, held)))
    }

    @Test fun `a song with no line timed whole leaves one-word lines word-synced`() {
        assertEquals(listOf(false, false), MixedSync.lineTimed(listOf(line("Oh"), line("I", " want", " you"))))
    }

    @Test fun `a word count reads CJK by the character and Latin by the space`() {
        assertEquals(1, MixedSync.words("触れ"))
        assertEquals(3, MixedSync.words("孤独な私はいない"))
        assertEquals(2, MixedSync.words("Baby 愛"))
    }
}
