package com.tx24.spicyplayer.translation

import com.google.gson.JsonParser
import com.tx24.spicyplayer.network.data.awaitResponse
import java.io.IOException
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class GoogleTranslator(private val client: OkHttpClient) : Translator {
    override val provider = TranslationProvider.Google
    /**
     * Chrome's translate endpoint first, then the web one: each has its own allowance. When both
     * are limiting this connection, it's [ProviderBusy]; asking again soon only lengthens a block.
     */
    override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
        var retryAfter: Long? = null
        /** The body, or null when the endpoint is limiting this connection. */
        suspend fun call(http: Request): String? = client.newCall(http).awaitResponse().use { response ->
            if (response.code == 429 || response.code == 503) {
                retryAfterMs(response)?.let { asked -> retryAfter = maxOf(retryAfter ?: 0L, asked) }
                return@use null
            }
            if (!response.isSuccessful) throw IOException("Translation is unavailable (HTTP ${response.code}).")
            response.body.string()
        }
        call(googleChromeRequest(request))?.let { return googleChromeResponse(it, request.source) }
        call(googleRequest(request))?.let { return googleResponse(it) }
        throw ProviderBusy(retryAfter)
    }
}

/** How long a 429 or 503 asked to be left alone, in seconds or as a date; null when it didn't say. */
fun retryAfterMs(response: okhttp3.Response, now: Long = System.currentTimeMillis()): Long? {
    val value = response.header("Retry-After")?.trim() ?: return null
    value.toLongOrNull()?.let { return it.coerceAtLeast(0L) * 1_000L }
    return response.headers.getDate("Retry-After")?.time?.minus(now)?.coerceAtLeast(0L)
}

/** Each line its own `q`: Google then detects each line's language, not one for them all. */
fun googleChromeRequest(request: TranslatorRequest): Request {
    if (request.lines.sumOf { it.length } + request.lines.size - 1 > TranslationBatching.GOOGLE_TEXT_LIMIT) {
        throw TranslationFailure("This request exceeds Google's text limit.")
    }
    val url = "https://translate.googleapis.com/translate_a/t".toHttpUrl().newBuilder()
        .addQueryParameter("client", "dict-chrome-ex")
        .addQueryParameter("sl", request.source?.let(TranslationLanguages::googleCode) ?: "auto")
        .addQueryParameter("tl", TranslationLanguages.googleCode(request.target)).build()
    val body = FormBody.Builder().apply { request.lines.forEach { add("q", it) } }.build()
    return Request.Builder().url(url).post(body).build()
}

/**
 * One item per `q`: `["translation","ja"]` when the language was detected, a bare
 * `"translation"` when it was given. The song's language is the one most of its lines are in.
 */
fun googleChromeResponse(json: String, source: String?): TranslatorResponse {
    val items = JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray ?: throw IOException("Google returned an invalid response.")
    val lines = items.map { item ->
        val (text, language) = if (item.isJsonArray) {
            val pair = item.asJsonArray
            pair.getOrNull(0)?.takeIf { it.isJsonPrimitive }?.asString to pair.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asString
        } else item.takeIf { it.isJsonPrimitive }?.asString to source
        TranslationEntry(text?.takeIf(String::isNotBlank), language = languageCode(language))
    }
    val main = lines.mapNotNull { it.language }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    return TranslatorResponse(lines, main ?: source)
}

private fun com.google.gson.JsonArray.getOrNull(index: Int) = if (index < size()) get(index) else null

fun googleRequest(request: TranslatorRequest): Request {
    val text = request.lines.joinToString("\n")
    if (text.length > TranslationBatching.GOOGLE_TEXT_LIMIT) throw TranslationFailure("This request exceeds Google's text limit.")
    val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
        .addQueryParameter("client", "gtx").addQueryParameter("dt", "t").addQueryParameter("dj", "1")
        .addQueryParameter("sl", request.source?.let(TranslationLanguages::googleCode) ?: "auto")
        .addQueryParameter("tl", TranslationLanguages.googleCode(request.target)).build()
    return Request.Builder().url(url).post(FormBody.Builder().add("q", text).build()).build()
}

fun googleResponse(json: String): TranslatorResponse {
    val root = JsonParser.parseString(json).asJsonObject
    val sentences = root.getAsJsonArray("sentences") ?: throw IOException("Google returned an invalid response.")
    val text = sentences.joinToString("") { sentence ->
        sentence.asJsonObject.get("trans")?.takeUnless { it.isJsonNull }?.asString
            ?: throw IOException("Google returned an invalid sentence.")
    }
    return TranslatorResponse(text.replace("\r\n", "\n").split('\n').map { TranslationEntry(it.takeIf(String::isNotBlank)) },
        root.get("src")?.takeUnless { it.isJsonNull }?.asString)
}

class DeepLTranslator(private val client: OkHttpClient, private val key: String) : Translator {
    override val provider = TranslationProvider.DeepL
    override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
        if (!DeepLKey.valid(key)) throw TranslationFailure("Add your DeepL API key in Translation settings.")
        val response = client.newCall(deepLRequest(request, key))
            .awaitResponse().use { response ->
                when (response.code) {
                    429 -> throw ProviderBusy(retryAfterMs(response))
                    403 -> throw TranslationFailure("DeepL didn't accept your API key. Check it in Translation settings.")
                    456 -> throw TranslationFailure("Your DeepL translation quota is used up for now.")
                }
                if (!response.isSuccessful) throw IOException("DeepL couldn't translate these lyrics (HTTP ${response.code}).")
                JsonParser.parseString(response.body.string()).asJsonObject
            }
        val translations = response.getAsJsonArray("translations")?.map { it.takeIf { item -> item.isJsonObject }?.asJsonObject }
            ?: throw IOException("DeepL returned an invalid response.")
        val lines = translations.map { item ->
            val text = item?.get("text")?.takeUnless { it.isJsonNull }?.asString?.trim()?.takeIf(String::isNotBlank)
            val language = item?.get("detected_source_language")?.takeUnless { it.isJsonNull }?.asString
            text?.let { TranslationEntry(it, language = languageCode(language)) }
        }
        val main = lines.mapNotNull { it?.language }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return TranslatorResponse(lines, main ?: request.source)
    }
}

/**
 * Each line its own `text`, so DeepL detects each line's language (a mixed song's lines in the
 * target language come back marked as such), with the whole song as `context`: read for meaning,
 * never translated or billed.
 */
fun deepLRequest(request: TranslatorRequest, key: String): Request {
    fun code(language: String): String = if (languageCode(language) == "no") "NB" else language.uppercase(java.util.Locale.ROOT)
    if (!TranslationLanguages.supports(TranslationProvider.DeepL, request.target)) {
        throw TranslationFailure("DeepL can't translate into ${TranslationLanguages.name(request.target)}. Pick another target language or use Google.")
    }
    val body = FormBody.Builder().apply {
        request.lines.forEach { add("text", it) }
        add("target_lang", code(request.target))
        // A song language DeepL doesn't know is left for it to detect line by line.
        request.source?.takeIf { TranslationLanguages.supports(TranslationProvider.DeepL, it) }?.let { add("source_lang", code(it)) }
        if (request.context.isNotBlank()) add("context", request.context)
        add("split_sentences", "nonewlines")
        add("preserve_formatting", "1")
    }.build()
    if (body.contentLength() > 128 * 1024) throw TranslationFailure("This song exceeds DeepL's request size limit.")
    return Request.Builder().url("${DeepLKey.host(key)}/v2/translate")
        .header("Authorization", "DeepL-Auth-Key ${key.trim()}").post(body).build()
}
