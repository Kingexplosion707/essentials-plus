package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.spotify.LocalTrackMetadata
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackCandidate
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolution
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * lrc.red serves Apple Music's lyrics TTML by ISRC, word, line and unsynced alike, with Apple's
 * `itunes:` names renamed. Its site search (`search.json`) finds the ISRC from title and artist;
 * the hits go through the Spotify matcher, which already refuses other versions of a song.
 * Some songs carry a word sync from lrc.red's in-house model instead of Apple's lyrics; only the
 * song's `song.json` says so (`byModel`), so that is what's fetched, with the TTML as its `source`.
 * An answer ranks as Apple Music's or as lrc.red's AI syncs ([originOf]).
 */
@Singleton
class LrcRedLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
    /**
     * When each song the user had lrc.red's model sync was synced, by ISRC. lrc.red's CDN keeps
     * serving the old `song.json` for a day or more, so these are asked for under a URL of their own.
     */
    val synced: MutableMap<String, Long> = ConcurrentHashMap(),
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        RemoteLyricsSource.LRC_RED_ID, "lrc.red", 45,
        setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.PLAIN_TEXT, LyricsCapability.TRANSLITERATION),
        upstreamFamily = "apple_music", releaseChannel = SourceReleaseChannel.EXTENDED,
    )

    // The model takes a few seconds on the recording; longer than a lookup is given, the whole
    // call included (the lookup client's own call deadline is shorter).
    internal val syncClient by lazy {
        client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(SYNC_CALL_TIMEOUT_S, TimeUnit.SECONDS).build()
    }

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = guarded {
        val (_, song) = firstSong(request) ?: return@guarded ProviderResult.Miss
        songPayload(song)?.let { ProviderResult.Hit(it) } ?: ProviderResult.Miss
    }

    /**
     * Has lrc.red's model word-sync [request]'s song, as its site's "Sync with the model" does.
     * Only for a song lrc.red has; the answer is the model's sync, ranked as lrc.red's AI syncs.
     */
    suspend fun requestSync(request: LyricsLookupRequest): ModelSyncResult = try {
        val (isrc, _) = firstSong(request) ?: return ModelSyncResult.Failed("lrc.red doesn't have this song.")
        val post = Request.Builder().url("$BASE/s/$isrc/sync").post(ByteArray(0).toRequestBody()).build()
        val body = syncClient.newCall(post).awaitResponse().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) return ModelSyncResult.Failed(syncError(text) ?: "lrc.red couldn't sync it (HTTP ${response.code}).")
            text
        }
        val payload = songPayload(gson.fromJson(body, JsonObject::class.java), byModel = true)
            ?: return ModelSyncResult.Failed("lrc.red's model sent no lyrics.")
        synced[isrc] = System.currentTimeMillis()
        ModelSyncResult.Synced(isrc, payload)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        ModelSyncResult.Failed("Couldn't reach lrc.red.")
    } catch (error: Exception) {
        ModelSyncResult.Failed("lrc.red's answer couldn't be read.")
    }

    /**
     * The first of the matched recordings lrc.red has a song for: its site search finds the ISRCs,
     * the Spotify matcher picks the hit, and its alternates are tried in turn.
     */
    private suspend fun firstSong(request: LyricsLookupRequest): Pair<String, JsonObject>? {
        val search = "$BASE/search.json".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${request.title} ${request.artist}").build()
        val candidates = client.json(search, gson).getAsJsonArray("hits").orEmpty().mapNotNull { element ->
            val hit = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun string(key: String) = hit.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            SpotifyTrackCandidate(
                id = string("isrc") ?: return@mapNotNull null,
                title = string("title") ?: return@mapNotNull null,
                artists = listOfNotNull(string("artist")),
                album = string("album").orEmpty(),
                durationMs = ((hit.get("duration")?.asDouble ?: 0.0) * 1000).toLong(),
            )
        }
        val local = LocalTrackMetadata(request.title, request.artist, request.album, request.durationSeconds * 1000L)
        val match = SpotifyTrackMatcher.resolve(local, candidates) as? SpotifyTrackResolution.Matched ?: return null
        for (isrc in listOf(match.track, *match.alternates.toTypedArray()).map { it.candidate.id }) {
            val url = synced[isrc]?.let { "$BASE/s/$isrc.song.json?synced=$it" } ?: "$BASE/s/$isrc.song.json"
            val song = try {
                client.json(url, gson)
            } catch (missing: HttpStatusException) {
                if (missing.code == 404) continue else throw missing
            }
            return isrc to song
        }
        return null
    }

    companion object {
        private const val BASE = "https://lrc.red"
        internal const val SYNC_CALL_TIMEOUT_S = 90L
        private val RENAMED_ATTRIBUTE = Regex("""(?<=\s)lrc:(?=[A-Za-z]+=)""")

        /**
         * Whose lyrics a song is, by its `byModel` flag. The TTML can't tell: Apple's own word syncs
         * often lack `leadingSilence` (Bohemian Rhapsody, GBUM71029604) and the model's can lack
         * part names like a line sync does.
         */
        fun originOf(byModel: Boolean): String =
            if (byModel) LyricsOrigins.LRC_RED_AI_NAME else "Apple Music"

        /**
         * A `song.json` as lyrics: its TTML (`source`) with Apple's names, labelled by [originOf].
         * [byModel] overrides the song's own flag, which the sync request's answer leaves out.
         */
        fun songPayload(song: JsonObject, byModel: Boolean? = null): RemoteLyricsPayload? {
            val ttml = song.get("source")?.takeIf { it.isJsonPrimitive }?.asString
            if (ttml.isNullOrBlank()) return null
            val byModel = byModel ?: (song.get("byModel")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
            return RemoteLyricsPayload(
                ttmlLyrics = appleTtml(ttml),
                sourceId = RemoteLyricsSource.LRC_RED_ID,
                attribution = LyricsAttribution("lrc.red", originName = originOf(byModel)),
            )
        }

        /** The `error` lrc.red's sync request explains a refusal with, e.g. "We can't find the recording this comes from." */
        fun syncError(body: String): String? = runCatching {
            Gson().fromJson(body, JsonObject::class.java)?.get("error")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
        }.getOrNull()

        /** Puts back Apple's names (`itunes:timing`, `itunes:key`, `iTunesMetadata`) the parser reads. */
        fun appleTtml(ttml: String): String = ttml
            .replace("xmlns:lrc=", "xmlns:itunes=")
            .replace(RENAMED_ATTRIBUTE, "itunes:")
            .replace("<sourceMetadata", "<iTunesMetadata")
            .replace("</sourceMetadata>", "</iTunesMetadata>")
    }
}

/** What asking lrc.red's model to sync a song came to. */
sealed interface ModelSyncResult {
    data class Synced(val isrc: String, val payload: RemoteLyricsPayload) : ModelSyncResult
    data class Failed(val message: String) : ModelSyncResult
}

private fun com.google.gson.JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList().orEmpty()
