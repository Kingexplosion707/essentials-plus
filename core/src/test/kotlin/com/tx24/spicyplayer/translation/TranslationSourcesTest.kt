package com.tx24.spicyplayer.translation

import com.google.gson.JsonParser
import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.TimedWord
import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsFooter
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.romanization.RomanizationMode
import com.tx24.spicyplayer.network.data.SourceDisclosures
import com.tx24.spicyplayer.network.data.providers.geniusLyricsText
import com.tx24.spicyplayer.network.data.providers.linkedTranslations
import com.tx24.spicyplayer.network.data.providers.GeniusSongPages
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslationSourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun document(vararg text: String) = TranslationDocument.from("song", "source", text.map {
        TimedLine(0, 1_000, listOf(TimedWord(it, 0, 1_000, false)))
    })
    private val preferences = TranslationPreferences(targetLanguage = "en")
    private class Cache : TranslationCache {
        var result: TranslationResult? = null
        override fun read(key: TranslationKey, lineCount: Int) = result?.takeIf { it.key == key && it.texts.size == lineCount }
        override fun write(result: TranslationResult) { this.result = result }
    }
    private class Machine : Translator {
        override val provider = TranslationProvider.Google
        val requests = mutableListOf<TranslatorRequest>()
        override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
            requests += request
            return TranslatorResponse(request.lines.map { TranslationEntry("machine:$it") }, "ja")
        }
    }

    @Test fun `Google posts original whole-song text in q and languages in the query`() {
        val request = googleRequest(TranslatorRequest(listOf("こんにちは", "世界 & friends"), "it", null, "ignored"))
        assertEquals("POST", request.method)
        assertEquals("translate.googleapis.com", request.url.host)
        assertEquals("/translate_a/single", request.url.encodedPath)
        assertEquals("gtx", request.url.queryParameter("client"))
        assertEquals("t", request.url.queryParameter("dt"))
        assertEquals("1", request.url.queryParameter("dj"))
        assertEquals("auto", request.url.queryParameter("sl"))
        assertEquals("it", request.url.queryParameter("tl"))
        val body = request.body as FormBody
        assertEquals(1, body.size)
        assertEquals("q", body.name(0))
        assertEquals("こんにちは\n世界 & friends", body.value(0))
        assertFalse(request.url.toString().contains("friends"))
        assertEquals("ja", googleRequest(TranslatorRequest(listOf("世界"), "en", "ja", "")).url.queryParameter("sl"))
    }

    @Test fun `Google sentences concatenate before splitting and preserve empty positions`() {
        val response = googleResponse("""{"sentences":[{"trans":"Hello\n","orig":"こんにちは\n"},{"trans":"\nWorld"}],"src":"ja"}""")
        assertEquals("ja", response.detectedLanguage)
        assertEquals(listOf("Hello", null, "World"), response.lines.map { it?.text })
        assertEquals(listOf("Hello world"), googleResponse("""{"sentences":[{"trans":"Hello "},{"trans":"world"}],"src":"ja"}""").lines.map { it?.text })
        assertTrue(runCatching { googleResponse("""{"sentences":[{"orig":"missing translation"}]}""") }.isFailure)
    }

    /** A Google client whose two endpoints answer [chrome] and [web] (status, body). */
    private fun google(chrome: Pair<Int, String>, web: Pair<Int, String>, asked: MutableList<String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val path = chain.request().url.encodedPath
        asked += path
        val (code, body) = if (path.endsWith("/t")) chrome else web
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody()).build()
    }.build()

    @Test fun `Google asks Chrome's endpoint first and the web one only when that one is limited`() = runBlocking {
        val request = TranslatorRequest(listOf("ciao", "mondo"), "en", null, "ciao\nmondo")
        val asked = mutableListOf<String>()
        val chrome = GoogleTranslator(google(200 to """[["hello","it"],["world","it"]]""", 500 to "", asked)).translate(request)
        assertEquals(listOf("hello", "world"), chrome.lines.map { it?.text })
        assertEquals("it", chrome.detectedLanguage)
        assertEquals(listOf("/translate_a/t"), asked)
        asked.clear()
        val web = GoogleTranslator(google(429 to "", 200 to """{"sentences":[{"trans":"hello\nworld"}],"src":"it"}""", asked)).translate(request)
        assertEquals(listOf("hello", "world"), web.lines.map { it?.text })
        assertEquals(listOf("/translate_a/t", "/translate_a/single"), asked)
        asked.clear()
        try {
            GoogleTranslator(google(429 to "", 429 to "", asked)).translate(request)
            fail("Both endpoints limiting the connection is busy")
        } catch (_: ProviderBusy) {
        }
    }

    @Test fun `Google's answer with a given language is a bare string`() {
        assertEquals(listOf("hello"), googleChromeResponse("""["hello"]""", "it").lines.map { it?.text })
        assertEquals("it", googleChromeResponse("""["hello"]""", "it").detectedLanguage)
    }

    @Test fun `a mismatched song is asked again by verse, never line by line`() = runBlocking {
        val input = document("一行目", "二行目", "", "三行目", "四行目")
        val requests = mutableListOf<List<String>>()
        val translator = object : Translator {
            override val provider = TranslationProvider.Google
            override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
                requests += request.lines
                return if (request.lines.size > 2 || "三行目" in request.lines) TranslatorResponse(emptyList(), "ja")
                else TranslatorResponse(request.lines.map { TranslationEntry("t:$it") }, "ja")
            }
        }
        val result = TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {})
            .translate(input, preferences, null, translator) as TranslationOutcome.Translated
        assertEquals(listOf(listOf("一行目", "二行目", "三行目", "四行目"), listOf("一行目", "二行目"), listOf("三行目", "四行目")), requests)
        assertEquals(listOf("t:一行目", "t:二行目", null, null, null), result.result.texts)
    }

    @Test fun `a limited Google is left alone for a while instead of retried`() = runBlocking {
        var clock = 0L
        var calls = 0
        val translator = object : Translator {
            override val provider = TranslationProvider.Google
            override suspend fun translate(request: TranslatorRequest): TranslatorResponse { calls++; throw ProviderBusy() }
        }
        val engine = TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {}, now = { clock })
        val first = runCatching { engine.translate(document("一行目"), preferences, null, translator) }.exceptionOrNull()
        assertTrue(first is TranslationFailure)
        assertEquals(1, calls)
        clock += 60_000L
        runCatching { engine.translate(document("二行目"), preferences, null, translator) }
        assertEquals("No request while it rests", 1, calls)
        clock += 10 * 60_000L
        runCatching { engine.translate(document("三行目"), preferences, null, translator) }
        assertEquals(2, calls)
    }

    @Test fun `old provider names migrate but Unison consent never grants Google or Genius consent`() {
        for (stored in listOf(null, "Unison", "unknown", "")) assertEquals(TranslationProvider.Google, TranslationProvider.stored(stored))
        assertEquals(TranslationProvider.DeepL, TranslationProvider.stored("DeepL"))
        assertFalse(TranslationConsent.covered(setOf("Unison", "translation_unison"), TranslationProvider.Google, false))
        assertFalse(TranslationConsent.covered(setOf("translation_google"), TranslationProvider.Google, true))
        assertFalse(TranslationConsent.covered(setOf("DeepL"), TranslationProvider.DeepL, true))
        assertTrue(TranslationConsent.covered(setOf("translation_google_genius"), TranslationProvider.Google, false))
        val notice = SourceDisclosures.translation(TranslationProvider.Google, true)
        assertTrue(notice.description.contains("free web service, not a paid API"))
        assertTrue(notice.description.contains("Genius"))
        assertFalse(SourceDisclosures.translation(TranslationProvider.Google, false).description.contains("Genius"))
    }

    @Test fun `two sung lines merged into one translated line both get it`() {
        val original = "[Verse 1]\nどんなに今日を生き抜いても\n報われぬ毎日の中で\n[Chorus]\n夜が来る\n星が光る\n[Bridge]\n声が響く"
        val translation = "[Verse 1]\nHowever hard I survive today, it goes unrewarded every single day\n[Chorus]\nNight comes\nStars shine\n[Bridge]\nVoices echo"
        val pair = GeniusTranslationPair.fromText(original, translation, "ja")!!
        assertEquals(listOf("どんなに今日を生き抜いても", "報われぬ毎日の中で", "夜が来る", "星が光る", "声が響く"), pair.originals)
        assertEquals("However hard I survive today, it goes unrewarded every single day", pair.translations[0])
        assertEquals(pair.translations[0], pair.translations[1])
        assertEquals(listOf("Night comes", "Stars shine", "Voices echo"), pair.translations.drop(2))
    }

    @Test fun `one line split in two gets both, and an extra title block or section doesn't sink the page`() {
        val original = "[Song title 歌詞]\n\n[Intro]\n雨の日も風の日もずっとあなたのそばにいたい\n[Verse]\n夜が来る\n星が光る\n[Outro]\n声が響く"
        val translation = "[Intro]\nOn rainy days and windy days\nI want to stay by your side forever\n[Verse]\nNight comes\nStars shine\n[Outro]\nVoices echo"
        val pair = GeniusTranslationPair.fromText(original, translation, "ja")!!
        assertEquals(listOf("雨の日も風の日もずっとあなたのそばにいたい", "夜が来る", "星が光る", "声が響く"), pair.originals)
        assertEquals("On rainy days and windy days I want to stay by your side forever", pair.translations[0])
    }

    @Test fun `Genius page pairs strip headers equally`() {
        val original = geniusLyricsText("""<div data-lyrics-container="true">[Verse 1]<br/>夜が来る<br/><br/>星が光る</div>""")
        val pair = GeniusTranslationPair.fromText(original, "[Verse]\nNight comes\n\nStars shine", "ja")!!
        assertEquals(listOf("夜が来る", "星が光る"), pair.originals)
        assertEquals(listOf("Night comes", "Stars shine"), pair.translations)
    }

    @Test fun `Genius linked language selects the target without guessing from the title`() {
        val song = JsonParser.parseString("""{"translation_songs":[
            {"language":"romanization","url":"roman"},{"language":"it","url":"italian"},
            {"language":"en","url":"english"},{"title":"English Translation","url":"no-language"}]}""").asJsonObject
        assertEquals(listOf("english"), linkedTranslations(song, "en-US").map { it.get("url").asString })
        assertEquals(listOf("italian"), linkedTranslations(song, "it").map { it.get("url").asString })
        assertTrue(linkedTranslations(song, "fr").isEmpty())
    }

    @Test fun `a sung cover in the same language is never taken for the translation`() {
        val song = JsonParser.parseString("""{"translation_songs":[
            {"language":"en","title":"Mesmerizer (Official English Version)","url":"cover"},
            {"language":"en","title":"サツキ (Satsuki) - メズマライザー (Mesmerizer) (English Translation)","url":"translation"}]}""").asJsonObject
        assertEquals(listOf("translation"), linkedTranslations(song, "en").map { it.get("url").asString })
    }

    @Test fun `a Genius song matches through a featured artist and a bracketed title`() {
        val pages = GeniusSongPages(okhttp3.OkHttpClient(), com.google.gson.Gson())
        val hit = JsonParser.parseString("""{"title":"メズマライザー (Mesmerizer)",
            "primary_artist":{"name":"サツキ (Satsuki)"},
            "artist_names":"サツキ (Satsuki) (Ft. 初音ミク (Hatsune Miku) & 重音テト (Kasane Teto))"}""").asJsonObject
        assertTrue(pages.ours(hit, "メズマライザー (feat. 初音ミク&重音テト)", "32ki, Hatsune Miku, 重音テト"))
        val cover = JsonParser.parseString("""{"title":"【MESMERIZER/メズマライザー】(by 32ki) ENGLISH COVER",
            "primary_artist":{"name":"Artsythesecond"}, "artist_names":"Artsythesecond (Ft. Razaplays)"}""").asJsonObject
        assertFalse(pages.ours(cover, "メズマライザー (feat. 初音ミク&重音テト)", "32ki, Hatsune Miku, 重音テト"))
        assertFalse(pages.ours(hit, "メズマライザー", "Someone Else"))
    }

    @Test fun `original-script alignment survives skips and a collapsed repeated chorus`() {
        val input = document("夜が来る", "星が光る", "私だけの行", "歩き続ける", "夜が来る", "星が光る", "朝が来る")
        val pair = GeniusTranslationPair.fromText("[Chorus]\n夜が来る\n星が光る\n[Verse]\n歩き続ける\n朝が来る",
            "[Chorus]\nNight comes\nStars shine\n[Verse]\nKeep walking\nMorning comes", "ja")!!
        assertEquals(listOf("Night comes", "Stars shine", null, "Keep walking", "Night comes", "Stars shine", "Morning comes"), HumanTranslation.align(input, pair))
        val ambiguous = GeniusTranslationPair(listOf("夜が来る", "夜が来る"), listOf("Night comes", "Darkness falls"), "ja")
        assertEquals(listOf(null), HumanTranslation.align(document("夜が来る"), ambiguous))
    }

    @Test fun `Genius background runs stay separate and use machine fallback`() = runBlocking {
        val input = TranslationDocument.from("song", "source", listOf(
            TimedLine(0, 1_000, listOf(TimedWord("夜が来る", 0, 1_000, false)), groupId = 2),
            TimedLine(0, 1_000, listOf(TimedWord("声が響く", 0, 1_000, false)), role = LineRole.BACKGROUND, groupId = 2),
        ))
        val pair = GeniusTranslationPair.fromText("夜が来る (声が響く)", "Night comes (Voices echo)", "ja")!!
        val machine = Machine()
        val result = (TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {}).translate(input, preferences, null, machine) { pair } as TranslationOutcome.Translated).result
        assertEquals(listOf("Night comes", "machine:声が響く"), result.texts)
        assertEquals(listOf(TranslationOrigin.Genius, TranslationOrigin.Google), result.origins)
        assertEquals(input.lines.map { it.text }, machine.requests.single().lines)
        assertEquals("Genius + Google", result.credit())
        assertEquals("Translation from: Genius + Google", LyricsFooter(translationCredit = result.credit()).lines().single().text)
    }

    @Test fun `complete human coverage makes zero machine calls and disk hits retain origins`() = runBlocking {
        val input = document("夜が来る", "", "星が光る")
        val pair = GeniusTranslationPair.fromText("夜が来る\n星が光る", "Night comes\nStars shine", "ja")!!
        val cache = DiskTranslationCache(temporary.newFolder())
        val machine = Machine()
        val engine = TranslationEngine(SongLanguageDetector { null }, cache, pause = {})
        val result = (engine.translate(input, preferences, null, machine) { pair } as TranslationOutcome.Translated).result
        assertEquals(listOf("Night comes", null, "Stars shine"), result.texts)
        assertEquals(listOf(TranslationOrigin.Genius, null, TranslationOrigin.Genius), result.origins)
        assertEquals("Genius", result.credit())
        assertEquals(result, (engine.translate(input, preferences, null, machine) { error("Cache should avoid Genius too") } as TranslationOutcome.Translated).result)
        assertTrue(machine.requests.isEmpty())
    }

    @Test fun `a slow human lookup shows the machine translation first, then upgrades it`() = runBlocking {
        val input = document("夜が来る", "違う歌詞", "星が光る")
        val pair = GeniusTranslationPair.fromText("夜が来る\n星が光る", "Night comes\nStars shine", "ja")!!
        val partials = mutableListOf<List<String?>>()
        val result = (TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {})
            .translate(input, preferences, null, Machine(), onPartial = { partials.add(it.texts) }) {
                kotlinx.coroutines.delay(600)
                pair
            } as TranslationOutcome.Translated).result
        assertEquals(listOf(listOf("machine:夜が来る", "machine:違う歌詞", "machine:星が光る")), partials)
        assertEquals(listOf("Night comes", "machine:違う歌詞", "Stars shine"), result.texts)
    }

    @Test fun `mixed combination preserves gaps and disabling human translations has its own cache key`() = runBlocking {
        val input = document("夜が来る", "違う歌詞", "星が光る")
        val pair = GeniusTranslationPair.fromText("夜が来る\n星が光る", "Night comes\nStars shine", "ja")!!
        val engine = TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {})
        val machine = Machine()
        val result = (engine.translate(input, preferences, null, machine) { pair } as TranslationOutcome.Translated).result
        assertEquals(listOf("Night comes", "machine:違う歌詞", "Stars shine"), result.texts)
        assertEquals(listOf(TranslationOrigin.Genius, TranslationOrigin.Google, TranslationOrigin.Genius), result.origins)
        assertNotEquals(engine.key(input, preferences, null).fileName(), engine.key(input, preferences.copy(humanTranslations = false), null).fileName())
        val disabled = (engine.translate(input, preferences.copy(humanTranslations = false), null, machine) { error("Disabled") } as TranslationOutcome.Translated).result
        assertEquals(listOf("machine:夜が来る", "machine:違う歌詞", "machine:星が光る"), disabled.texts)
    }

    @Test fun `Genius disk cache remembers found and missing pairs by song and language`() = runBlocking {
        val folder = temporary.newFolder()
        var now = 1_000L
        val cache = DiskGeniusTranslationCache(folder) { now }
        var calls = 0
        val pair = GeniusTranslationPair.fromText("夜が来る", "Night comes", "ja")!!
        assertEquals(pair, cache.find("song", "artist", "en") { calls++; pair })
        assertEquals(pair, DiskGeniusTranslationCache(folder) { now }.find("song", "artist", "en") { error("Disk hit") })
        assertNull(cache.find("song", "artist", "it") { calls++; null })
        assertNull(DiskGeniusTranslationCache(folder) { now }.find("song", "artist", "it") { error("Negative disk hit") })
        assertEquals(2, calls)
        now += 86_400_001L
        cache.find("song", "artist", "it") { calls++; null }
        assertEquals(3, calls)
        cache.forget("song", "artist")
        cache.find("song", "artist", "en") { calls++; pair }
        assertEquals(4, calls)
        // A lookup that failed isn't remembered as missing.
        assertTrue(runCatching { cache.find("song", "artist", "de") { calls++; throw java.io.IOException("limited") } }.isFailure)
        assertEquals(pair, cache.find("song", "artist", "de") { calls++; pair })
        assertEquals(6, calls)
    }

    /** A client answering every request with [code] and [body], and [headers]. */
    private fun answering(code: Int, body: String = "", vararg headers: Pair<String, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody())
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
    }.build()

    @Test fun `Google gets each line as its own q and answers with each line's language`() {
        val request = googleChromeRequest(TranslatorRequest(listOf("사랑해", "I love you"), "en", null, ""))
        val body = request.body as FormBody
        assertEquals(listOf("q" to "사랑해", "q" to "I love you"), (0 until body.size).map { body.name(it) to body.value(it) })
        assertEquals("auto", request.url.queryParameter("sl"))
        val response = googleChromeResponse("""[["I love you","ko"],["I love you","en"],["","en"]]""", null)
        assertEquals(listOf("ko", "en", "en"), response.lines.map { it?.language })
        assertEquals(listOf("I love you", "I love you", null), response.lines.map { it?.text })
        assertEquals(listOf("hello", "world"), googleChromeResponse("""["hello","world"]""", "it").lines.map { it?.text })
    }

    @Test fun `a limited Google or DeepL passes on how long it asked to wait`() = runBlocking {
        val request = TranslatorRequest(listOf("ciao"), "en", null, "")
        val google = runCatching { GoogleTranslator(answering(429, "", "Retry-After" to "90")).translate(request) }.exceptionOrNull()
        assertEquals(90_000L, (google as ProviderBusy).retryAfterMs)
        assertNull((runCatching { GoogleTranslator(answering(429)).translate(request) }.exceptionOrNull() as ProviderBusy).retryAfterMs)
        val deepL = runCatching { DeepLTranslator(answering(429, "", "Retry-After" to "5"), "abcdefghijklmnopq:fx").translate(request) }.exceptionOrNull()
        assertEquals(5_000L, (deepL as ProviderBusy).retryAfterMs)
        val quota = runCatching { DeepLTranslator(answering(456), "abcdefghijklmnopq:fx").translate(request) }.exceptionOrNull()
        assertTrue(quota is TranslationFailure)
    }

    @Test fun `DeepL tells each line's language so a mixed song's English stays as it is`() = runBlocking {
        val answer = """{"translations":[
            {"detected_source_language":"KO","text":"I miss you"},
            {"detected_source_language":"EN","text":"I want you back tonight!"},
            {"detected_source_language":"KO","text":"Love you"}]}"""
        val request = TranslatorRequest(listOf("보고 싶어", "I want you back tonight", "사랑해"), "en", null, "보고 싶어\nI want you back tonight\n사랑해")
        val response = DeepLTranslator(answering(200, answer), "abcdefghijklmnopq:fx").translate(request)
        assertEquals(listOf("ko", "en", "ko"), response.lines.map { it?.language })
        assertEquals("ko", response.detectedLanguage)
        val engine = TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {})
        val deepL = object : Translator {
            override val provider = TranslationProvider.DeepL
            override suspend fun translate(request: TranslatorRequest) = response
        }
        val result = engine.translate(document(*request.lines.toTypedArray()), preferences.copy(provider = TranslationProvider.DeepL, humanTranslations = false), null, deepL)
        assertEquals(listOf("I miss you", null, "Love you"), (result as TranslationOutcome.Translated).result.texts)
        assertEquals("DeepL", result.result.credit())
    }

    @Test fun `a Genius outage is an error, not a song without a translation`() = runBlocking {
        val gson = com.google.gson.Gson()
        val limited = com.tx24.spicyplayer.network.data.providers.GeniusTranslationSource(answering(429), gson)
        assertTrue(runCatching { limited.find("夜に駆ける", "YOASOBI", "en") }.isFailure)
        val empty = com.tx24.spicyplayer.network.data.providers.GeniusTranslationSource(
            answering(200, """{"response":{"sections":[]}}"""), gson)
        assertNull(empty.find("夜に駆ける", "YOASOBI", "en"))
    }

    @Test fun `a Genius line that just repeats the original isn't shown under it`() {
        val input = document("夜が来る", "I want you back", "星が光る")
        val pair = GeniusTranslationPair.fromText("夜が来る\nI want you back\n星が光る", "Night comes\nI want you back\nStars shine", "ja")!!
        assertEquals(listOf("Night comes", null, "Stars shine"), HumanTranslation.align(input, pair))
    }

    @Test fun `romanization and translation independently choose all four presentations`() {
        val original = Line(listOf(Word("こん", 0, 500, romanizedText = "kon"),
            Word("にちは", 500, 1_000, isPartOfWord = true, romanizedText = "nichiwa")), 0, 1_000)
        for (romanization in RomanizationMode.entries) {
            for (translation in TranslationMode.entries) {
                val display = TranslationPresentation(listOf("Hello"), translation).displayLines(listOf(original)).single()
                val supplements = presentationSupplements(original, true, romanization, "Hello", translation)
                val expected = (if (romanization == RomanizationMode.UnderLine) listOf("konnichiwa") else emptyList()) +
                    (if (translation == TranslationMode.UnderLine) listOf("Hello") else emptyList())
                assertEquals(expected, supplements)
                assertEquals(translation == TranslationMode.Replace, display.translationReplaces)
                if (translation == TranslationMode.UnderLine) assertSame(original, display)
                else assertEquals("Hello", display.words.single().text)
            }
        }
        assertEquals(listOf("konnichiwa"), presentationSupplements(original, true, RomanizationMode.UnderLine, null, null))
        assertTrue(presentationSupplements(original, true, RomanizationMode.Replace, null, null).isEmpty())
        assertEquals(listOf("Hello"), presentationSupplements(original, false, RomanizationMode.UnderLine, "Hello", TranslationMode.UnderLine))
        assertSame(original, TranslationPresentation(listOf(null), TranslationMode.Replace).displayLines(listOf(original)).single())
    }
    /** An AI translator that never answers in time, or turns the request down. */
    private class Slow(private val error: Exception = java.net.SocketTimeoutException("timeout")) : Translator {
        override val provider = TranslationProvider.Gemini
        var asked = 0
        override suspend fun translate(request: TranslatorRequest): TranslatorResponse { asked++; throw error }
    }
    private val gemini = preferences.copy(provider = TranslationProvider.Gemini)

    @Test fun `a timed-out AI still shows Genius's lines, and isn't kept`() = runBlocking {
        val input = document("夜が来る", "違う歌詞", "星が光る")
        val pair = GeniusTranslationPair.fromText("夜が来る\n星が光る", "Night comes\nStars shine", "ja")!!
        val cache = Cache()
        val result = (TranslationEngine(SongLanguageDetector { null }, cache, pause = {}).translate(input, gemini, null, Slow()) { pair }
            as TranslationOutcome.Translated).result
        assertEquals(listOf("Night comes", null, "Stars shine"), result.texts)
        assertNull(cache.result)
    }

    @Test fun `a timed-out AI falls back to Google, credited to Google and not kept`() = runBlocking {
        val input = document("夜が来る", "星が光る")
        val cache = Cache()
        val result = (TranslationEngine(SongLanguageDetector { null }, cache, pause = {})
            .translate(input, gemini, null, Slow(), fallback = Machine()) { null } as TranslationOutcome.Translated).result
        assertEquals(listOf("machine:夜が来る", "machine:星が光る"), result.texts)
        assertEquals("Google", result.credit())
        assertNull(cache.result)
    }

    @Test fun `with nothing else to show, the timeout stands, and a bad setting never falls back`() = runBlocking {
        val engine = TranslationEngine(SongLanguageDetector { null }, Cache(), pause = {})
        val timedOut = runCatching { engine.translate(document("夜が来る"), gemini, null, Slow()) { null } }.exceptionOrNull()
        assertTrue(timedOut is java.net.SocketTimeoutException)
        val google = Machine()
        val badKey = runCatching {
            engine.translate(document("夜が来る"), gemini, null, Slow(TranslationFailure("bad key")), fallback = google) { null }
        }.exceptionOrNull()
        assertEquals("bad key", badKey?.message)
        assertTrue(google.requests.isEmpty())
    }
}
