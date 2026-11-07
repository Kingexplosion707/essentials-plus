package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.measuredQuality
import com.tx24.spicyplayer.network.data.spotify.LocalTrackMetadata
import com.tx24.spicyplayer.network.data.spotify.SpotifyCatalogSearch
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackCandidate
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A song the Spotify match finds on two IDs: "first" (the album copy, asked first) and "second"
 * (the single). Each ID's answer comes back after its own delay.
 */
class SpicyLyricsProviderLookupTest {
    private val request = LyricsLookupRequest(artist = "Artist", title = "Song", album = "Album", durationSeconds = 200)

    private val catalog = object : SpotifyCatalogSearch {
        override suspend fun search(track: LocalTrackMetadata) = listOf(
            SpotifyTrackCandidate("second", "Song", listOf("Artist"), "Song - Single", 200_000),
            SpotifyTrackCandidate("first", "Song", listOf("Artist"), "Album", 200_000),
        )
    }

    private class Answer(val delayMs: Long, val body: String)

    /** When each ID's request started and finished, in nanoseconds, and whether it was cancelled. */
    private val started = ConcurrentHashMap<String, Long>()
    private val finished = ConcurrentHashMap<String, Long>()
    private val cancelled = ConcurrentHashMap<String, Boolean>()

    /** [secondDone] counts down once the second ID's request has run its course. */
    private fun provider(answers: Map<String, Answer>, secondDone: CountDownLatch = CountDownLatch(1)): SpicyLyricsProvider {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val id = chain.request().url.pathSegments.last()
            val answer = answers.getValue(id)
            started[id] = System.nanoTime()
            try {
                Thread.sleep(answer.delayMs)
            } finally {
                finished[id] = System.nanoTime()
                cancelled[id] = chain.call().isCanceled()
                if (id == "second") secondDone.countDown()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer.body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return SpicyLyricsProvider(client, Gson(), SpotifyTrackResolver(catalog), "key")
    }

    @Test fun bothIdsAreAskedAtOnceAndTheFirstStillWinsATie() = runBlocking {
        // The second ID answers sooner; equal timing still goes to the first.
        val provider = provider(mapOf(
            "first" to Answer(400, wordSynced("first")),
            "second" to Answer(100, wordSynced("second")),
        ))

        val result = provider.fetch(request)

        assertTrue("second asked before first answered", started.getValue("second") < finished.getValue("first"))
        assertEquals(listOf("first"), (result as ProviderResult.Hit).payload.attribution?.songwriters)
    }

    @Test fun betterTimingOnTheSecondIdWins() = runBlocking {
        val provider = provider(mapOf(
            "first" to Answer(100, lineSynced("first")),
            "second" to Answer(300, wordSynced("second")),
        ))

        val result = provider.fetch(request) as ProviderResult.Hit

        assertEquals(listOf("second"), result.payload.attribution?.songwriters)
        assertEquals(RemoteLyricsQuality.WORD_SYNCED, result.payload.measuredQuality())
    }

    @Test fun anUploadOnTheFirstIdNeitherWaitsForNorKeepsTheSecond() = runBlocking {
        val secondDone = CountDownLatch(1)
        val provider = provider(mapOf(
            "first" to Answer(50, wordSynced("first", upload = true)),
            "second" to Answer(1_500, wordSynced("second", upload = true)),
        ), secondDone)

        val startedAt = System.nanoTime()
        val result = provider.fetch(request) as ProviderResult.Hit
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals(listOf("first"), result.payload.attribution?.songwriters)
        assertTrue("took $tookMs ms", tookMs < 1_000)
        // The second ID's HTTP call is cancelled along with its coroutine.
        assertTrue(secondDone.await(5, TimeUnit.SECONDS))
        assertEquals(true, cancelled["second"])
    }

    private fun wordSynced(writer: String, upload: Boolean = false) = """{"Status":200,"Body":{
        "SongWriters":["$writer"],
        ${if (upload) """"UploadAttribution":{"Maker":"someone"},""" else ""}
        "Content":[{"Lead":{"StartTime":1,"EndTime":2,"Syllables":[
            {"Text":"hello","StartTime":1,"EndTime":1.5,"IsPartOfWord":false},
            {"Text":"there","StartTime":1.5,"EndTime":2,"IsPartOfWord":false}
        ]}}]}}"""

    private fun lineSynced(writer: String) = """{"Status":200,"Body":{
        "SongWriters":["$writer"],
        "Content":[{"Type":"Vocal","Text":"hello there","StartTime":1,"EndTime":2}]}}"""
}
