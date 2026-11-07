package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import okhttp3.OkHttpClient

/** Finds a human romanization directly or through the original song's translation links. */
class GeniusRomanizationSource(private val pages: GeniusSongPages) {
    constructor(client: OkHttpClient, gson: Gson) : this(GeniusSongPages(client, gson))

    /**
     * The song's romanization as lyric lines, or null when Genius has none. A lookup that
     * couldn't finish (Genius limiting or unreachable) throws instead, so it isn't remembered as
     * missing. [lines], the lyric's own lines that need romanizing, are the last way in: Genius's
     * lyric index searched with them, for a title Genius files under another name.
     */
    suspend fun find(title: String, artist: String, lines: List<String> = emptyList()): List<String>? {
        val hits = pages.search(title, artist, strict = true)
        for (hit in hits) {
            if (!pages.isRomanization(hit) || !pages.byUs(hit, artist, title)) continue
            pages.linesAt(hit.get("url")?.asString.orEmpty(), strict = true)?.let { return it }
        }
        for (hit in pages.originals(hits, title, artist)) {
            val linked = pages.linkedRomanization(hit.get("id")?.asLong ?: continue, strict = true) ?: continue
            pages.linesAt(linked.get("url")?.asString.orEmpty(), strict = true)?.let { return it }
        }
        for (hit in pages.byLyrics(lines)) {
            val page = if (pages.isRomanization(hit)) hit else pages.linkedRomanization(hit.get("id")?.asLong ?: continue) ?: continue
            pages.linesAt(page.get("url")?.asString.orEmpty())?.let { return it }
        }
        return null
    }
}
