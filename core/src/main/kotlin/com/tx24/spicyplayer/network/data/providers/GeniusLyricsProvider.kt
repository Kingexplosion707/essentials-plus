package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/**
 * Genius's lyrics for the song, plain text. The song is found the way the romanization and
 * translation lookups find it, through the same [GeniusSongPages], so they share its requests.
 */
class GeniusLyricsProvider constructor(private val pages: GeniusSongPages) : RemoteLyricsProvider {
    constructor(client: OkHttpClient, gson: Gson) : this(GeniusSongPages(client, gson))

    override val descriptor = LyricsSourceDescriptor("genius", "Genius", 140, setOf(LyricsCapability.PLAIN_TEXT, LyricsCapability.CONTRIBUTOR_CREDITS),
        releaseChannel = SourceReleaseChannel.EXPERIMENTAL, defaultEnabled = false)

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = try {
        val song = pages.original(request.title, request.artist)
        val text = song?.let { pages.textAt(it.get("url")?.asString.orEmpty(), strict = true) }?.let(::withoutHeaders)
        if (text.isNullOrBlank()) ProviderResult.Miss else ProviderResult.Hit(RemoteLyricsPayload(plainLyrics = text))
    } catch (c: CancellationException) {
        throw c
    } catch (e: ProviderHttpException) {
        e.unavailable()
    } catch (e: IOException) {
        ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, e.message, true)
    } catch (e: Exception) {
        ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, e.message)
    }
}

/**
 * Genius's text as it's sung: no "[Verse 1: Jung Kook]" headers or "[米津玄師「Lemon」歌詞]" cards (which Genius starts with a zero-width space),
 * which shown as lyrics would be lines nobody sings. Stanza breaks stay.
 */
fun withoutHeaders(text: String): String = text.lines()
    .map { it.replace(ZERO_WIDTH, "").trim() }
    .filterNot { it.startsWith("[") && it.endsWith("]") }
    .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

private val ZERO_WIDTH = Regex("[\u200B-\u200D\uFEFF]")

/**
 * The lyrics on a Genius song page: the text of every `data-lyrics-container` div, which nest
 * divs of their own (a header with a translations menu, annotations). Parts marked
 * `data-exclude-from-selection` are page furniture, not lyrics, and are skipped.
 */
fun geniusLyricsText(html: String): String {
    val tag = Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>")
    val containers = mutableListOf<String>()
    var from = 0
    while (true) {
        val open = Regex("<div[^>]*data-lyrics-container=[\"']true[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html, from) ?: break
        val out = StringBuilder()
        var depth = 1
        var excludedAt = -1
        var at = open.range.last + 1
        while (depth > 0) {
            val next = tag.find(html, at) ?: break
            if (excludedAt < 0) out.append(html, at, next.range.first)
            at = next.range.last + 1
            val name = next.groupValues[2].lowercase()
            val closing = next.groupValues[1] == "/"
            if (name == "br") {
                if (excludedAt < 0) out.append('\n')
                continue
            }
            if (name !in NESTING_TAGS || next.groupValues[3].trimEnd().endsWith("/")) continue
            if (closing) {
                depth--
                if (depth == excludedAt) excludedAt = -1
            } else {
                if (excludedAt < 0 && "data-exclude-from-selection=\"true\"" in next.groupValues[3]) excludedAt = depth
                depth++
            }
        }
        containers += out.toString()
        from = at
    }
    return containers.joinToString("\n")
        .replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** Tags that open and close around content; void and inline tags don't change the nesting that matters. */
private val NESTING_TAGS = setOf("div", "span", "a", "i", "b", "em", "strong", "p", "button", "ul", "li", "svg", "path", "label", "section")
