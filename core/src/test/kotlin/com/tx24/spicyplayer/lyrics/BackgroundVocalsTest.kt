package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundVocalsTest {
    /** A word-synced line, one second per word; a leading space marks a word break. */
    private fun words(vararg spans: String) = TimedLine(0, spans.size * 1_000L, spans.mapIndexed { i, text ->
        TimedWord(text.trim(), i * 1_000L, (i + 1) * 1_000L, attached = !text.startsWith(" ") && i > 0)
    })
    private fun text(line: TimedLine) = line.words.joinToString("") { (if (it.attached) "" else " ") + it.text }.trim()
    private fun shape(lines: List<TimedLine>) = lines.map { "${it.role.name.first()}:${text(it)}" }

    @Test fun `a bracket at a word-synced line's end becomes its background line`() {
        val lines = BackgroundVocals.split(listOf(words("I", " want", " you", " (I", " want", " you)")))
        assertEquals(listOf("L:I want you", "B:I want you"), shape(lines))
        assertEquals(lines[0].groupId, lines[1].groupId)
        assertEquals(3_000L to 6_000L, lines[1].startMs to lines[1].endMs)
        // The lead's window still covers its background vocals.
        assertEquals(6_000L, lines[0].endMs)
    }

    @Test fun `a bracket at the start splits off too, and both ends can`() {
        assertEquals(listOf("L:I want you", "B:Oh"), shape(BackgroundVocals.split(listOf(words("(oh)", " I", " want", " you")))))
        assertEquals(listOf("L:I want you", "B:Oh", "B:Yeah"),
            shape(BackgroundVocals.split(listOf(words("(oh)", " I", " want", " you", " (yeah)")))))
    }

    @Test fun `a line-synced line is cut by its text and keeps its timing`() {
        val line = TimedLine(5_000, 9_000, listOf(TimedWord("(Ooh) Every night (every night)", 5_000, 9_000, false)))
        val lines = BackgroundVocals.split(listOf(line))
        assertEquals(listOf("L:Every night", "B:Ooh", "B:Every night"), shape(lines))
        assertEquals(listOf(LineRole.LEAD, LineRole.BACKGROUND, LineRole.BACKGROUND), lines.map { it.role })
        lines.forEach { assertEquals(5_000L to 9_000L, it.startMs to it.endMs) }
    }

    @Test fun `whole bracketed lines, middle brackets and directions stay as they are`() {
        val kept = listOf(
            words("(I", " lose", " myself)"),
            words("I", " (really)", " want", " you"),
            words("Chorus", " again", " (x2)"),
            TimedLine(0, 1_000, listOf(TimedWord("Let it go (Repeat)", 0, 1_000, false))),
        )
        assertEquals(kept, BackgroundVocals.split(kept))
    }

    @Test fun `a direction at one end doesn't stop the other end splitting`() {
        assertEquals(listOf("L:I want you (x2)", "B:Oh"), shape(BackgroundVocals.split(listOf(words("(oh)", " I", " want", " you", " (x2)")))))
    }
}
