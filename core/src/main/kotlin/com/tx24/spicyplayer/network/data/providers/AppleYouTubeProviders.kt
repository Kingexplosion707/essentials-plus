package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.lyrics.spicy.romanization.Script
import com.tx24.spicyplayer.lyrics.spicy.romanization.ScriptDetector
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.blend.LyricsFurniture
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class YouTubeTranscriptLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        "youtube_transcript", "YouTube transcripts", 150,
        setOf(LyricsCapability.LINE_SYNC, LyricsCapability.PLAIN_TEXT),
        releaseChannel = SourceReleaseChannel.EXPERIMENTAL, defaultEnabled = false,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
        return try {
            val search = client.newCall(Request.Builder().url("https://www.youtube.com/results".toHttpUrl().newBuilder()
                .addQueryParameter("search_query", "${request.artist} ${request.title} official audio").build()).get().build())
                .awaitResponse().use { if (!it.isSuccessful) throw IOException("YouTube search HTTP ${it.code}"); it.body?.string().orEmpty() }
            val ids = Regex("\\\"videoId\\\":\\\"([A-Za-z0-9_-]{11})\\\"").findAll(search).map { it.groupValues[1] }.distinct().take(6).toList()
            for (id in ids) {
                // The first client that describes the video decides it; asking the next is another slow round trip.
                val player = CLIENTS.firstNotNullOfOrNull { (name, version, ua) ->
                    player(id, name, version, ua)?.takeIf { it.has("videoDetails") }
                } ?: continue
                val details = player.getAsJsonObject("videoDetails")
                val duration = details.get("lengthSeconds")?.asInt ?: 0
                val title = details.get("title")?.asString.orEmpty()
                val author = details.get("author")?.asString.orEmpty()
                val titleMatch = SpotifyTrackMatcher.normalize(title).contains(SpotifyTrackMatcher.normalize(request.title))
                val artistMatch = SpotifyTrackMatcher.normalize("$title $author").contains(SpotifyTrackMatcher.normalize(request.artist))
                if (!titleMatch || !artistMatch || (duration > 0 && kotlin.math.abs(duration - request.durationSeconds) > 12)) continue
                val tracks = player.getAsJsonObject("captions")?.getAsJsonObject("playerCaptionsTracklistRenderer")?.getAsJsonArray("captionTracks") ?: continue
                // Auto-generated captions mishear singing too often to be worth showing.
                val track = tracks.map { it.asJsonObject }.firstOrNull { it.get("kind")?.asString != "asr" } ?: continue
                val baseUrl = track.get("baseUrl")?.asString ?: continue
                // Lyric videos open on the title alone ("("Blinding Lights")"), which nobody sings.
                val titleKey = SpotifyTrackMatcher.normalize(request.title)
                val captions = captionLrc(baseUrl)?.lines()
                    ?.dropWhile { SpotifyTrackMatcher.normalize(it.substringAfter(']')) == titleKey }
                    ?.joinToString("\n")?.takeIf(String::isNotBlank) ?: continue
                // Uploaders file pinyin or romaji as the "zh" or "ja" track: try the next video instead.
                if (!inSongScript(captions, request.title)) continue
                val payload = LyricsFurniture.clean(RemoteLyricsPayload(syncedLyrics = captions), request.title, request.artist)
                if (!payload.isEmpty()) return ProviderResult.Hit(payload)
            }
            ProviderResult.Miss
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: IOException) { ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, true) }
          catch (error: Exception) { ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message) }
    }

    private suspend fun player(id: String, clientName: String, version: String, ua: String): JsonObject? {
        val body = """{"context":{"client":{"clientName":"$clientName","clientVersion":"$version","hl":"en","gl":"US"}},"videoId":"$id"}"""
        return client.newCall(Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", ua).post(body.toRequestBody("application/json".toMediaType())).build()).awaitResponse().use {
            throttled(it.code)
            if (!it.isSuccessful) return@use null; gson.fromJson(it.body?.string(), JsonObject::class.java)
        }
    }

    private suspend fun captionLrc(baseUrl: String): String? {
        val url = baseUrl.toHttpUrl().newBuilder().setQueryParameter("fmt", "json3").build()
        val root = client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use {
            throttled(it.code)
            if (!it.isSuccessful) return null; gson.fromJson(it.body?.string(), JsonObject::class.java)
        }
        val lines = root.getAsJsonArray("events")?.mapNotNull { raw ->
            val event = raw.asJsonObject; val start = event.get("tStartMs")?.asLong ?: return@mapNotNull null
            val text = event.getAsJsonArray("segs")?.joinToString("") { it.asJsonObject.get("utf8")?.asString.orEmpty() }
                ?.replace("\n", " ")?.replace(CUES, " ")?.replace(SPACES, " ")?.trim().orEmpty()
            if (text.isBlank()) null else "[${lrcTime(start)}]$text"
        }.orEmpty()
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** YouTube throttles an address that asks too often; that's "try later", not "no lyrics". */
    private fun throttled(code: Int) {
        if (code == 429) throw IOException("YouTube HTTP 429")
    }

    /** A Chinese, Japanese or Korean title wants captions written that way in at least half their lines. */
    fun inSongScript(captions: String, songTitle: String): Boolean {
        val scripts = ScriptDetector.detect(songTitle) intersect setOf(Script.CHINESE, Script.JAPANESE, Script.KOREAN)
        if (scripts.isEmpty()) return true
        val lines = captions.lines().map { it.substringAfter(']') }.filter(String::isNotBlank)
        return lines.count { line -> scripts.any { ScriptDetector.contains(it, line) } } * 2 >= lines.size
    }

    private fun lrcTime(ms: Long): String {
        val totalSeconds = ms / 1000; val minutes = totalSeconds / 60; val seconds = totalSeconds % 60; val hundredths = (ms % 1000) / 10
        return String.format(Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, hundredths)
    }

    private companion object {
        /** Sound cues and music notes: "[Music]", "[音乐]", "(upbeat music)", "♪". */
        val CUES = Regex("""\[[^\]]*]|\([^)]*\b(?:music|instrumental|applause|cheering|laughs?)\b[^)]*\)|[♪♫]""", RegexOption.IGNORE_CASE)
        val SPACES = Regex("""\s{2,}""")
        val CLIENTS = listOf(
            Triple("ANDROID", "20.10.38", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"),
            Triple("IOS", "20.10.4", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_0 like Mac OS X)"),
        )
    }
}
