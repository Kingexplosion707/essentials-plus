package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.lyrics.spicy.romanization.HumanRomanization
import com.tx24.spicyplayer.network.data.awaitResponse
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared public song search, record lookup and lyric page parsing. One instance serves the lyrics,
 * romanization and translation lookups, which ask about the same song at the same moment: what
 * Genius answered is kept for a while and a request already out is waited for, so they share
 * their searches, records and pages instead of multiplying the burst Genius limits.
 */
class GeniusSongPages(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Answer(val at: Long, val value: Any?)
    private val answers = object : LinkedHashMap<String, Answer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>) = size > MAX_ANSWERS
    }
    private val asking = object : LinkedHashMap<String, Mutex>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mutex>) = size > MAX_ANSWERS
    }

    /** Forgets every answer, so clearing a cache or trying again really asks Genius again. */
    fun clear() {
        synchronized(answers) { answers.clear() }
    }

    /** [fetch]'s answer for [key], from a recent answer or a request already out. Failures aren't kept. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> remembered(key: String, fetch: suspend () -> T): T {
        val lock = synchronized(asking) { asking.getOrPut(key) { Mutex() } }
        return lock.withLock {
            val kept = synchronized(answers) { answers[key]?.takeIf { now() - it.at < ANSWER_MS } }
            if (kept != null) kept.value as T
            else fetch().also { value -> synchronized(answers) { answers[key] = Answer(now(), value) } }
        }
    }

    /**
     * Search the song, with extra reading-specific queries when looking for romanizations. With
     * [strict], a search that found nothing because every query failed throws instead of
     * answering "no hits", so a caller can tell an outage from a miss.
     */
    suspend fun search(title: String, artist: String, romanizations: Boolean = true, strict: Boolean = false): List<JsonObject> {
        val cleanTitle = title.replace(BRACKETED, "").trim()
        val cleanArtist = artist.replace(BRACKETED, "").trim()
        // Several credited artists ("ROSÉ, Bruno Mars") can bury the song under its translation
        // pages; the first artist alone finds it.
        val firstArtist = artist.split(ARTIST_SEPARATOR).first().trim().takeIf { it.isNotEmpty() && it != artist.trim() }
        // A title's other name in brackets ("ヤラララ(YARARARA)"): Genius's search may find the
        // song by it alone when the whole title finds nothing.
        val otherName = alternateTitles(title).firstOrNull()?.let { "$it ${firstArtist ?: cleanArtist}" }
        val queries = if (romanizations) listOfNotNull(
            "$title $artist",
            firstArtist?.let { "$cleanTitle $it" },
            otherName,
            "$cleanTitle $cleanArtist Romanized",
            "$cleanTitle Romanized",
            "$title Genius Romanizations",
        ) else listOfNotNull("$title $artist", "$cleanTitle $cleanArtist", firstArtist?.let { "$cleanTitle $it" }, otherName)
        val hits = mutableListOf<JsonObject>()
        val seen = mutableSetOf<Long>()
        var failure: Exception? = null
        for (query in queries) {
            if (query.isBlank()) continue
            try {
                val url = "https://genius.com/api/search/multi".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query.trim()).build()
                val sections = getJson(url.toString())?.getAsJsonObject("response")?.getAsJsonArray("sections")
                sections?.forEach { section ->
                    val s = section.asJsonObject
                    if (s.str("type") !in setOf("top_hit", "song")) return@forEach
                    s.getAsJsonArray("hits")?.forEach { h ->
                        val res = h.asJsonObject.getAsJsonObject("result") ?: return@forEach
                        val id = res.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                        if (isSong(res) && seen.add(id)) hits += res
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                failure = e
            }
            if (romanizations && hits.any(::isRomanization)) break
            // The song itself is all a lyrics or translation lookup needs: no more queries once found.
            if (!romanizations && hits.any { !isRomanization(it) && ours(it, title, artist) }) break
        }
        if (strict && hits.isEmpty()) failure?.let { throw it }
        return hits.sortedBy { !isRomanization(it) }
    }

    /**
     * The songs Genius's lyric index finds for our own [lines], most found first: the way in for a
     * track whose names Genius files differently ("SIGN IS B" for サインはB). Asks with the
     * [LYRIC_ASKS] longest lines; a song needs two of them to find it (one alone can be a quote),
     * and at most three are returned.
     */
    suspend fun byLyrics(lines: List<String>): List<JsonObject> {
        val asks = lines.map(String::trim).filter(String::isNotEmpty).distinct().sortedByDescending { it.length }.take(LYRIC_ASKS)
        if (asks.isEmpty()) return emptyList()
        val votes = LinkedHashMap<Long, Pair<JsonObject, Int>>()
        for (line in asks) {
            val url = "https://genius.com/api/search/multi".toHttpUrl().newBuilder().addQueryParameter("q", line.take(100)).build()
            val sections = runCatching { getJson(url.toString()) }.getOrNull()?.getAsJsonObject("response")?.getAsJsonArray("sections") ?: continue
            for (section in sections) {
                val s = section.asJsonObject
                if (s.str("type") != "lyric") continue
                s.getAsJsonArray("hits")?.forEach { h ->
                    val res = h.asJsonObject.getAsJsonObject("result") ?: return@forEach
                    val id = res.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                    if (!isSong(res)) return@forEach
                    votes[id] = res to ((votes[id]?.second ?: 0) + 1)
                }
            }
        }
        val need = minOf(2, asks.size)
        return votes.values.filter { it.second >= need }.sortedByDescending { it.second }.take(3).map { it.first }
    }

    /**
     * The search hits that are the song itself (not a romanization, translation or another
     * song), best first: a title that matches outright before one that only holds the other
     * ("Lemon" before "Lemon (Remix)").
     */
    suspend fun originals(hits: List<JsonObject>, title: String, artist: String): List<JsonObject> {
        var aliasLookups = 0
        return hits.filter { hit ->
            if (isRomanization(hit) || !sameTitle(hit, title)) return@filter false
            if (sameArtist(hit, artist)) return@filter true
            // The artist may be filed under another name: Genius's "Official髭男dism" is the
            // player's "Official HIGE DANdism". Its record lists the others; asked of a few hits only.
            aliasLookups++ <= MAX_ALIAS_LOOKUPS && aliasOf(hit, artist)
        }.sortedByDescending { exactTitle(it, title) }
    }

    /** Whether one of [artist]'s names is among the hit's primary artist's alternate names on Genius. */
    private suspend fun aliasOf(hit: JsonObject, artist: String): Boolean {
        val id = hit.getAsJsonObject("primary_artist")?.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return false
        val record = runCatching { getJson("https://genius.com/api/artists/$id") }
            .onFailure { if (it is CancellationException) throw it }.getOrNull()
            ?.getAsJsonObject("response")?.getAsJsonObject("artist") ?: return false
        val aliases = record.getAsJsonArray("alternate_names")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            .map(::norm).filter { it.length >= 2 }
        val names = artist.split(ARTIST_SEPARATOR).map(::norm).filter { it.length >= 2 }
        return names.any { name -> aliases.any { it == name || it in name || name in it } }
    }

    /** The song's own page record from a search, or null when Genius has none that's ours. Throws when Genius couldn't answer. */
    suspend fun original(title: String, artist: String): JsonObject? =
        originals(search(title, artist, romanizations = false, strict = true), title, artist).firstOrNull()

    private fun exactTitle(hit: JsonObject, title: String): Boolean {
        val theirs = hit.str("title")
        return norm(theirs) == norm(title) || norm(theirs.replace(BRACKETED, "")) == norm(title.replace(BRACKETED, ""))
    }

    /** A song, not an album: an album's id is a different id space and would fetch another song. */
    private fun isSong(hit: JsonObject): Boolean {
        val type = hit.str("_type").ifEmpty { hit.str("type") }
        return type.isEmpty() || type == "song"
    }

    /** Only entries that claim to be romanizations: the original-script page is no use. */
    fun isRomanization(hit: JsonObject): Boolean {
        if (hit.str("language").lowercase() in ROMAN_LANGUAGES) return true
        val fields = listOf(hit.str("title"), hit.str("full_title"), hit.str("title_with_featured"), artistOf(hit))
        return fields.any { ROMAN_HINT.containsMatchIn(it) }
    }

    /**
     * Whether a romanization page is for [artist]. They're filed under a Genius account, so the
     * page title is where the musician is named: "Ado - 踊 (Odo) (Romanized)". Unknown counts as yes.
     */
    fun byUs(hit: JsonObject, artist: String, title: String = ""): Boolean {
        val bare = hit.str("title").replace(ROMAN_HINT, "").trim(' ', '-', '(', ')', '[', ']')
        // Another version's romanization is no use either.
        if (title.isNotBlank() && otherVersion(bare.substringAfter(" - "), title)) return false
        if (artist.isBlank()) return true
        if (" - " !in bare) return true
        val ours = norm(artist)
        val theirs = norm(bare.substringBefore(" - "))
        if (ours.isEmpty() || theirs.isEmpty()) return true
        return ours in theirs || theirs in ours
    }

    /**
     * Whether a search hit is the song that was asked for. One title holds the other, whole or
     * with their bracketed parts (featured credits, alternate titles) set aside. Any one of our
     * credited artists in Genius's credits, featured ones included, is enough: a producer can be
     * filed under another name (the player's "32ki" is Genius's "サツキ (Satsuki)").
     */
    fun ours(hit: JsonObject, title: String, artist: String): Boolean = sameTitle(hit, title) && sameArtist(hit, artist)

    /**
     * One title holds the other, whole or without their bracketed parts, and the hit isn't
     * another version of the song ("Spring Day (Japanese Ver.)", "Pretender - Acoustic Ver"):
     * one has lines of its own, which neither pair nor time with the song's.
     */
    private fun sameTitle(hit: JsonObject, title: String): Boolean {
        fun holds(a: String, b: String) = a.isNotEmpty() && b.isNotEmpty() && (a == b || a in b || b in a)
        val theirTitle = hit.str("title")
        val held = holds(norm(theirTitle), norm(title)) || holds(norm(theirTitle.replace(BRACKETED, "")), norm(title.replace(BRACKETED, "")))
        // Or the two share a name in brackets outright: "ヤラララ(YARARARA)" and "やラララ (YARARARA)".
        val named = !held && alternateTitles(title).map(::norm).any { it.isNotEmpty() && it in alternateTitles(theirTitle).map(::norm) }
        return (held || named) && !otherVersion(theirTitle, title)
    }

    /** A title's other names in brackets; not featured credits or version notes. */
    private fun alternateTitles(title: String): List<String> =
        BRACKETED.findAll(title).map { it.value.drop(1).dropLast(1).trim() }
            .filter { it.length >= 2 && !CREDIT.containsMatchIn(it) && !VERSION.containsMatchIn(it) }
            .toList()

    private fun sameArtist(hit: JsonObject, artist: String): Boolean {
        val who = norm(artistOf(hit))
        val asked = norm(artist)
        if (asked.isEmpty() || who.isEmpty() || asked in who || who in asked) return true
        val credits = norm(hit.str("artist_names").ifEmpty { artistOf(hit) })
        return artist.split(ARTIST_SEPARATOR).map(::norm).any { it.length >= 2 && it in credits }
    }

    /**
     * The romanization Genius files against a song (`translation_songs`), if any. With [strict],
     * a record that couldn't be read throws instead of reading as none.
     */
    suspend fun linkedRomanization(songId: Long, strict: Boolean = false): JsonObject? = try {
        record(songId)
            ?.getAsJsonArray("translation_songs")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.str("language").lowercase() in ROMAN_LANGUAGES }
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        if (strict) throw e
        null
    }

    /** A Genius song page's lyric lines, cleaned; short or unavailable pages have no result. */
    suspend fun linesAt(url: String, minimum: Int = 4, strict: Boolean = false): List<String>? =
        textAt(url, strict)?.let(HumanRomanization::cleanLines)?.takeIf { it.size >= minimum }

    /**
     * A page's lyrics as text, section headers ("[Chorus]") kept. With [strict], a page that
     * couldn't be read (limited, server error, no connection) throws; a missing page is still null.
     */
    suspend fun textAt(url: String, strict: Boolean = false): String? {
        if (url.isBlank()) return null
        return try {
            remembered("page:$url") {
                val html = client.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).get().build())
                    .awaitResponse().use {
                        if (!it.isSuccessful && it.code != 404) throw ProviderHttpException("Genius", it.code)
                        if (it.isSuccessful) it.body?.string().orEmpty() else ""
                    }
                geniusLyricsText(html).takeIf(String::isNotBlank)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            if (strict) throw e
            null
        }
    }

    suspend fun record(songId: Long): JsonObject? =
        getJson("https://genius.com/api/songs/$songId")?.getAsJsonObject("response")?.getAsJsonObject("song")

    // Genius' front answers OkHttp's own user agent with HTTP 401. A missing page is null; any
    // other refusal (rate limit, server error) throws, so it isn't taken for a missing page.
    private suspend fun getJson(url: String): JsonObject? = remembered("json:$url") {
        client.newCall(Request.Builder().url(url).header("Referer", "https://genius.com/").header("User-Agent", BROWSER_UA).get().build())
            .awaitResponse().use { r ->
                if (r.code == 404) return@use null
                if (!r.isSuccessful) throw ProviderHttpException("Genius", r.code)
                gson.fromJson(r.body?.string(), JsonObject::class.java)
            }
    }

    private fun artistOf(hit: JsonObject) = hit.getAsJsonObject("primary_artist")?.str("name").orEmpty()
    // Hiragana folded into katakana: a title is written either way ("ヤラララ", Genius's "やラララ").
    private fun norm(s: String) = SpotifyTrackMatcher.normalize(s).replace(" ", "")
        .map { if (it in 'ぁ'..'ゖ') it + 0x60 else it }.joinToString("")
    private fun JsonObject.str(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    companion object {
        /** [theirs] names a version (in brackets or after a dash) that [ours] doesn't. */
        fun otherVersion(theirs: String, ours: String): Boolean {
            val extras = BRACKETED.findAll(theirs).map { it.value } + listOfNotNull(theirs.substringAfter(" - ", "").takeIf(String::isNotEmpty))
            return extras.filterNot(SAME_RECORDING::containsMatchIn)
                .any { extra -> VERSION.findAll(extra).any { it.value.lowercase() !in ours.lowercase() } }
        }

        /** Version notes that keep the recording: "(Deluxe Version)", "(Taylor's Version)", "(2015 Remaster)". */
        private val SAME_RECORDING = Regex("""deluxe|expanded|edition|taylor|explicit|clean|remaster|anniversary|original|album|single""", RegexOption.IGNORE_CASE)

        /** How many of our lines are asked of Genius's lyric index ([byLyrics]). */
        const val LYRIC_ASKS = 3
        const val MAX_ANSWERS = 64
        /** Artist records asked for alternate names per lookup: the first title matches are the likely ones. */
        const val MAX_ALIAS_LOOKUPS = 2
        /** A credit in a title's brackets, not another name for it. */
        val CREDIT = Regex("""^(?:feat\.?|ft\.?|with|prod\.?|from)\b""", RegexOption.IGNORE_CASE)
        /** What names another version of a song in its title. */
        val VERSION = Regex("""\b(?:ver(?:sion)?\b\.?|remix|live|acoustic|instrumental|inst\.|sped up|slowed|tv size|demo)""", RegexOption.IGNORE_CASE)
        /** Long enough for one song's lookups to share; short enough that an edited page shows up. */
        const val ANSWER_MS = 10 * 60_000L
        val ROMAN_HINT = Regex("romani[sz]ed|romani[sz]ation|\\bromaji\\b", RegexOption.IGNORE_CASE)
        val ROMAN_LANGUAGES = setOf("romanization", "romanized")
        val BRACKETED = Regex("[(\\[{].*?[)\\]}]")
        /** Between the names in a player's artist credit: "32ki, Hatsune Miku & 重音テト". */
        val ARTIST_SEPARATOR = Regex("""\s*(?:,|&|、|/|×|\bfeat\.?|\bft\.?|\bx\b)\s*""", RegexOption.IGNORE_CASE)
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    }
}
