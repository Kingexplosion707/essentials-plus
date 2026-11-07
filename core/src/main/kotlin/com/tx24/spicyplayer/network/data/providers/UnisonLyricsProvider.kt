package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.LyricsAttribution
import com.tx24.spicyplayer.network.data.LyricsCapability
import com.tx24.spicyplayer.network.data.LyricsContributor
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.LyricsSourceDescriptor
import com.tx24.spicyplayer.network.data.ProviderFailureCategory
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsPayload
import com.tx24.spicyplayer.network.data.RemoteLyricsProvider
import com.tx24.spicyplayer.network.data.SourceReleaseChannel
import com.tx24.spicyplayer.network.data.awaitResponse
import com.tx24.spicyplayer.network.data.slobjpack.SLObjPack
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class UnisonLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        id = "unison",
        // Named for its host, apart from the source it serves and Spicy Lyrics' copy of it.
        displayName = "Unison (boidu.dev)",
        defaultPriority = 60,
        capabilities = setOf(
            LyricsCapability.WORD_SYNC,
            LyricsCapability.LINE_SYNC,
            LyricsCapability.PLAIN_TEXT,
        ),
        releaseChannel = SourceReleaseChannel.EXTENDED,
    )

    /**
     * Unison's search finds the song (and who submitted it); Spicy Lyrics' store list then serves
     * its lyrics by ISRC or YouTube video. Unison's own copy is the fallback when the store list
     * doesn't have it yet, and the title lookup when the search finds nothing.
     */
    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = try {
        search(request) ?: direct(request) ?: ProviderResult.Miss
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, retryable = true)
    } catch (error: Exception) {
        ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message)
    }

    private suspend fun direct(request: LyricsLookupRequest): ProviderResult? {
        val url = "$BASE/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("song", request.title)
            .addQueryParameter("artist", request.artist)
            .build()
        return client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) return@use httpFailure(response.code)
            val root = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val data = root.getAsJsonObject("data") ?: return@use null
            if (!data.matches(request)) return@use null
            payload(data)
        }
    }

    private suspend fun search(request: LyricsLookupRequest): ProviderResult? {
        val url = "$BASE/lyrics/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${request.artist} ${request.title}")
            .build()
        val searchResult = client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                return@use null to if (response.code == 404) null else httpFailure(response.code)
            }
            val root = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val hit = root.getAsJsonArray("data")
                ?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
                ?.firstOrNull { it.matches(request) }
            hit to null
        }
        searchResult.second?.let { return it }
        val hit = searchResult.first ?: return null
        storeList(hit)?.let { lyrics ->
            return ProviderResult.Hit(lyrics.copy(attribution = attribution(hit)))
        }
        val id = hit.get("id")?.asString ?: return null
        val detailUrl = "$BASE/lyrics".toHttpUrl().newBuilder().addPathSegment(id).build()
        return client.newCall(Request.Builder().url(detailUrl).get().build()).awaitResponse().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) return@use httpFailure(response.code)
            val root = gson.fromJson(response.body?.string(), JsonObject::class.java)
            root.getAsJsonObject("data")?.let(::payload)
        }
    }

    /**
     * The store list's lyrics for a search [hit], by its ISRC, else its YouTube video. Null when it
     * has neither or the store list hasn't got the song: Unison's own copy is asked next.
     */
    private suspend fun storeList(hit: JsonObject): RemoteLyricsPayload? = client.storeList(gson, listOfNotNull(
        hit.string("isrc")?.takeIf { it.matches(ISRC) }?.let { "$it.isrc" },
        hit.string("videoId")?.takeIf { it.matches(VIDEO_ID) }?.let { "$it.video_id" },
    ))

    private fun payload(data: JsonObject): ProviderResult? {
        val lyrics = data.get("lyrics")?.asString?.takeIf(String::isNotBlank) ?: return null
        val format = data.get("format")?.asString?.lowercase()
        val payload = when {
            format == "ttml" || lyrics.contains("<tt", ignoreCase = true) ->
                RemoteLyricsPayload(ttmlLyrics = lyrics)
            format == "lrc" || LRC_TIME.containsMatchIn(lyrics) ->
                RemoteLyricsPayload(syncedLyrics = lyrics)
            format == "plain" || format == "txt" || format.isNullOrBlank() ->
                RemoteLyricsPayload(plainLyrics = lyrics)
            else -> return null // YAML needs a dedicated lossless converter.
        }
        return ProviderResult.Hit(payload.copy(attribution = attribution(data)))
    }

    private fun attribution(entry: JsonObject) = LyricsAttribution(
        providerName = descriptor.displayName,
        uploader = entry.getAsJsonObject("submitter")?.submitter(),
    )

    /** Whoever submitted the sync, with their curator page on Unison and their avatar. */
    private fun JsonObject.submitter(): LyricsContributor? {
        val name = string("displayName") ?: return null
        return LyricsContributor(
            username = name,
            profileUrl = string("keyId")?.takeIf { it.matches(KEY_ID) }?.let { "$BASE/curator/$it" },
            avatarUrl = string("avatarUrl")?.takeIf { it.startsWith("https://") },
        )
    }

    private fun JsonObject.matches(request: LyricsLookupRequest): Boolean {
        val title = get("song")?.asString ?: get("title")?.asString ?: return false
        val artist = get("artist")?.asString ?: return false
        return SpotifyTrackMatcher.normalize(title) == SpotifyTrackMatcher.normalize(request.title) &&
            SpotifyTrackMatcher.normalize(artist).let { candidate ->
                val wanted = SpotifyTrackMatcher.normalize(request.artist)
                candidate == wanted || candidate in wanted || wanted in candidate
            }
    }

    private fun httpFailure(code: Int) = ProviderResult.Unavailable(
        if (code in 400..499) ProviderFailureCategory.CLIENT_REQUEST else ProviderFailureCategory.SERVER,
        "Unison returned HTTP $code",
        retryable = code >= 500,
    )

    companion object {
        const val BASE = "https://unison.boidu.dev"
        const val STORE_LIST = "https://regional.unison.slnode.spicylyrics.org/provider/storelist"
        val ISRC = Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}")
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val KEY_ID = Regex("[0-9a-f]{16,128}")
        val LRC_TIME = Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]")
    }
}

/**
 * Spicy Lyrics' store list of Unison's lyrics: the first of [lookups] (`<isrc>.isrc`,
 * `<video id>.video_id`) it has whose track [accept]s (title, artist). Null when it has none of
 * them, or can't be reached or read.
 */
suspend fun OkHttpClient.storeList(
    gson: Gson,
    lookups: List<String>,
    accept: (title: String?, artist: String?) -> Boolean = { _, _ -> true },
): RemoteLyricsPayload? {
    for (lookup in lookups) {
        val request = Request.Builder().url("${UnisonLyricsProvider.STORE_LIST}/$lookup.json").get().build()
        val body = try {
            newCall(request).awaitResponse().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (error: IOException) {
            null
        } ?: continue
        val track = runCatching {
            gson.fromJson(body, JsonObject::class.java).getAsJsonObject("data").getAsJsonObject("track")
        }.getOrNull()
        if (!accept(track?.string("title"), track?.string("artist"))) continue
        return try {
            withContext(Dispatchers.Default) { storeListPayload(body, gson) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }
    }
    return null
}

private fun JsonObject.string(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)

/**
 * The lyrics in a store-list entry: `data.lyrics.content` is a Spicy Lyrics API body packed with SLObjPack.
 * Null when the entry has none; throws when the content can't be unpacked.
 */
fun storeListPayload(json: String, gson: Gson): RemoteLyricsPayload? {
    val content = gson.fromJson(json, JsonObject::class.java)
        ?.getAsJsonObject("data")?.getAsJsonObject("lyrics")?.get("content")
        ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    // A JSON array decodes to a List of Doubles, Strings and Lists, which is what unpack reads.
    val packed = gson.fromJson(content, List::class.java)
    val body = gson.toJsonTree(SLObjPack().unpack(packed)).takeIf { it.isJsonObject }?.asJsonObject ?: return null
    return spicyBodyPayload(body)
}
