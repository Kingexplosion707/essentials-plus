package com.tx24.spicyplayer.translation

import com.google.gson.JsonParser
import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.TimedWord
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import com.tx24.spicyplayer.network.data.providers.SpicyLyricsTtmlConverter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Translations that come with the lyrics: TTML's x-translation spans and head entries, and the API's TranslatedText. */
class OwnTranslationTest {
    private fun parse(ttml: String) = TtmlLyricsParser.parse(ttml.byteInputStream())

    private val head = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">"""

    @Test fun `reads an inline translation and keeps it out of the words`() {
        val lyrics = parse("""$head<body><div>
            <p begin="00:12.300" end="00:15.800" itunes:key="L1" ttm:agent="v1"><span begin="00:12.300" end="00:12.900">Hola</span> <span begin="00:13.000" end="00:13.600">mundo</span><span ttm:role="x-translation" xml:lang="en">Hello world</span></p>
        </div></body></tt>""")
        val line = lyrics.lines.single()
        assertEquals("Hello world", line.translation)
        assertEquals(listOf("Hola", "mundo"), line.words.map { it.text })
        assertEquals("en", lyrics.translationLanguage)
    }

    @Test fun `reads head translations by key, backgrounds by their own key or in order`() {
        val lyrics = parse("""$head<head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><translations><translation type="subtitle" xml:lang="en">
            <text for="L1">Hello world<span ttm:role="x-bg">oh yes</span></text>
            <text for="L2">Goodbye</text>
            <text for="B2">see you</text>
        </translation></translations></iTunesMetadata></metadata></head><body><div>
            <p begin="1s" end="3s" itunes:key="L1"><span begin="1s" end="2s">Hola</span><span ttm:role="x-bg"><span begin="2s" end="3s">(oh sí)</span></span></p>
            <p begin="4s" end="6s" itunes:key="L2"><span begin="4s" end="5s">Adiós</span><span ttm:role="x-bg" itunes:key="B2"><span begin="5s" end="6s">(nos vemos)</span></span></p>
        </div></body></tt>""")
        assertEquals(listOf("Hello world", "oh yes", "Goodbye", "see you"), lyrics.lines.map { it.translation })
        assertEquals(LineRole.BACKGROUND, lyrics.lines[1].role)
        assertEquals("en", lyrics.translationLanguage)
    }

    @Test fun `an inline translation wins over the head's, and line-synced lyrics carry them too`() {
        val lyrics = parse("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><translations><translation><text for="L1">From the head</text><text for="L2">Second</text></translation></translations></iTunesMetadata></metadata></head>
            <body><div><p begin="1s" end="2s" itunes:key="L1">Uno<span ttm:role="x-translation">Inline</span></p><p begin="2s" end="3s" itunes:key="L2">Dos</p></div></body></tt>""")
        assertEquals(listOf("Uno", "Dos"), lyrics.lines.map { it.words.single().text })
        assertEquals(listOf("Inline", "Second"), lyrics.lines.map { it.translation })
        assertNull(lyrics.translationLanguage)
    }

    @Test fun `no translations leaves every line without one`() {
        val lyrics = parse("""$head<body><div><p begin="1s" end="2s"><span begin="1s" end="2s">Hola</span></p></div></body></tt>""")
        assertNull(lyrics.lines.single().translation)
        assertNull(lyrics.translationLanguage)
    }

    @Test fun `the API's TranslatedText becomes x-translation for leads, backgrounds and line-synced lines`() {
        val word = JsonParser.parseString("""{"Content":[
            {"Type":"Vocal","OppositeAligned":false,"Lead":{"Syllables":[{"Text":"Hola","IsPartOfWord":false,"StartTime":1,"EndTime":2}],"StartTime":1,"EndTime":2,"TranslatedText":"Hello"},
             "Background":[{"Syllables":[{"Text":"sí","IsPartOfWord":false,"StartTime":1.5,"EndTime":2.5}],"StartTime":1.5,"EndTime":2.5,"TranslatedText":"yes"}]}
        ]}""").asJsonObject
        val wordLines = parse(requireNotNull(SpicyLyricsTtmlConverter.convert(word, word.getAsJsonArray("Content")))).lines
        assertEquals(listOf("Hello", "yes"), wordLines.map { it.translation })
        assertEquals(listOf("Hola"), wordLines[0].words.map { it.text })

        val line = JsonParser.parseString("""{"Content":[{"Type":"Vocal","OppositeAligned":false,"Text":"Hola mundo","StartTime":1,"EndTime":3,"TranslatedText":"Hello world"}]}""").asJsonObject
        val lineLines = parse(requireNotNull(SpicyLyricsTtmlConverter.convert(line, line.getAsJsonArray("Content")))).lines
        assertEquals("Hola mundo", lineLines.single().words.single().text)
        assertEquals("Hello world", lineLines.single().translation)
    }

    @Test fun `static API bodies with translations keep them, as untimed TTML`() {
        val body = JsonParser.parseString("""{"Type":"Static","Lines":[{"Text":"Hola","TranslatedText":"Hello"},{"Text":"Adiós"}]}""").asJsonObject
        val payload = requireNotNull(com.tx24.spicyplayer.network.data.providers.spicyBodyPayload(body))
        assertEquals("Hola\nAdiós", payload.plainLyrics)
        val lyrics = parse(requireNotNull(payload.ttmlLyrics))
        assertEquals(com.tx24.spicyplayer.lyrics.spicy.models.LyricsType.Static, lyrics.type)
        assertEquals(listOf("Hola", "Adiós"), lyrics.lines.map { it.words.single().text })
        assertEquals(listOf("Hello", null), lyrics.lines.map { it.translation })
        // Without translations a static body stays plain text.
        val bare = JsonParser.parseString("""{"Type":"Static","Lines":[{"Text":"Hola"}]}""").asJsonObject
        assertNull(com.tx24.spicyplayer.network.data.providers.spicyBodyPayload(bare)!!.ttmlLyrics)
    }

    private fun document(language: String?, vararg lines: Pair<String, String?>) = TranslationDocument.from("song", "source", lines.map { (text, own) ->
        TimedLine(0, 1_000, listOf(TimedWord(text, 0, 1_000, false)), translation = own)
    }, language)

    private class NoCache : TranslationCache {
        override fun read(key: TranslationKey, lineCount: Int): TranslationResult? = null
        override fun write(result: TranslationResult) {}
    }

    private class Machine : Translator {
        override val provider = TranslationProvider.Google
        val requests = mutableListOf<TranslatorRequest>()
        override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
            requests += request
            return TranslatorResponse(request.lines.map { TranslationEntry("machine:$it") }, "es")
        }
    }

    private val english = TranslationPreferences(targetLanguage = "en", humanTranslations = false)

    @Test fun `own translations covering the song need no translator`() = runBlocking {
        val machine = Machine()
        val engine = TranslationEngine({ null }, NoCache(), pause = {})
        val result = engine.translate(document("en", "Hola" to "Hello", "Adiós" to "Goodbye"), english, "es", machine)
        result as TranslationOutcome.Translated
        assertEquals(listOf("Hello", "Goodbye"), result.result.texts)
        assertEquals("Lyrics 2", result.result.lineSources())
        assertEquals("Lyrics", result.result.credit())
        assertTrue(machine.requests.isEmpty())
    }

    @Test fun `gaps in own translations go to the translator, own lines win`() = runBlocking {
        val machine = Machine()
        val engine = TranslationEngine({ null }, NoCache(), pause = {})
        val result = engine.translate(document("en", "Hola" to "Hello", "Adiós" to null), english, "es", machine)
        result as TranslationOutcome.Translated
        assertEquals(listOf("Hello", "machine:Adiós"), result.result.texts)
        assertEquals(listOf(TranslationOrigin.Lyrics, TranslationOrigin.Google), result.result.origins)
    }

    @Test fun `a target other than the own translations' language translates every line`() = runBlocking {
        val machine = Machine()
        val engine = TranslationEngine({ null }, NoCache(), pause = {})
        val german = TranslationPreferences(targetLanguage = "de", humanTranslations = false)
        val result = engine.translate(document("en", "Hola" to "Hello", "Adiós" to "Goodbye"), german, "es", machine)
        result as TranslationOutcome.Translated
        assertEquals(listOf("machine:Hola", "machine:Adiós"), result.result.texts)
        assertTrue(TranslationOrigin.Lyrics !in result.result.origins)
    }

    @Test fun `own translations in another language than the target are left out`() {
        val engine = TranslationEngine({ null }, NoCache(), pause = {})
        val german = TranslationPreferences(targetLanguage = "de")
        assertNull(engine.ownTranslations(document("en", "Hola" to "Hello"), german))
        // Undeclared, their language is detected.
        val detecting = TranslationEngine({ text -> if (text == "Hello") "en" else "es" }, NoCache(), pause = {})
        assertEquals(listOf("Hello"), detecting.ownTranslations(document(null, "Hola" to "Hello"), english))
        assertNull(detecting.ownResult(document(null, "Hola" to "Hello"), german, "es"))
    }

    @Test fun `own lines win over a kept machine answer`() = runBlocking {
        val song = document("en", "Hola" to "Hello", "Adiós" to null)
        val engine = TranslationEngine({ null }, NoCache(), pause = {})
        val key = engine.key(song, english, "es")
        // Kept before the lyrics' own translations were used.
        val stored = TranslationResult(key, listOf("machine:Hola", "machine:Adiós"), "es")
        val kept = object : TranslationCache {
            override fun read(key: TranslationKey, lineCount: Int) = stored
            override fun write(result: TranslationResult) {}
        }
        val result = TranslationEngine({ null }, kept, pause = {}).translate(song, english, "es", Machine())
        result as TranslationOutcome.Translated
        assertEquals(listOf("Hello", "machine:Adiós"), result.result.texts)
        assertEquals(listOf(TranslationOrigin.Lyrics, TranslationOrigin.Google), result.result.origins)
    }

    @Test fun `undeclared own translations are detected even when the song's language isn't`() {
        // The API's TranslatedText says no language, and the app never guesses the song's.
        val engine = TranslationEngine({ null }, NoCache(), pause = {}, ownDetector = { text -> if (text == "Hello") "en" else null })
        val song = document(null, "Hola" to "Hello")
        assertNull(engine.language(song, null))
        assertEquals(listOf("Hello"), engine.ownResult(song, english, null)?.texts)
    }
}
