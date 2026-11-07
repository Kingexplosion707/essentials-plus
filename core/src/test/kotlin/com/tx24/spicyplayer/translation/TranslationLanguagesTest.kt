package com.tx24.spicyplayer.translation

import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.TimedWord
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLanguagesTest {
    @Test fun `alternative and regional codes become the listed one`() {
        mapOf(
            "fil" to "tl", "FIL" to "tl", "fil-PH" to "tl", "fil_PH" to "tl", "tl" to "tl", "tgl" to "tl",
            "iw" to "he", "he" to "he", "in" to "id", "ji" to "yi", "jw" to "jv", "mo" to "ro",
            "nb" to "no", "nn" to "no", "NB" to "no", "prs" to "fa",
            "zh-CN" to "zh", "zh-TW" to "zh", "zh-Hant" to "zh", "EN-us" to "en", " pt-BR " to "pt",
        ).forEach { (code, listed) -> assertEquals(code, listed, languageCode(code)) }
        listOf(null, "", " ", "und", "auto", "AUTO").forEach { assertNull(it, languageCode(it)) }
    }

    @Test fun `every listed code is already the one languageCode gives, and names are unique`() {
        TranslationLanguages.common.keys.forEach { assertEquals(it, it, languageCode(it)) }
        assertEquals(TranslationLanguages.common.size, TranslationLanguages.common.values.toSet().size)
    }

    @Test fun `codes saved before the list grew are all still listed`() {
        val earlier = listOf("en", "ar", "bg", "zh", "cs", "da", "nl", "fi", "fr", "de", "el", "he", "hi", "hu", "id",
            "it", "ja", "ko", "no", "pl", "pt", "ro", "ru", "sk", "es", "sv", "th", "tr", "uk", "vi")
        assertTrue(TranslationLanguages.common.keys.containsAll(earlier))
        listOf("tl", "ms", "fa", "bn", "ta", "ur", "te", "mr", "pa", "sw").forEach { assertTrue(it, it in TranslationLanguages.common) }
        assertEquals("Filipino", TranslationLanguages.name("fil"))
        assertEquals("Filipino", TranslationLanguages.name("tl"))
        assertEquals("Hebrew", TranslationLanguages.name("iw"))
    }

    @Test fun `Filipino is excluded whichever code the lyrics are detected as`() {
        assertTrue(skipTranslation("fil", "en", setOf("tl")))
        assertTrue(skipTranslation("tl", "en", setOf("fil")))
        assertTrue(skipTranslation("fil-PH", "en", setOf("tl")))
        assertTrue(skipTranslation("fil", "tl", emptySet()))
        assertTrue(skipTranslation("iw", "en", setOf("he")))
        assertTrue(skipTranslation("jw", "en", setOf("jv")))
        assertTrue(skipTranslation("zh-TW", "en", setOf("zh")))
        assertFalse(skipTranslation("id", "en", setOf("tl")))
        assertFalse(skipTranslation("und", "en", setOf("tl")))
    }

    @Test fun `the translate button shows only where a translation would help`() {
        val prefs = TranslationPreferences(targetLanguage = "en", excludedLanguages = setOf("tl"))
        assertFalse(translateButtonHelps(false, "en", prefs))
        assertFalse(translateButtonHelps(false, "fil", prefs))
        assertTrue(translateButtonHelps(false, "ko", prefs))
        // Not known yet: the button stays.
        assertTrue(translateButtonHelps(false, null, prefs))
        // While a translation shows, it stays to turn it off.
        assertTrue(translateButtonHelps(true, "en", prefs))
    }

    @Test fun `a song in two scripts counts as mixed, a stray word doesn't`() {
        val japanese = listOf("夢ならばどれほどよかったでしょう", "未だにあなたのことを夢にみる", "忘れた物を取りに帰るように", "古びた思い出の埃を払う")
        val english = listOf("I'm in the stars tonight", "So watch me bring the fire", "Shoes on, get up in the morn", "Cup of milk, let's rock and roll")
        assertTrue(mixedScripts(japanese + english))
        assertFalse(mixedScripts(japanese))
        assertFalse(mixedScripts(english + english + english + listOf("夢")))
        // Vietnamese's extra letters are Latin.
        assertFalse(mixedScripts(listOf("Em ơi Hà Nội phố", "Ta còn em mùi hoàng lan", "Ta còn em mùi hoa sữa", "Con đường vắng rì rào cơn mưa nhỏ")))
    }

    @Test fun `a cached translation drops lines in a newly excluded language under any of its codes`() {
        val key = TranslationKey("hash", "en", TranslationProvider.Google, null)
        val result = TranslationResult(key, listOf("I love you", "hello", "thank you"), "tl", languages = listOf("fil", "ko", null))
        assertEquals(listOf(null, "hello", null), result.without(setOf("tl")).texts)
        assertEquals(listOf(null, "hello", null), result.without(setOf("fil")).texts)
    }

    @Test fun `lyrics Google detects as Filipino are left alone when Filipino is excluded`() = runBlocking {
        val document = TranslationDocument.from("song", "source", listOf("Mahal kita", "Salamat po").map {
            TimedLine(1_000, 2_000, listOf(TimedWord(it, 1_000, 2_000, false)))
        })
        for (detected in listOf("tl", "fil")) {
            for (excluded in listOf("tl", "fil")) {
                val translator = object : Translator {
                    override val provider = TranslationProvider.Google
                    override suspend fun translate(request: TranslatorRequest) =
                        googleChromeResponse(request.lines.joinToString(",", "[", "]") { "[\"translated\",\"$detected\"]" }, request.source)
                }
                val outcome = TranslationEngine(SongLanguageDetector { null }, NoCache, pause = {})
                    .translate(document, TranslationPreferences(targetLanguage = "en", excludedLanguages = setOf(excluded), humanTranslations = false), null, translator)
                assertEquals("$detected / $excluded", TranslationOutcome.Skipped("tl"), outcome)
            }
        }
    }

    @Test fun `DeepL is offered only the targets it can translate into, plus the saved one`() {
        val deepL = TranslationLanguages.targets(TranslationProvider.DeepL).map { it.first }
        assertTrue("tl" in deepL && "vi" in deepL && "hi" in deepL)
        assertFalse("km" in deepL)
        assertTrue("km" in TranslationLanguages.targets(TranslationProvider.DeepL, "km").map { it.first })
        assertEquals(TranslationLanguages.common.keys, TranslationLanguages.targets(TranslationProvider.Google).map { it.first }.toSet())
        val names = TranslationLanguages.all().map { it.second }
        assertEquals(names.sorted(), names)
        // A saved code under an old spelling shows once, as the listed language.
        assertEquals(TranslationLanguages.common.size, TranslationLanguages.all(setOf("fil", "iw")).size)
        assertTrue("eo" in TranslationLanguages.all(setOf("eo")).toMap())
    }

    @Test fun `DeepL refuses a target it lacks and detects a source it lacks`() {
        val key = "0123456789abcdef:fx"
        val failure = assertThrows(TranslationFailure::class.java) { deepLRequest(TranslatorRequest(listOf("text"), "km", null, ""), key) }
        assertTrue(failure.message!!.contains("Khmer"))
        val fields = deepLRequest(TranslatorRequest(listOf("text"), "tl", "km", ""), key).body as FormBody
        val named = (0 until fields.size).associate { fields.name(it) to fields.value(it) }
        assertEquals("TL", named["target_lang"])
        assertNull(named["source_lang"])
    }

    @Test fun `Google gets the codes its endpoints use`() {
        val chrome = googleChromeRequest(TranslatorRequest(listOf("שלום"), "jv", "he", ""))
        assertEquals("iw", chrome.url.queryParameter("sl"))
        assertEquals("jw", chrome.url.queryParameter("tl"))
        val web = googleRequest(TranslatorRequest(listOf("Mahal kita"), "tl", null, ""))
        assertEquals("auto", web.url.queryParameter("sl"))
        assertEquals("tl", web.url.queryParameter("tl"))
    }

    private object NoCache : TranslationCache {
        override fun read(key: TranslationKey, lineCount: Int): TranslationResult? = null
        override fun write(result: TranslationResult) = Unit
    }
}
