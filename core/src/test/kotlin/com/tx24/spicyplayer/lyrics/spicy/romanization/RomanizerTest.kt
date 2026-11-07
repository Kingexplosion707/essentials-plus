package com.tx24.spicyplayer.lyrics.spicy.romanization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanizerTest {
    @Test fun koreanRevisedRomanization() {
        assertEquals("annyeonghaseyo", KoreanRomanizer.romanize("안녕하세요"))
    }

    @Test fun cyrillicAndGreek() {
        assertEquals("privet", CyrillicRomanizer.romanize("привет"))
        assertEquals("kalimera", GreekRomanizer.romanize("καλημέρα"))
    }

    @Test fun kanaHandlesYouonAndSokuon() {
        assertEquals("kya", KanaRomanizer.romanize("きゃ"))
        assertEquals("kitto", KanaRomanizer.romanize("きっと"))
    }

    @Test fun kanjiOnlyWordInJapaneseSongIsNotPinyin() {
        // "心" alone reads as Chinese; the song's kana makes it Japanese.
        val out = RomanizationService.romanize(listOf(listOf("君と"), listOf("心"))).map { it.single() }
        assertTrue(out[1] != null && out[1] != "xin")
    }

    @Test fun hanOnlySongIsPinyin() {
        assertEquals(listOf(listOf("xīn")), RomanizationService.romanize(listOf(listOf("心"))))
    }

    @Test fun latinIsLeftAlone() {
        assertNull(RomanizationService.romanize(listOf(listOf("hello"))).single().single())
    }

    @Test fun composesEveryPresentScript() {
        val out = RomanizationService.romanize(listOf(listOf("Привет κόσμος"))).single().single()
        assertTrue(out != null && out.none { it in 'Ͱ'..'ԯ' })
    }

    @Test fun syllableSplitJapaneseUsesLineContext() {
        // Some lyrics split 段々堕ちてく one character per syllable; read alone, 々 and 堕 stay unromanized.
        val syllables = listOf("段", "々", "堕", "ち", "て", "く", "I", "Q", "ク", "ソ", "ワ", "ロ")
        val out = RomanizationService.romanize(listOf(syllables)).single()
        // 段々 / 堕ち / て / く: a syllable that starts a word carries a leading space.
        assertEquals(listOf("dan", "dan", " o", "chi", " te", " ku"), out.take(6))
        assertTrue(out.none { it != null && it.any { c -> c in '぀'..'鿿' } })
    }

    @Test fun koreanMatchesAromanizeTranscription() {
        // Expected values from aromanize-js 1.0.0 "RevisedRomanizationTranscription".
        mapOf(
            "사랑해" to "saranghae", "좋아" to "joha", "없어" to "eopseo", "한국어 노래" to "hangugeo norae",
            "밤하늘" to "bamhaneul", "괜찮아" to "gwaenchanha", "꽃잎" to "kkochip",
        ).forEach { (hangul, roman) -> assertEquals(hangul, roman, KoreanRomanizer.romanize(hangul)) }
    }

    @Test fun chineseMatchesSpicyLyricsPinyin() {
        // Expected values from pinyin 4.0.0, pinyin(text, { segment: false, group: true }).join("-").
        mapOf(
            "你好 world" to "nǐ-hǎo- world", "了" to "le", "的" to "de", "长" to "cháng",
            "还" to "huán", "着" to "zháo", "為" to "wéi",
        ).forEach { (han, roman) -> assertEquals(han, roman, PinyinRomanizer.romanize(han)) }
    }

    @Test fun japaneseReadsPronunciationLikeKuroshiro() {
        // kuroshiro "spaced" romaji: particles by sound, long vowels as macrons, n' before vowels.
        assertEquals("watashi wa", JapaneseRomanizer.romanize("私は"))
        assertEquals("tōkyō e", JapaneseRomanizer.romanize("東京へ"))
        assertEquals("kin'en", KanaRomanizer.romanize("きんえん"))
        assertEquals("shimbun", KanaRomanizer.romanize("しんぶん"))
        assertEquals("matcha", KanaRomanizer.romanize("まっちゃ"))
    }

    @Test fun lineSyllablesKeepGeminationAndLongVowels() {
        // The っ's consonant goes where the っ is sung.
        assertEquals(listOf("kit", "to"), JapaneseRomanizer.romanizeLine(listOf("きっ", "と")))
        assertEquals(listOf("ki", "tto"), JapaneseRomanizer.romanizeLine(listOf("き", "っと")))
    }

    @Test fun syllableSeamsKeepEverySound() {
        fun line(vararg syllables: String) = JapaneseRomanizer.romanizeLine(syllables.toList()).map(String::trim)
        assertEquals(listOf("damat", "te iro"), line("黙っ", "ていろ"))
        assertEquals(listOf("hak", "ka"), line("発", "火"))
        assertEquals(listOf("mat", "cha"), line("待っ", "ちゃ"))
        // Before a vowel or the end of the line, a っ is a stop and unwritten.
        assertEquals(listOf("ma", "ik", "ka"), line("まっ", "いっ", "か"))
        assertEquals(listOf("a"), line("あっ"))
        // 々 repeats the kanji before it; a bracketed reading is the kanji's, said once.
        assertEquals(listOf("san", "san"), line("燦", "々"))
        assertEquals(listOf("itan", "kabu", "ke ba"), line("異端", "傾(かぶ)", "けば"))
    }
}
