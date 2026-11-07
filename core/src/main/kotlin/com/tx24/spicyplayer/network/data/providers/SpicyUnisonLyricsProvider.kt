package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.LyricsAttribution
import com.tx24.spicyplayer.network.data.LyricsCapability
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.LyricsSourceDescriptor
import com.tx24.spicyplayer.network.data.ProviderFailureCategory
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsProvider
import com.tx24.spicyplayer.network.data.awaitResponse
import com.tx24.spicyplayer.network.data.spotify.LocalTrackMetadata
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolution
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolver
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Spicy Lyrics' store list of Unison's lyrics, looked up by the song's ISRC: the Spotify match the
 * Spicy Lyrics lookup makes (shared, so it's searched once) and Spotify's ISRC for it. Unison has
 * no ISRC for some songs, only the YouTube video they were synced to, so then YouTube's search
 * gives the video, and the store list's own title and artist have to be the song's. Asked on its
 * own, so its answer ranks under Unison whatever Spicy Lyrics' API has.
 */
class SpicyUnisonLyricsProvider(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val spotifyResolver: SpotifyTrackResolver,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        id = ID,
        displayName = "Spicy Lyrics store list",
        defaultPriority = 61,
        capabilities = setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.PLAIN_TEXT),
        upstreamFamily = "unison",
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = try {
        val trackId = request.spotifyTrackId ?: (spotifyResolver.resolve(
            LocalTrackMetadata(request.title, request.artist, request.album, request.durationSeconds * 1_000L),
        ) as? SpotifyTrackResolution.Matched)?.track?.candidate?.id
        val isrc = trackId?.let { spotifyResolver.isrc(it) }?.takeIf { it.matches(UnisonLyricsProvider.ISRC) }
        val lyrics = isrc?.let { client.storeList(gson, listOf("$it.isrc")) }
            ?: client.storeList(gson, videoIds(request).map { "$it.video_id" }) { title, artist -> same(request, title, artist) }
        if (lyrics == null) ProviderResult.Miss
        else ProviderResult.Hit(lyrics.copy(attribution = LyricsAttribution(providerName = "Spicy Lyrics", originName = "Unison")))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, retryable = true)
    } catch (error: Exception) {
        ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message)
    }

    /** The first few videos YouTube's search finds for the song. */
    private suspend fun videoIds(request: LyricsLookupRequest): List<String> {
        val url = "https://www.youtube.com/results".toHttpUrl().newBuilder()
            .addQueryParameter("search_query", "${request.artist} ${request.title}").build()
        val page = client.newCall(Request.Builder().url(url).header("Accept-Language", "en").get().build()).awaitResponse().use {
            if (it.isSuccessful) it.body?.string().orEmpty() else ""
        }
        return VIDEO_ID.findAll(page).map { it.groupValues[1] }.distinct().take(VIDEOS_TRIED).toList()
    }

    /** Whether a store-list track is the song asked for: the same title, an artist one names the other. */
    private fun same(request: LyricsLookupRequest, title: String?, artist: String?): Boolean {
        if (title == null || artist == null) return false
        val theirs = SpotifyTrackMatcher.normalize(artist)
        val ours = SpotifyTrackMatcher.normalize(request.artist)
        return SpotifyTrackMatcher.normalize(title) == SpotifyTrackMatcher.normalize(request.title) &&
            theirs.isNotEmpty() && ours.isNotEmpty() && (theirs in ours || ours in theirs)
    }

    companion object {
        const val ID = "spicy_unison"
        private val VIDEO_ID = Regex("\\\"videoId\\\":\\\"([A-Za-z0-9_-]{11})\\\"")
        private const val VIDEOS_TRIED = 3
    }
}
