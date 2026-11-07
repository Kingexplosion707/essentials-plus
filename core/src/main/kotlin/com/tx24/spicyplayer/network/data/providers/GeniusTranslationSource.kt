package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tx24.spicyplayer.translation.GeniusTranslationPair
import com.tx24.spicyplayer.translation.TranslationLanguages
import com.tx24.spicyplayer.translation.languageCode
import okhttp3.OkHttpClient

class GeniusTranslationSource(private val pages: GeniusSongPages) {
    constructor(client: OkHttpClient, gson: Gson) : this(GeniusSongPages(client, gson))

    /**
     * The song's translation into [target], or null when Genius has none that pairs. A lookup
     * that couldn't finish (Genius limiting or unreachable) throws instead, so it isn't
     * remembered as missing.
     */
    suspend fun find(title: String, artist: String, target: String): GeniusTranslationPair? {
        val hits = pages.search(title, artist, romanizations = false, strict = true)
        for (hit in pages.originals(hits, title, artist)) {
            val song = pages.record(hit.get("id")?.asLong ?: continue) ?: continue
            val linked = linkedTranslations(song, target).takeIf { it.isNotEmpty() } ?: continue
            val original = pages.textAt(song.get("url")?.asString.orEmpty(), strict = true) ?: continue
            // The first page that pairs with the original; a sung cover in the same language has
            // its own lines and pairs with little or nothing.
            for (page in linked) {
                val translated = pages.textAt(page.get("url")?.asString.orEmpty(), strict = true) ?: continue
                GeniusTranslationPair.fromText(original, translated, song.get("language")?.asString)?.let { return it }
            }
        }
        return null
    }
}

/**
 * The pages linked to [song] in the [target] language. Their language field decides which are in
 * it; ones titled as a translation go first, and a sung "official English version" or cover, which
 * has lyrics of its own rather than a translation, is left out.
 */
fun linkedTranslations(song: JsonObject, target: String): List<JsonObject> =
    song.getAsJsonArray("translation_songs")?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }.orEmpty()
        .filterNot { linked -> linked.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().contains(COVER) }
        .filter { linked ->
            val language = linked.get("language")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val code = TranslationLanguages.common.entries.firstOrNull { it.value.equals(language, ignoreCase = true) }?.key ?: languageCode(language)
            code != null && code == languageCode(target)
        }
        .sortedByDescending { linked -> linked.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().contains("translat", ignoreCase = true) }

private val COVER = Regex("""\b(version|cover)\b""", RegexOption.IGNORE_CASE)
