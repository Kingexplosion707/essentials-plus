package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.lyrics.spicy.romanization.JapaneseRomanizerProvider
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.awaitResponse
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request

/** How the Chinese catalogues' search hits are matched to the song asked for. */
object CatalogueTitles {
    /** Version markers: a hit claiming one we didn't ask for is another recording. */
    private val CUT = Regex(
        "\\b(live|acoustic|cover|remix|instrumental|karaoke|nightcore|demo|tribute|rehearsal|session" +
            "|mashup|parody|sped[\\s-]?up|slowed|bootleg|re-?work|flip|vip)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKETED = Regex("\\s*[(（\\[【][^)）\\]】]*[)）\\]】]")
    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")
    /** Within this many seconds an exact title stands in for a byline written in another script. */
    private const val BILLED_APART_SLACK = 2.0

    private fun norm(s: String) = SpotifyTrackMatcher.normalize(s)

    /** The version markers a title carries ("Galaxies (RogueRemix)" counts as a remix). */
    private fun cutWords(text: String): Set<String> =
        CUT.findAll(CAMEL.replace(text, " ")).map { it.groupValues[1].lowercase().filter(Char::isLetter) }.toSet()

    /**
     * Whether [theirs] names the song [ours] does: equal, or equal once a bracketed tag is taken
     * off either one (新時代 for "新時代 (ウタ from ONE PIECE FILM RED)"), unless the tag claims a
     * version ours doesn't.
     */
    fun sameTitle(theirs: String, ours: String): Boolean {
        val a = norm(theirs)
        val b = norm(ours)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if ((cutWords(theirs) - cutWords(ours)).isNotEmpty()) return false
        return b == norm(BRACKETED.replace(theirs, "")) || a == norm(BRACKETED.replace(ours, ""))
    }

    /**
     * Whether [theirs] bills the artist [ours] does, or, where the two are written in different
     * scripts ("TK from Ling tosite sigure" against "TK from 凛として時雨"), the title is exact and
     * the lengths within two seconds.
     */
    fun sameArtist(theirs: String, ours: String, theirTitle: String, ourTitle: String, theirSeconds: Int, ourSeconds: Int): Boolean {
        if (norm(theirs).contains(norm(ours))) return true
        val a = kinds(theirs)
        val b = kinds(ours)
        return a.isNotEmpty() && b.isNotEmpty() && a != b && norm(ourTitle).isNotEmpty() && norm(theirTitle) == norm(ourTitle) &&
            ourSeconds > 0 && theirSeconds > 0 && abs(theirSeconds - ourSeconds) <= BILLED_APART_SLACK
    }

    /**
     * Whether a Japanese title [theirs] reads as the romanised [ours] ("可愛くてごめん" for
     * "Kawaikutegomen"), bracketed tags off both. Exact only; for a player that shows Japanese
     * titles romanised where the catalogue files them in their own script.
     */
    fun sameReading(theirs: String, ours: String): Boolean {
        if (kinds(ours) != setOf("latin") || theirs.none { it in '぀'..'ヿ' || it in '一'..'鿿' }) return false
        fun letters(s: String) = norm(s).filter(Char::isLetterOrDigit)
        val want = letters(BRACKETED.replace(ours, ""))
        if (want.isEmpty()) return false
        val read = runCatching { JapaneseRomanizerProvider.get().romanize(BRACKETED.replace(theirs, "")) }.getOrNull() ?: return false
        return letters(read) == want
    }

    /** The scripts a name is written in: cjk, hangul, latin. */
    fun kinds(text: String): Set<String> = buildSet {
        for (ch in text) when {
            ch in '぀'..'ヿ' || ch in '一'..'鿿' -> add("cjk")
            ch in '가'..'힯' -> add("hangul")
            ch.isLetter() && ch.code < 128 -> add("latin")
        }
    }
}

/**
 * A song's title and artist as its home storefront writes them, where the player gave them
 * translated or romanised ("New Genesis" by Ado is 新時代 in Japan). Apple's track IDs are the
 * same in every storefront, so the song is found in the US one and looked up in the home ones.
 * Asked once per song, and only for names with no native script already.
 */
class NativeNames(private val client: OkHttpClient, private val gson: Gson) {
    private val known = ConcurrentHashMap<String, CompletableDeferred<Pair<String, String>?>>()

    suspend fun of(request: LyricsLookupRequest): Pair<String, String>? {
        if (CatalogueTitles.kinds(request.title + request.artist).any { it != "latin" }) return null
        val key = "${request.title}\u0000${request.artist}\u0000${request.durationSeconds}"
        val mine = CompletableDeferred<Pair<String, String>?>()
        val pending = known.putIfAbsent(key, mine)
        if (pending != null) return pending.await()
        val got = try {
            lookUp(request)
        } catch (cancelled: CancellationException) {
            known.remove(key, mine)
            mine.cancel(cancelled)
            throw cancelled
        } catch (_: Exception) {
            null
        }
        mine.complete(got)
        return got
    }

    private suspend fun lookUp(request: LyricsLookupRequest): Pair<String, String>? {
        val term = URLEncoder.encode("${request.title} ${request.artist}", "UTF-8")
        val rows = get("https://itunes.apple.com/search?term=$term&entity=song&limit=10")
        val want = request.durationSeconds
        val track = rows.filter { row ->
            CatalogueTitles.sameTitle(row.text("trackName"), request.title) &&
                SpotifyTrackMatcher.normalize(row.text("artistName")).contains(SpotifyTrackMatcher.normalize(request.artist)) &&
                (want <= 0 || abs(row.seconds() - want) <= NEAR)
        }.minByOrNull { abs(it.seconds() - want) } ?: return null
        val id = track.get("trackId")?.asLong ?: return null
        for (store in STORES) {
            val home = get("https://itunes.apple.com/lookup?id=$id&country=$store").firstOrNull() ?: continue
            val name = home.text("trackName")
            val artist = home.text("artistName").ifBlank { request.artist }
            if (name.isBlank() || CatalogueTitles.kinds(name + artist).none { it != "latin" }) continue
            if (SpotifyTrackMatcher.normalize(name) != SpotifyTrackMatcher.normalize(request.title) ||
                SpotifyTrackMatcher.normalize(artist) != SpotifyTrackMatcher.normalize(request.artist)) return name to artist
        }
        return null
    }

    private suspend fun get(url: String): List<JsonObject> =
        client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            if (!response.isSuccessful) return emptyList()
            gson.fromJson(response.body?.string(), JsonObject::class.java)?.getAsJsonArray("results")
                ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
        }

    private fun JsonObject.text(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.seconds() = ((get("trackTimeMillis")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L) / 1000L).toInt()

    private companion object {
        /** The storefronts whose own names the Chinese catalogues file songs under. */
        val STORES = listOf("jp", "kr", "tw", "hk")
        const val NEAR = 3
    }
}
