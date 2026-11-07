package com.tx24.spicyplayer.translation

import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.romanization.RomanizationMode
import org.junit.Assert.*
import org.junit.Test

class RomanizationNoteTest {
    private fun word(text: String, romanized: String?, start: Long, glued: Boolean = false) =
        Word(text, start, start + 500, isPartOfWord = glued, romanizedText = romanized)

    private fun assertMapping(line: Line, expected: String, texts: List<String>, offsets: List<Int>) {
        val note = romanizationNote(line)!!
        assertEquals(expected, note.text)
        assertEquals(texts, note.pieces.map { it.text })
        assertEquals(offsets, note.pieces.map { it.textOffset })
        assertEquals(line.words.indices.toList(), note.pieces.map { it.sourceWordIndex })
        note.pieces.forEachIndexed { index, piece ->
            assertSame(line.words[index], piece.word)
            assertEquals(piece.text, note.text.substring(piece.textOffset, piece.textOffset + piece.text.length))
        }
        assertEquals(expected, presentationSupplements(line, true, RomanizationMode.UnderLine, null, null).single())
    }

    @Test fun `Chinese syllables retain separate words and their pinyin spaces`() {
        val line = Line(listOf(word("你", "nǐ", 1000), word("不", "bù", 1500, true), word("出", "chū", 2000, true)), 1000)
        assertMapping(line, "nǐ bù chū", listOf("nǐ", "bù", "chū"), listOf(0, 3, 6))
        assertEquals(listOf(1000L, 1500L, 2000L), romanizationNote(line)!!.pieces.map { it.word.startMs })
    }

    @Test fun `Glued syllables whose romaji starts a word are spaced`() {
        // あなた の: written glued, romanized " no" since の is a word of its own.
        val line = Line(listOf(word("あ", "a", 1000), word("な", "na", 1500, true), word("た", "ta", 2000, true), word("の", " no", 2500, true)), 1000)
        assertMapping(line, "anata no", listOf("a", "na", "ta", "no"), listOf(0, 1, 3, 6))
    }

    @Test fun `Japanese multi-syllable words keep their glued pieces and original timing`() {
        val line = Line(listOf(word("ちょう", "chou", 1000), word("だい", "dai", 1500, true), word("ビーム", "biimu", 2000)), 1000)
        assertMapping(line, "choudai biimu", listOf("chou", "dai", "biimu"), listOf(0, 4, 8))
        // One source word may have a long reading; it still has one timing window.
        val whole = Line(listOf(word("世界", "sekai", 3000)), 3000)
        assertMapping(whole, "sekai", listOf("sekai"), listOf(0))
    }

    @Test fun `background vocals map to their own words rather than their lead`() {
        val lead = Line(listOf(word("声", "koe", 1000)), 1000, groupId = 7)
        val background = Line(listOf(word("あ", "a", 1250), word("あ", "a", 1750)), 1250,
            role = LineRole.BACKGROUND, groupId = 7, oppositeAligned = true)
        assertMapping(background, "a a", listOf("a", "a"), listOf(0, 2))
        assertNotSame(romanizationNote(lead)!!.pieces.single().word, romanizationNote(background)!!.pieces.first().word)
        assertEquals(1250L, romanizationNote(background)!!.pieces.first().word.startMs)
    }

    @Test fun `words without romanization keep their text and do not shift later source indices`() {
        val line = Line(listOf(word("愛", "ai", 1000), word("love", null, 1500), word("君", "kimi", 2000)), 1000)
        assertMapping(line, "ai love kimi", listOf("ai", "love", "kimi"), listOf(0, 3, 8))
        assertEquals(1500L, romanizationNote(line)!!.pieces[1].word.startMs)
    }

    @Test fun `empty readings retain source indices and existing spacing`() {
        val line = Line(listOf(word("あ", "a", 1000), word("", "", 1500, true), word("い", "i", 2000, true)), 1000)
        assertMapping(line, "ai", listOf("a", "", "i"), listOf(0, 1, 1))
    }

    @Test fun `RTL fallback text and duet alignment stay on the original line`() {
        val line = Line(listOf(word("مرحبا", null, 1000), word("君", "kimi", 1500)), 1000, oppositeAligned = true)
        assertMapping(line, "مرحبا kimi", listOf("مرحبا", "kimi"), listOf(0, 6))
        assertTrue(line.oppositeAligned)
    }

    @Test fun `off replace missing readings and interludes have no romanization note`() {
        val line = Line(listOf(word("君", "kimi", 1000)), 1000)
        assertTrue(presentationSupplements(line, false, RomanizationMode.UnderLine, null, null).isEmpty())
        assertTrue(presentationSupplements(line, true, RomanizationMode.Replace, null, null).isEmpty())
        assertNull(romanizationNote(line.copy(role = LineRole.INTERLUDE)))
        assertNull(romanizationNote(Line(listOf(word("original", null, 1000)), 1000)))
    }

    @Test fun `translation notes remain separate and replacements retain the original note mapping`() {
        val line = Line(listOf(word("君", "kimi", 1000)), 1000)
        assertEquals(listOf("kimi", "you"), presentationSupplements(line, true, RomanizationMode.UnderLine, "you", TranslationMode.UnderLine))
        val replacement = TranslationPresentation(listOf("you"), TranslationMode.Replace).displayLines(listOf(line)).single()
        assertEquals("you", replacement.words.single().text)
        assertEquals(line.startMs, replacement.startMs)
        assertEquals(line.endMs, replacement.endMs)
        assertTrue(replacement.translationReplaces)
        assertEquals(listOf("kimi"), presentationSupplements(line, true, RomanizationMode.UnderLine, "you", TranslationMode.Replace))
    }
}
