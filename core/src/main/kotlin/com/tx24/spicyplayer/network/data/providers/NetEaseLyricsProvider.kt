package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.blend.SequenceMatcher
import com.tx24.spicyplayer.lyrics.spicy.romanization.JapaneseRomanizerProvider
import com.google.gson.JsonObject
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.blend.BlendDonors
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class NetEaseLyricsProvider @Inject constructor(private val client: OkHttpClient, private val gson: Gson) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        "netease", "NetEase", 90,
        setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.TRANSLITERATION),
        releaseChannel = SourceReleaseChannel.EXTENDED,
        defaultEnabled = false,
    )

    /** The human-written romaji NetEase had for recent lookups, by title and artist ([romanization]). */
    private val romaji = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > 32
    }

    private fun romajiKey(title: String, artist: String) = SpotifyTrackMatcher.normalize(title) + "\u0000" + SpotifyTrackMatcher.normalize(artist)

    /**
     * NetEase's human-written romanization of [request]'s song as lyric lines (`romalrc`), or
     * null where it has none. From the lookup that found the lyrics when there was one, else
     * asked now.
     */
    suspend fun romanization(request: LyricsLookupRequest): List<String>? {
        val key = romajiKey(request.title, request.artist)
        synchronized(romaji) { romaji[key] }?.let { return it.ifEmpty { null } }
        lookup(request)
        return synchronized(romaji) { romaji[key] }?.ifEmpty { null }
    }

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = try {
        lookup(request)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: IOException) { ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, true) }
      catch (error: Exception) { ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message) }

    private suspend fun lookup(request: LyricsLookupRequest): ProviderResult {
        val searchUrl = "$BASE/cloudsearch/pc".toHttpUrl().newBuilder()
            .addQueryParameter("s", "${request.artist} ${request.title}").addQueryParameter("type", "1")
            .addQueryParameter("offset", "0").addQueryParameter("limit", "8").build()
        val search = getJson(searchUrl)
        val candidates = search.getAsJsonObject("result")?.getAsJsonArray("songs") ?: return ProviderResult.Miss
        val songs = candidates.map { it.asJsonObject }.filter { item ->
            val name = item.get("name")?.asString.orEmpty()
            val artists = (item.getAsJsonArray("ar") ?: item.getAsJsonArray("artists"))?.joinToString(" ") { it.asJsonObject.get("name").asString }.orEmpty()
            val duration = (item.get("dt") ?: item.get("duration"))?.asLong?.div(1000) ?: 0
            (CatalogueTitles.sameTitle(name, request.title) || CatalogueTitles.sameReading(name, request.title)) &&
                CatalogueTitles.sameArtist(artists, request.artist, name, request.title, duration.toInt(), request.durationSeconds) &&
                request.lengthMatches(duration.toInt())
        }
            // The copies closest in length first (search order breaks ties): another edit or
            // version of a song within the length tolerance has the same words at other times.
            // Still TRIES lyric requests, as before.
            .sortedBy { item -> lengthGap(item, request) }
            .take(TRIES)
        if (songs.isEmpty()) return ProviderResult.Miss
        // Word timing belongs to a release, not a song, and the search's favourite is often an
        // untimed or credits-only copy: take the first word-timed candidate, else the first with
        // timed lines.
        val lyrics = coroutineScope { songs.map { song -> async { lyrics(song.get("id").asString) } }.awaitAll() }
        // The romaji of the copy the lyrics come from, or one a sibling copy lends: the word-timed
        // pressing often ships none while another does.
        val romanized = lyrics.indices.sortedBy { if (data(lyrics[it], "yrc") != null) 0 else 1 }
            .firstNotNullOfOrNull { NetEaseRomaji.lines(data(lyrics[it], "romalrc"), data(lyrics[it], "lrc")) }
        synchronized(romaji) { romaji[romajiKey(request.title, request.artist)] = romanized.orEmpty() }
        // A copy whose lyric is only "纯音乐，请欣赏" (instrumental) says nothing about the others.
        lyrics.firstNotNullOfOrNull { data ->
            data.getAsJsonObject("yrc")?.get("lyric")?.asString
                ?.takeIf { it.isNotBlank() && !BlendDonors.isNoWordsNote(it.lines().map { line -> line.replace(ANY_STAMP, "").replace(YRC_STAMP, "") }) }
                ?.let(YrcToTtml::convert)
        }?.let { return ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = it)) }
        val lrcs = lyrics.mapNotNull { data -> data.getAsJsonObject("lrc")?.get("lyric")?.asString?.takeIf(String::isNotBlank) }
            .filterNot { lrc -> BlendDonors.isNoWordsNote(lrc.lines().map { it.replace(ANY_STAMP, "") }) }
        lrcs.firstOrNull(::hasTimedLyric)?.let { return ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = it)) }
        // No stamps worth the name: the words alone, if there are any besides the credits.
        val plain = lrcs.firstNotNullOfOrNull { lrc ->
            lrc.lines().map { it.replace(ANY_STAMP, "").trim() }
                .filter { it.isNotEmpty() && !BlendDonors.isCredit(it) }
                .takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
        return if (plain != null) ProviderResult.Hit(RemoteLyricsPayload(plainLyrics = plain)) else ProviderResult.Miss
    }

    /** How far [item]'s length is from the request's, in seconds; 0 when either is unknown. */
    private fun lengthGap(item: JsonObject, request: LyricsLookupRequest): Int {
        val duration = ((item.get("dt") ?: item.get("duration"))?.asLong?.div(1000) ?: 0L).toInt()
        return if (duration <= 0 || request.durationSeconds <= 0) 0 else kotlin.math.abs(duration - request.durationSeconds)
    }

    private suspend fun lyrics(id: String): JsonObject = getJson("$BASE/song/lyric".toHttpUrl().newBuilder()
        .addQueryParameter("os", "pc").addQueryParameter("id", id)
        .addQueryParameter("lv", "-1").addQueryParameter("kv", "-1")
        .addQueryParameter("tv", "-1").addQueryParameter("yv", "-1").addQueryParameter("rv", "-1").build())

    private fun data(lyric: JsonObject, part: String) = lyric.getAsJsonObject(part)?.get("lyric")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)

    /** An LRC with a stamped line that is a lyric, not a credit ("作词 : ..."). */
    private fun hasTimedLyric(lrc: String): Boolean = lrc.lineSequence().any { line ->
        val m = TIMED_LINE.find(line) ?: return@any false
        val body = line.substring(m.range.last + 1).trim()
        body.isNotEmpty() && !BlendDonors.isCredit(body)
    }

    private suspend fun getJson(url: okhttp3.HttpUrl): JsonObject {
        val request = Request.Builder().url(url).header("Referer", "https://music.163.com").get().build()
        return client.newCall(request).awaitResponse().use { response ->
            if (response.code == 404) return@use JsonObject()
            if (!response.isSuccessful) throw IOException("NetEase HTTP ${response.code}")
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        }
    }

    private companion object {
        const val BASE = "https://music.163.com/api"
        /** How many equally good candidates are worth a lyric request. */
        const val TRIES = 3
        val TIMED_LINE = Regex("""^\[\d+:\d+(?:[.:]\d+)?]""")
        val ANY_STAMP = Regex("""\[[^\]]*]""")
        val YRC_STAMP = Regex("""\(\d+,\d+,[^)]*\)""")
    }
}

/**
 * NetEase's word-timed lyrics: `[lineStart,lineLength](wordStart,wordLength,0)word…`, in ms.
 * Word starts are absolute (the first word of a line starting at 28480 says 28480).
 */
object YrcToTtml {
    /** Rights notices ("词版权管理方：…", "录音作品及MV版权：…"), which the shared credit check lets through. */
    private val rights = Regex("^[^:：]{0,20}版权[^:：]{0,8}[:：]")
    private const val RELATIVE_SLACK_MS = 1_000L
    private val line = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val word = Regex("\\((\\d+),(\\d+),[^)]*\\)([^()]*)")
    fun convert(yrc: String): String? {
        val paragraphs = yrc.lineSequence().mapNotNull { raw ->
            val match = line.matchEntire(raw.trim()) ?: return@mapNotNull null
            val start = match.groupValues[1].toLong(); val duration = match.groupValues[2].toLong()
            val words = word.findAll(match.groupValues[3]).mapNotNull { token ->
                val at = token.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val length = token.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val text = token.groupValues[3]; if (text.isEmpty()) return@mapNotNull null
                Triple(at, length, text)
            }.toList()
            if (words.isEmpty()) return@mapNotNull null
            val body = words.joinToString("") { it.third }.trim()
            if (BlendDonors.isCredit(body) || rights.containsMatchIn(body)) return@mapNotNull null
            // Should a line ever come with its words timed from the line's start, they'd sit
            // well before it: add the start back then.
            val base = if (words.first().first + RELATIVE_SLACK_MS < start) start else 0L
            val spans = words.joinToString("") { (at, length, text) ->
                "<span begin=\"${sec(base + at)}\" end=\"${sec(base + at + length)}\">${xml(text)}</span>"
            }
            "<p begin=\"${sec(start)}\" end=\"${sec(start + duration)}\">$spans</p>"
        }.toList()
        if (paragraphs.isEmpty()) return null
        return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>${paragraphs.joinToString("")}</div></body></tt>"""
    }
    private fun sec(ms: Long) = "${"%.3f".format(Locale.ROOT, ms / 1000.0)}s"
    private fun xml(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
}

/**
 * NetEase's human-written romaji (`romalrc`), as lines a reader can take in. It is written one
 * mora at a time ("su ba ra shi ki"), accurate and nearly unreadable under a lyric, so it is cut
 * into words the way the on-device reader cuts the same Japanese line: NetEase says what the
 * sounds are, the segmenter where to break them, and only where the two agree outright.
 */
object NetEaseRomaji {
    private val STAMP = Regex("""^\[(\d+):(\d+(?:[.:]\d+)?)]""")
    private val ANY_STAMP = Regex("""\[[^\]]*]""")
    /** Romaji is stamped to the lyric's own lines, a little either side of them. */
    private const val PAIRED = 0.05
    private const val ALIKE = 0.82

    fun lines(romalrc: String?, lrc: String?): List<String>? {
        val roman = rows(romalrc ?: return null).ifEmpty { return null }
        val sung = rows(lrc.orEmpty())
        // Stamped on its own clock (between half a second early and a fifth late), but row for row:
        // pair by position where the counts agree and every stamp is within a second.
        val byRow = sung.size == roman.size && roman.indices.all { kotlin.math.abs(roman[it].first - sung[it].first) <= 1.0 }
        return roman.mapIndexed { i, (at, mora) ->
            val line = if (byRow) sung[i].second else sung.firstOrNull { kotlin.math.abs(it.first - at) <= PAIRED }?.second
            val worded = line?.takeIf(::japanese)?.let { runCatching { JapaneseRomanizerProvider.get().romanize(it) }.getOrNull() }
            if (worded != null) rejoin(mora, worded) else mora
        }
    }

    private fun rows(lrc: String): List<Pair<Double, String>> = lrc.lines().mapNotNull { raw ->
        val m = STAMP.find(raw.trim()) ?: return@mapNotNull null
        val at = m.groupValues[1].toInt() * 60 + (m.groupValues[2].replace(':', '.').toDoubleOrNull() ?: return@mapNotNull null)
        val text = raw.trim().replace(ANY_STAMP, "").trim()
        if (text.isEmpty() || BlendDonors.isCredit(text)) null else at to text
    }

    private fun japanese(s: String) = s.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }

    /** [mora] re-cut at the word ends of [worded], where the two spell the same letters. */
    fun rejoin(mora: String, worded: String): String {
        val flat = mora.replace(" ", "")
        if (flat.isEmpty() || worded.isBlank()) return mora
        val stops = HashSet<Int>()
        var at = 0
        for (w in worded.split(' ').filter(String::isNotEmpty)) { at += w.length; stops += at }
        val sm = SequenceMatcher.of(flat.lowercase(), worded.replace(" ", "").lowercase())
        if (sm.ratio() < ALIKE) return mora
        val same = HashMap<Int, Int>()
        for ((i, j, n) in sm.matchingBlocks()) for (k in 0 until n) same[i + k] = j + k
        val out = mutableListOf<String>()
        var cut = 0
        for (i in flat.indices) {
            val j = same[i] ?: continue
            if (j + 1 !in stops) continue
            val next = if (i + 1 < flat.length) flat[i + 1] else 'a'
            if (!flat[i].isLetterOrDigit() || !next.isLetterOrDigit()) continue
            out += flat.substring(cut, i + 1)
            cut = i + 1
        }
        out += flat.substring(cut)
        return out.filter(String::isNotEmpty).joinToString(" ").ifEmpty { mora }
    }
}
