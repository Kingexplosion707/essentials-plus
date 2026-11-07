package com.tx24.spicyplayer.lyrics.spicy.romanization

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.providers.GeniusRomanizationSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class HumanRomanizationTest {
    @Test fun oneSungLineTakesTwoGeniusLinesAndOneGeniusLineSplitsInTwo() {
        // mild-lyrics' own examples: Genius prints 「くわばら…速さ」 as two lines, and
        // 「今日何食べた？」「好きな本は？」 as one.
        val ours = listOf(
            "kuwabara kuwabara kuwabara me ni mo tomaran hayasa",
            "kyou nani tabeta",
            "suki na hon wa",
        )
        val genius = listOf(
            "Kuwabara, kuwabara, kuwabara",
            "Me ni mo tomaran hayasa, hey",
            "Kyou nani tabeta? Suki na hon wa?",
        )
        val mapping = HumanRomanization.align(ours, genius)
        assertEquals("Kuwabara, kuwabara, kuwabara Me ni mo tomaran hayasa, hey", mapping[0])
        assertEquals("Kyou nani tabeta?", mapping[1])
        assertEquals("Suki na hon wa?", mapping[2])
    }

    @Test fun bracketedBackgroundVocalsGoToTheBackgroundLine() {
        val ours = listOf(listOf("kimi", "ga", "suki"), listOf("suki"), listOf("mata", "ashita"))
        val cuts = HumanRomanization.apply(
            ours,
            listOf("Kimi ga suki (suki da yo)", "Mata ashita"),
            background = listOf(false, true, false),
            groups = listOf(0, 0, 1),
        )
        // A syllable that starts a word carries a leading space: the romaji wraps between words.
        assertEquals(listOf("Kimi", " ga", " suki"), cuts[0])
        assertEquals(listOf("suki da yo"), cuts[1])
        assertEquals(listOf("Mata", " ashita"), cuts[2])
    }

    @Test fun unrelatedLinesAreLeftAlone() {
        val mapping = HumanRomanization.align(listOf("sakura no hana", "totally different"), listOf("Sakura no hana", "Zzzz qqqq"))
        assertEquals("Sakura no hana", mapping[0])
        assertNull(mapping[1])
    }

    @Test fun oneGeniusWordIsCutAcrossSyllables() {
        // 響い / て / いる sung apart, "hibiiteiru" written as one word.
        assertEquals(listOf("hibii", "te", "iru"), HumanRomanization.retime(listOf("hibii", "te", "iru"), "hibiiteiru"))
        // Human reading where the machine's differs: 運命 sung "sadame".
        assertEquals(listOf("sadame", " no", " hi"), HumanRomanization.retime(listOf("unmei", "no", "hi"), "sadame no hi"))
    }

    @Test fun cleanLinesDropsHeadersNotesAndEmbedTail() {
        val text = "[Verse 1]\nKimi no koe\n(x2)\n\n(Ooh) mada kikoeru\nSayonara12Embed"
        assertEquals(listOf("Kimi no koe", "(Ooh) mada kikoeru", "Sayonara"), HumanRomanization.cleanLines(text))
    }

    @Test fun liveGeniusRomanization() = runBlocking {
        assumeTrue(System.getenv("RUN_GENIUS_ROMAN_TEST") == "1")
        val source = GeniusRomanizationSource(OkHttpClient(), Gson())
        for ((title, artist) in listOf("夜に駆ける" to "YOASOBI", "Lemon" to "米津玄師", "Dynamite" to "BTS")) {
            val lines = source.find(title, artist)
            println("$title / $artist: ${lines?.size ?: 0} lines; ${lines?.take(3)}")
            if (artist != "BTS") assertNotNull(lines)
        }
    }
}
