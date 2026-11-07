package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.measuredQuality
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AmllLyricsProviderIntegrationTest {
    @Test
    fun `live search returns parseable word timed ttml`() = runBlocking {
        assumeTrue(System.getenv("RUN_AMLL_NETWORK_TEST") == "1")
        val provider = AmllLyricsProvider(
            OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
            Gson(),
        )

        val result = provider.fetch(
            LyricsLookupRequest(
                artist = "Rick Astley",
                title = "Never Gonna Give You Up",
                album = "Whenever You Need Somebody",
                durationSeconds = 213,
            )
        )

        assertTrue("Expected AMLL hit, got $result", result is ProviderResult.Hit)
        assertEquals(
            RemoteLyricsQuality.WORD_SYNCED,
            (result as ProviderResult.Hit).payload.measuredQuality(),
        )
    }
}
