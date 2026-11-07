package com.sameerasw.essentials.utils.lyrics

import com.sameerasw.essentials.BuildConfig
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.LyricsSource
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.service.LyricsService
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** One line of time-synced lyrics. */
data class TimedLine(val startMs: Long, val text: String)

/** Line-synced lyrics for a track, or an empty list when only plain (or no) lyrics exist. */
data class TrackLyrics(val lines: List<TimedLine>) {
    val synced: Boolean get() = lines.isNotEmpty()

    /** The line sung at [positionMs], or null before the first line / in a long gap. */
    fun lineAt(positionMs: Long): TimedLine? {
        val i = indexAt(positionMs)
        if (i < 0) return null
        return lines[i].takeIf { it.text.isNotBlank() }
    }

    fun indexAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** When the line after the one at [positionMs] starts, or null after the last. */
    fun nextStartAfter(positionMs: Long): Long? = lines.getOrNull(indexAt(positionMs) + 1)?.startMs

    companion object {
        val NONE = TrackLyrics(emptyList())
    }
}

/** LRC parsing kept free of Android so it can be unit tested. */
object LrcParser {
    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    // Enhanced LRC word stamps: <00:12.34>
    private val WORD_STAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(lrc: String): List<TimedLine> {
        val offset = OFFSET.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<TimedLine>()
        lrc.lineSequence().forEach { raw ->
            val line = raw.trim()
            var rest = line
            val stamps = ArrayList<Long>()
            while (true) {
                val m = STAMP.matchAt(rest, 0) ?: break
                stamps += stampMs(m)
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) return@forEach
            val text = rest.replace(WORD_STAMP, "").trim().replace(Regex("\\s+"), " ")
            stamps.forEach { out += TimedLine((it - offset).coerceAtLeast(0L), text) }
        }
        out.sortBy { it.startMs }
        return out
    }

    private fun stampMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return (min * 60 + sec) * 1000 + ms
    }
}

/**
 * LRCLIB lookups through the `:core` lyrics engine's LRCLIB source, with a small in-memory cache
 * so a song is only asked for once per session.
 */
object LrclibLyrics {
    private const val CACHE_SIZE = 32

    private val source: LyricsSource by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Essentials Plus ${BuildConfig.VERSION_NAME} (https://github.com/Kingexplosion707/essentials-plus)")
                        .build(),
                )
            }
            .build()
        val service = Retrofit.Builder()
            .baseUrl(LyricsService.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LyricsService::class.java)
        LyricsSource(service)
    }

    private val cache = object : LinkedHashMap<String, TrackLyrics>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackLyrics>?) = size > CACHE_SIZE
    }

    fun cached(key: String): TrackLyrics? = synchronized(cache) { cache[key] }

    /** Blocking network call: run it off the main thread. Null means "try again later". */
    suspend fun fetch(key: String, title: String, artist: String, album: String, durationMs: Long): TrackLyrics? {
        cached(key)?.let { return it }
        val request = LyricsLookupRequest(
            artist = artist,
            title = title,
            album = album,
            durationSeconds = (durationMs / 1000L).toInt().coerceAtLeast(0),
        )
        val result = when (val r = source.fetch(request)) {
            is ProviderResult.Hit -> TrackLyrics(r.payload.syncedLyrics?.let(LrcParser::parse).orEmpty())
            ProviderResult.Miss -> TrackLyrics.NONE
            else -> return null
        }
        synchronized(cache) { cache[key] = result }
        return result
    }
}
