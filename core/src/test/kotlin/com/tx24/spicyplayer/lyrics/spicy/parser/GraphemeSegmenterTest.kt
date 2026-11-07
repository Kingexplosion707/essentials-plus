package com.tx24.spicyplayer.lyrics.spicy.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemeSegmenterTest {
    private fun segment(text: String) = GraphemeSegmenter.segment(text, sdkInt = 0)

    @Test
    fun `tamil conjunct stays one unit`() {
        assertEquals(listOf("ஸ்ரீ"), segment("ஸ்ரீ"))
    }

    @Test
    fun `tamil vowel sign stays with its consonant`() {
        assertEquals(listOf("கொ"), segment("கொ"))
    }

    @Test
    fun `devanagari conjunct stays one unit`() {
        assertEquals(listOf("क्ष"), segment("क्ष"))
    }

    @Test
    fun `devanagari word splits around its conjunct`() {
        assertEquals(listOf("न", "म", "स्ते"), segment("नमस्ते"))
    }

    @Test
    fun `emoji zwj family stays one unit`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(listOf(family), segment(family))
    }

    @Test
    fun `latin with a space is unchanged`() {
        assertEquals(listOf("a", " ", "b"), segment("a b"))
    }

    @Test
    fun `virama before a space keeps the space separate`() {
        assertEquals(listOf("स्", " ", "त"), segment("स् त"))
    }

    @Test
    fun `join helper merges split conjuncts`() {
        // The shape a character iterator returns when it doesn't treat a virama as conjunct-forming.
        assertEquals(listOf("ஸ்ரீ"), GraphemeSegmenter.joinConjuncts(listOf("ஸ்", "ரீ")))
        assertEquals(listOf("न", "म", "स्ते"), GraphemeSegmenter.joinConjuncts(listOf("न", "म", "स्", "ते")))
        assertEquals(listOf("क्ष्म"), GraphemeSegmenter.joinConjuncts(listOf("क्", "ष्", "म")))
    }

    @Test
    fun `join helper leaves whitespace and trailing virama alone`() {
        assertEquals(listOf("स्", " ", "त"), GraphemeSegmenter.joinConjuncts(listOf("स्", " ", "त")))
        assertEquals(listOf("a", "स्"), GraphemeSegmenter.joinConjuncts(listOf("a", "स्")))
        assertEquals(listOf("a", " ", "b"), GraphemeSegmenter.joinConjuncts(listOf("a", " ", "b")))
    }
}
