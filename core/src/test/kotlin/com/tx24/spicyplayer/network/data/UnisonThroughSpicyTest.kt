package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.network.data.spotify.AnonymousSpotifyCatalogSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unison's lyrics brought by Spicy Lyrics (its store list) count with the Unison provider itself off. */
class UnisonThroughSpicyTest {
    private val request = LyricsLookupRequest("Nobody", "Made Up", "", 60)
    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word"><body><div>""" +
        """<p begin="1s" end="2s"><span begin="1s" end="2s">Hi</span></p></div></body></tt>"""

    private fun provider(id: String, result: ProviderResult, defaultEnabled: Boolean = true) = object : RemoteLyricsProvider {
        override val descriptor = LyricsSourceDescriptor(id, id, 10, setOf(LyricsCapability.WORD_SYNC), defaultEnabled = defaultEnabled)
        override suspend fun fetch(request: LyricsLookupRequest) = result
    }

    @Test fun `Spicy Lyrics' store list answers for Unison while Unison's own API is off`() = runBlocking {
        val spicy = provider("spicy_unison", ProviderResult.Hit(RemoteLyricsPayload(
            ttmlLyrics = ttml, attribution = LyricsAttribution(providerName = "Spicy Lyrics", originName = "Unison"),
        )))
        val unison = provider("unison", ProviderResult.Miss)
        val policy = RemoteLyricsPolicy(pairs = mapOf("unison/unison" to false, "unison/spicy_unison" to true))
        val result = RemoteLyricsSource(setOf(spicy, unison), ProviderCooldownTracker()).resolveLyrics(request, policy)
            as RemoteLyricsResolution.Found
        assertEquals("spicy_unison", result.selection.source.id)
        assertEquals("Unison", result.selection.payload.attribution?.originName)
    }

    @Test fun `track IDs turn into the metadata service's hex IDs`() {
        assertEquals("93bc414a606747b2b612491ef83d5a3e", AnonymousSpotifyCatalogSearch.trackGid("4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(AnonymousSpotifyCatalogSearch.trackGid("not-an-id"))
    }
}
