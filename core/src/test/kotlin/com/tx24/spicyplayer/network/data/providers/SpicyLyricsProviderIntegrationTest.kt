package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.measuredQuality
import com.tx24.spicyplayer.network.data.spotify.AnonymousSpotifyCatalogSearch
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolver
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SpicyLyricsProviderIntegrationTest {
    @Test
    fun `live provider resolves spotify id and converts response to word ttml`() = runBlocking {
        assumeTrue(System.getenv("RUN_SPICY_LYRICS_PROVIDER_TEST") == "1")
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val gson = Gson()
        val provider = SpicyLyricsProvider(
            client,
            gson,
            SpotifyTrackResolver(AnonymousSpotifyCatalogSearch(client, gson)),
            requireNotNull(System.getenv("SPICY_LYRICS_CLIENT_KEY")),
        )

        val result = provider.fetch(
            LyricsLookupRequest(
                artist = "Lady Gaga",
                title = "Disease",
                album = "MAYHEM",
                durationSeconds = 229,
                spotifyTrackId = "1QV6tiMFM6fSOKOGLMHYYg",
            )
        )

        assertTrue("Expected Spicy Lyrics hit, got $result", result is ProviderResult.Hit)
        assertEquals(
            RemoteLyricsQuality.WORD_SYNCED,
            (result as ProviderResult.Hit).payload.measuredQuality(),
        )
    }
}
