package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniusSongPagesTest {
    /** A client answering with [codes] in turn (the last one repeats), counting the requests. */
    private class Genius(vararg val codes: Int) {
        var requests = 0
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val code = codes[minOf(requests++, codes.lastIndex)]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body("""{"response":{"song":{"id":1,"translation_songs":[]}}}""".toResponseBody()).build()
        }.build()
    }

    @Test fun `shown lyrics leave out section headers and title cards`() {
        val page = "\u200B[米津玄師「Lemon」歌詞]\n\n[Verse 1]\n夢ならばどれほどよかったでしょう\n(Ooh)\n\n[Chorus: Jung Kook]\nあの日の悲しみさえ"
        assertEquals("夢ならばどれほどよかったでしょう\n(Ooh)\n\nあの日の悲しみさえ", withoutHeaders(page))
    }

    @Test fun `the romanization and translation lookups share a song's requests`() = runBlocking {
        val genius = Genius(200)
        val pages = GeniusSongPages(genius.client, Gson())
        // Both lookups ask for the same record at once: one request answers both.
        val records = List(2) { async { pages.record(1) } }.awaitAll()
        assertEquals(1, genius.requests)
        assertEquals(records[0], records[1])
        pages.record(1)
        assertEquals(1, genius.requests)
    }

    @Test fun `a failed request isn't remembered and an old answer is asked again`() = runBlocking {
        var now = 0L
        val genius = Genius(429, 200)
        val pages = GeniusSongPages(genius.client, Gson()) { now }
        assertTrue(runCatching { pages.record(1) }.isFailure)
        assertEquals(1L, pages.record(1)?.get("id")?.asLong)
        assertEquals(2, genius.requests)
        now += 11 * 60_000L
        pages.record(1)
        assertEquals(3, genius.requests)
    }

    @Test fun `a Genius outage isn't taken for a song without a romanization`() = runBlocking {
        val limited = GeniusRomanizationSource(GeniusSongPages(Genius(429).client, Gson()))
        assertTrue(runCatching { limited.find("夜に駆ける", "YOASOBI") }.isFailure)
        val empty = GeniusRomanizationSource(GeniusSongPages(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("x")
                .body("""{"response":{"sections":[]}}""".toResponseBody()).build()
        }.build(), Gson()))
        assertNull(empty.find("夜に駆ける", "YOASOBI"))
    }

    /** A Genius answering searches with [hits] (title to primary artist) and artist records with [aliases]. */
    private fun searchingGenius(hits: List<Pair<String, String>>, aliases: List<String> = emptyList()) = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        val body = when {
            "/api/artists/" in url -> Gson().toJson(mapOf("response" to mapOf("artist" to mapOf("name" to "x", "alternate_names" to aliases))))
            "/api/search/" in url -> Gson().toJson(mapOf("response" to mapOf("sections" to listOf(mapOf("type" to "song", "hits" to hits.mapIndexed { i, (title, artist) ->
                mapOf("result" to mapOf("_type" to "song", "id" to i + 1, "title" to title, "url" to "https://genius.com/$i",
                    "primary_artist" to mapOf("id" to 100 + i, "name" to artist), "artist_names" to artist))
            })))))
            else -> "{}"
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("x").body(body.toResponseBody()).build()
    }.build()

    @Test fun `another version of the song isn't the song`() = runBlocking {
        val pages = GeniusSongPages(searchingGenius(listOf("Spring Day (Japanese Ver.)" to "BTS", "봄날 (Spring Day)" to "BTS")), Gson())
        assertEquals("봄날 (Spring Day)", pages.original("Spring Day", "BTS")?.get("title")?.asString)
        // Unless that version is what's playing.
        assertEquals("Spring Day (Japanese Ver.)", pages.original("Spring Day (Japanese Ver.)", "BTS")?.get("title")?.asString)
        val acoustic = GeniusSongPages(searchingGenius(listOf("Pretender - Acoustic Ver" to "Official髭男dism")), Gson())
        assertNull(acoustic.original("Pretender", "Official髭男dism"))
    }

    @Test fun `an artist filed under another name is found by its Genius aliases`() = runBlocking {
        val hits = listOf("Pretender" to "Official髭男dism")
        val pages = GeniusSongPages(searchingGenius(hits, aliases = listOf("Higedan (ヒゲダン)", "OFFICIAL HIGE DANDISM")), Gson())
        assertEquals("Pretender", pages.original("Pretender", "Official HIGE DANdism")?.get("title")?.asString)
        val stranger = GeniusSongPages(searchingGenius(hits, aliases = listOf("Higedan")), Gson())
        assertNull(stranger.original("Pretender", "Some Other Band"))
    }

    @Test fun `a title written in the other kana, or matched by its bracketed name, is the song`() = runBlocking {
        // Spotify's "ヤラララ(YARARARA)" is Genius's "やラララ (YARARARA)".
        val pages = GeniusSongPages(searchingGenius(listOf("やラララ (YARARARA)" to "AnythingBecomeMoe")), Gson())
        assertEquals("やラララ (YARARARA)", pages.original("ヤラララ(YARARARA)", "AnythingBecomeMoe")?.get("title")?.asString)
        assertEquals("やラララ (YARARARA)", pages.original("YARARARA", "AnythingBecomeMoe")?.get("title")?.asString)
        // A featured credit in brackets is no other name for the song.
        val featured = GeniusSongPages(searchingGenius(listOf("Different (feat. Kasane Teto)" to "AnythingBecomeMoe")), Gson())
        assertNull(featured.original("Song (feat. Kasane Teto)", "AnythingBecomeMoe"))
    }

    @Test fun `a title that matches outright goes before one that only holds it`() = runBlocking {
        val pages = GeniusSongPages(searchingGenius(listOf("Lemon Tree" to "米津玄師 (Kenshi Yonezu)", "Lemon" to "米津玄師 (Kenshi Yonezu)")), Gson())
        assertEquals("Lemon", pages.original("Lemon", "Kenshi Yonezu")?.get("title")?.asString)
    }
}
