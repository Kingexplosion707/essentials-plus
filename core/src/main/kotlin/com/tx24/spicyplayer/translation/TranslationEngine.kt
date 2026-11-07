package com.tx24.spicyplayer.translation

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps request positions separate from display positions, including split long lines. */
data class TranslationPiece(val index: Int, val part: Int, val text: String, val verse: Int = 0)

object TranslationBatching {
    const val GOOGLE_TEXT_LIMIT = 4_999
    const val DEEPL_LINES = 50

    fun batches(document: TranslationDocument, provider: TranslationProvider): List<List<TranslationPiece>> {
        val verses = mutableListOf<MutableList<TranslationPiece>>()
        var verse = mutableListOf<TranslationPiece>()
        document.lines.forEach { line ->
            if (line.boundary || line.verseStart) {
                if (verse.isNotEmpty()) verses.add(verse)
                verse = mutableListOf()
            }
            if (!line.boundary) {
                val texts = if (provider == TranslationProvider.Google) splitLongLine(line.text) else listOf(line.text)
                texts.forEachIndexed { part, text -> verse.add(TranslationPiece(line.index, part, text, verses.size)) }
            }
        }
        if (verse.isNotEmpty()) verses.add(verse)
        // DeepL reads the whole song as each request's context, so its lines just go in fifties.
        if (provider == TranslationProvider.DeepL) return verses.flatten().chunked(DEEPL_LINES)
        val batches = mutableListOf<List<TranslationPiece>>()
        var batch = mutableListOf<TranslationPiece>()
        verses.forEach { block ->
            if (size(batch + block) > provider.textLimit && batch.isNotEmpty()) {
                batches.add(batch)
                batch = mutableListOf()
            }
            // An oversized verse has to split between lines; each piece still keeps its address.
            block.forEach { piece ->
                if (size(batch + piece) > provider.textLimit && batch.isNotEmpty()) {
                    batches.add(batch)
                    batch = mutableListOf()
                }
                batch.add(piece)
            }
        }
        if (batch.isNotEmpty()) batches.add(batch)
        return batches
    }

    private fun splitLongLine(text: String): List<String> {
        val parts = mutableListOf<String>()
        var remaining = text
        while (remaining.length > GOOGLE_TEXT_LIMIT) {
            var end = remaining.lastIndexOf(' ', GOOGLE_TEXT_LIMIT).takeIf { it > 0 } ?: GOOGLE_TEXT_LIMIT
            if (remaining[end - 1].isHighSurrogate()) end--
            parts.add(remaining.substring(0, end))
            remaining = remaining.substring(end).trimStart()
        }
        if (remaining.isNotBlank()) parts.add(remaining)
        return parts
    }

    private fun size(pieces: List<TranslationPiece>): Int = pieces.sumOf { it.text.length } + (pieces.size - 1).coerceAtLeast(0)

    /**
     * A batch whose line count came back wrong, split into its verses to ask again. A single verse
     * isn't split further: asking line by line is a burst of requests that gets the connection
     * blocked, so its lines stay untranslated instead.
     */
    fun fallback(batch: List<TranslationPiece>): List<List<TranslationPiece>> {
        val verses = batch.groupBy { it.verse }.values.toList()
        return if (verses.size > 1) verses else emptyList()
    }

    /**
     * A positional response of the wrong size cannot reveal where a missing item was. A line in a
     * language [skips] leaves alone (a mixed song's lines in the target language), or that came
     * back as it went out, gets no translation under it.
     */
    fun align(batch: List<TranslationPiece>, response: TranslatorResponse, skips: (String) -> Boolean = { false }): Map<Pair<Int, Int>, String?> {
        if (response.lines.size != batch.size) return batch.associate { (it.index to it.part) to null }
        return batch.mapIndexed { position, piece ->
            val result = response.lines[position]
            (piece.index to piece.part) to result?.text?.let { trimStrayPunctuation(it, piece.text) }?.takeIf {
                result.needsTranslation && it.isNotBlank() && result.language?.let(skips) != true && !sameText(it, piece.text)
            }
        }.toMap()
    }
}

/**
 * A translator reading a line as part of a longer sentence ends it with the comma or dash that
 * sentence carries on with: dropped unless the original line ends with one of its own.
 */
fun trimStrayPunctuation(translation: String, original: String): String {
    if (original.trimEnd().lastOrNull()?.let { it in TRAILING } == true) return translation.trim()
    return translation.trim().trimEnd { it in TRAILING || it.isWhitespace() }
}

private const val TRAILING = ",;:、，；：—–-"

/** The same words, whatever the case, spacing and punctuation: a "translation" that changed nothing. */
fun sameText(a: String, b: String): Boolean {
    fun key(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)
    return key(a).let { it.isNotEmpty() && it == key(b) }
}

interface TranslationCache {
    fun read(key: TranslationKey, lineCount: Int): TranslationResult?
    fun write(result: TranslationResult)
}

sealed interface TranslationOutcome {
    data class Translated(val result: TranslationResult) : TranslationOutcome
    data class Skipped(val language: String?) : TranslationOutcome
}

class TranslationEngine(
    private val detector: SongLanguageDetector,
    private val cache: TranslationCache,
    /** Waits between requests and before a retry; tests pass one that doesn't. */
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Detects the language of the lyrics' own translations when they don't say. Separate from
     * [detector], whose answer is sent to translators as the source: this one only decides
     * whether the lyrics' translations are in the target language.
     */
    private val ownDetector: SongLanguageDetector = detector,
) {
    /** Until when each provider is left alone after it asked to be (wall clock). */
    private val restsUntil = java.util.concurrent.ConcurrentHashMap<TranslationProvider, Long>()
    private val languages = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean = size > 32
    }

    fun language(document: TranslationDocument, override: String?): String? = languageCode(override) ?: synchronized(languages) {
        if (!languages.containsKey(document.hash)) languages[document.hash] = languageCode(detector.detect(document.context))
        languages[document.hash]
    }

    /**
     * The lyrics' own translations, by line, when they are in the target language: the language
     * the lyrics give them, else the one they're detected in. Null when there are none to use.
     */
    fun ownTranslations(document: TranslationDocument, preferences: TranslationPreferences): List<String?>? {
        if (document.lines.none { it.own != null }) return null
        val language = document.ownLanguage ?: synchronized(languages) {
            val key = "own:" + document.hash
            if (!languages.containsKey(key)) languages[key] = languageCode(ownDetector.detect(document.ownText))
            languages[key]
        }
        if (language != languageCode(preferences.targetLanguage)) return null
        return document.lines.map { it.own }
    }

    /**
     * The song translated by its own translations alone, when they cover every line: nothing is
     * sent anywhere then. Null when a line still needs a translator.
     */
    fun ownResult(document: TranslationDocument, preferences: TranslationPreferences, source: String?): TranslationResult? {
        val own = ownTranslations(document, preferences) ?: return null
        if (document.lines.any { !it.boundary && own[it.index] == null }) return null
        val none = List<String?>(document.lines.size) { null }
        return combineTranslations(key(document, preferences, source), none, none, source, own = own)
    }

    fun key(document: TranslationDocument, preferences: TranslationPreferences, source: String?): TranslationKey =
        TranslationKey(document.hash, languageCode(preferences.targetLanguage) ?: "en", preferences.provider, languageCode(source), preferences.humanTranslations,
            preferences.aiVariant.takeIf { preferences.provider.isAi }.orEmpty())

    /**
     * [source] is a language the whole song is known to be in (one set for the song), and only
     * that is ever sent as the source language. Otherwise the provider detects each line's own,
     * so a mixed song's lines in the target language are left alone instead of being read as the
     * song's main language.
     */
    suspend fun translate(
        document: TranslationDocument,
        preferences: TranslationPreferences,
        source: String?,
        translator: Translator,
        /** The machine translation, shown while a slow human lookup is still out. */
        onPartial: suspend (TranslationResult) -> Unit = {},
        /** What happened, step by step, for the debug report. */
        trace: (String) -> Unit = {},
        /** Asked when [translator] times out or is busy; its answer is shown but not kept. */
        fallback: Translator? = null,
        human: suspend () -> GeniusTranslationPair? = { null },
    ): TranslationOutcome = coroutineScope {
        fun skips(language: String?) = skipTranslation(language, preferences.targetLanguage, preferences.excludedLanguages)
        if (skips(source)) return@coroutineScope TranslationOutcome.Skipped(source)
        // Translations that came with the lyrics, covering every line, need no translator.
        ownResult(document, preferences, source)?.let { return@coroutineScope TranslationOutcome.Translated(it) }
        require(translator.provider == preferences.provider)
        val key = key(document, preferences, source)
        cache.read(key, document.lines.size)?.let { stored ->
            // The lyrics' own lines win over a kept answer too, which may predate them being used.
            val cached = ownTranslations(document, preferences)?.let { own ->
                fun mine(i: Int) = own[i]?.takeIf(String::isNotBlank)
                stored.copy(texts = stored.texts.mapIndexed { i, text -> mine(i) ?: text },
                    origins = stored.origins.mapIndexed { i, origin -> if (mine(i) != null) TranslationOrigin.Lyrics else origin })
            } ?: stored
            val shown = cached.without(preferences.excludedLanguages)
            trace("from cache: ${shown.lineSources()}")
            return@coroutineScope if (shown.texts.all { it == null }) TranslationOutcome.Skipped(cached.detectedLanguage)
            else TranslationOutcome.Translated(shown)
        }
        if (document.lines.all { it.boundary }) return@coroutineScope TranslationOutcome.Skipped(source)
        val none = List<String?>(document.lines.size) { null }
        val own = ownTranslations(document, preferences) ?: none
        // A failed lookup (Genius limiting or unreachable) isn't the same as no translation: the
        // result is then shown but not kept, so the next play asks Genius again.
        var humanFailed = false
        val lookup = if (preferences.humanTranslations) async {
            try {
                human()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                humanFailed = true
                trace("Genius failed: ${error.message ?: error.javaClass.simpleName}")
                null
            }
        } else null
        // A human translation already on hand (cached) decides before any machine request; a slow
        // lookup doesn't hold the machine translation back.
        val quick = lookup?.let { withTimeoutOrNull(QUICK_HUMAN_MS) { it.await() } }
        var humanTexts = quick?.let { HumanTranslation.align(document, it) } ?: none
        fun traceHuman(pair: GeniusTranslationPair?, texts: List<String?>) {
            if (humanFailed) return
            trace(if (pair == null) "Genius: no ${preferences.targetLanguage} translation"
                else "Genius: ${texts.count { it != null }} lines matched of ${pair.originals.size} on the page")
        }
        if (quick != null) traceHuman(quick, humanTexts)
        if (document.lines.all { it.boundary || humanTexts[it.index] != null || own[it.index] != null }) {
            val result = combineTranslations(key, humanTexts, none, source ?: languageCode(quick?.sourceLanguage), own = own)
            cache.write(result)
            return@coroutineScope TranslationOutcome.Translated(result)
        }
        // Keep the whole original song in the machine request, even where human lines win.
        if (TranslationBatching.batches(document, translator.provider).isEmpty()) return@coroutineScope TranslationOutcome.Skipped(source)
        var requests = 0
        suspend fun machine(translator: Translator): MachinePass {
            val batches = TranslationBatching.batches(document, translator.provider)
            val aligned = mutableMapOf<Pair<Int, Int>, String?>()
            val lineLanguages = mutableMapOf<Int, String>()
            val languageWeights = mutableMapOf<String, Int>()
            var answered = false
            suspend fun send(batch: List<TranslationPiece>) {
                if (requests++ > 0) pause(PACE_MS)
                val response = ask(translator, TranslatorRequest(batch.map { it.text }, key.targetLanguage, source, document.context), trace)
                if (response.lines.size != batch.size) {
                    trace("asked ${batch.size} lines, ${response.lines.size} came back")
                    if (translator.provider != TranslationProvider.Google && !translator.provider.isAi) throw TranslationFailure("Translation returned an incomplete response. Try again later.")
                    if (batch.size > 1) TranslationBatching.fallback(batch).forEach { send(it) }
                    return
                }
                answered = true
                val weights = mutableMapOf<String, Int>()
                batch.forEachIndexed { position, piece ->
                    val lineLanguage = languageCode(response.lines[position]?.language)
                    if (lineLanguage != null && piece.part == 0) lineLanguages[piece.index] = lineLanguage
                    if (piece.part != 0) return@forEachIndexed
                    val language = lineLanguage ?: languageCode(response.detectedLanguage) ?: return@forEachIndexed
                    // By lines, not characters: a line of Hangul or kanji is far shorter than its English.
                    weights.merge(language, 1, Int::plus)
                }
                weights.forEach { (language, weight) -> languageWeights.merge(language, weight, Int::plus) }
                aligned.putAll(TranslationBatching.align(batch, response) { skips(it) })
                trace("${translator.provider.label}: ${batch.size} lines" +
                    weights.entries.sortedByDescending { it.value }.joinToString(" ") { it.key }.takeIf(String::isNotEmpty)?.let { ", in $it" }.orEmpty())
            }
            batches.forEach { send(it) }
            val pieces = batches.flatten().groupBy { it.index }
            val texts = document.lines.map { line ->
                val parts = pieces[line.index].orEmpty()
                // A missing chunk leaves the entire original line intact.
                if (parts.isEmpty() || parts.any { aligned[it.index to it.part] == null }) null
                else parts.joinToString(" ") { aligned.getValue(it.index to it.part).orEmpty() }
            }
            return MachinePass(translator.provider, texts, document.lines.map { lineLanguages[it.index] },
                // The song's language is the one most of its lines are in.
                languageWeights.maxByOrNull { it.value }?.key,
                answered && batches.flatten().all { (it.index to it.part) in aligned })
        }
        // A slow or busy translator doesn't throw away what else there is: the fallback's lines,
        // or Genius's. Only when neither has any does the failure stand.
        var failure: Exception? = null
        val pass = try {
            machine(translator)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error !is java.io.IOException && error !is ProviderResting) throw error
            failure = error
            trace("${translator.provider.label} failed: ${error.message ?: error.javaClass.simpleName}")
            fallback?.let { backup ->
                trace("asking ${backup.provider.label} instead")
                try {
                    machine(backup)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (backupError: Exception) {
                    trace("${backup.provider.label} failed: ${backupError.message ?: backupError.javaClass.simpleName}")
                    null
                }
            } ?: error.takeIf { lookup == null }?.let { throw it }
        }
        val texts = pass?.texts ?: none
        val perLine = pass?.languages ?: none
        val detected = source ?: pass?.language ?: languageCode(quick?.sourceLanguage)
        if (lookup != null && quick == null) {
            if (texts.any { it != null }) onPartial(combineTranslations(key, none, texts, detected, perLine, own, pass?.provider ?: key.provider))
            val pair = lookup.await()
            humanTexts = pair?.let { HumanTranslation.align(document, it) } ?: none
            traceHuman(pair, humanTexts)
        }
        val result = combineTranslations(key, humanTexts, texts, detected ?: languageCode(lookup?.await()?.sourceLanguage),
            perLine, own, pass?.provider ?: key.provider)
        if (result.texts.all { it == null }) {
            failure?.let { throw it }
            // Every line answered and none needed translating: the song is in the target language
            // (or ones left alone). Kept, so it's known the next time it plays.
            if (pass == null || !pass.complete) throw TranslationFailure("Couldn't translate these lyrics. Try again later.")
            if (!humanFailed) cache.write(result)
            return@coroutineScope TranslationOutcome.Skipped(detected)
        }
        when {
            failure != null -> trace("not cached: ${translator.provider.label} gets asked again next time")
            humanFailed -> trace("not cached: Genius gets asked again next time")
            else -> cache.write(result)
        }
        TranslationOutcome.Translated(result)
    }

    /** One translator's answer for the whole song. */
    private class MachinePass(
        val provider: TranslationProvider,
        val texts: List<String?>,
        val languages: List<String?>,
        val language: String?,
        /** Every request was answered with the lines it asked for. */
        val complete: Boolean,
    )

    /**
     * [Translator.translate], waiting out a busy provider briefly. One that asks for longer, or
     * stays busy, is left alone until then. Google's limit is a block on the connection that
     * asking again only lengthens, so it is never retried at once.
     */
    private suspend fun ask(translator: Translator, request: TranslatorRequest, trace: (String) -> Unit): TranslatorResponse {
        val provider = translator.provider
        restsUntil[provider]?.let { if (now() < it) throw resting(provider, it) }
        var attempt = 0
        while (true) {
            try {
                return translator.translate(request)
            } catch (busy: ProviderBusy) {
                val asked = busy.retryAfterMs
                if (provider == TranslationProvider.Google || attempt >= BUSY_RETRIES || asked != null && asked > MAX_WAIT_MS) {
                    val rest = asked?.coerceIn(MIN_REST_MS, MAX_REST_MS) ?: if (provider == TranslationProvider.Google) GOOGLE_REST_MS else MIN_REST_MS
                    val until = now() + rest
                    restsUntil[provider] = until
                    trace("${provider.label} is limiting (retry after: ${asked?.let { "${it / 1000} s" } ?: "not given"}), left alone for ${rest / 1000} s")
                    throw resting(provider, until, busy.detail)
                }
                val wait = asked?.coerceAtLeast(1_000L) ?: (BUSY_WAIT_MS * (attempt + 1))
                trace("${provider.label} is busy, asking again in ${wait / 1000} s")
                pause(wait)
                attempt++
            }
        }
    }

    private fun resting(provider: TranslationProvider, until: Long, detail: String? = null): ProviderResting {
        val minutes = ((until - now()).coerceAtLeast(0L) + 59_999L) / 60_000L
        val wait = if (minutes <= 1) "a minute" else "$minutes minutes"
        return ProviderResting(provider, until, when (provider) {
            TranslationProvider.Google -> "Google is limiting translations on this connection. Trying again in $wait, or use DeepL."
            TranslationProvider.DeepL -> "DeepL is busy right now. Trying again in $wait."
            // An AI service says which limit it hit (per minute, per day…), which is worth passing on.
            else -> "${provider.label} is busy right now. Trying again in $wait." + detail?.let { " It said: $it" }.orEmpty()
        })
    }

    private companion object {
        /** How long a human lookup gets before the machine translation goes ahead without it. */
        const val QUICK_HUMAN_MS = 300L
        const val PACE_MS = 300L
        const val BUSY_RETRIES = 2
        const val BUSY_WAIT_MS = 2_000L
        /** The longest a busy provider is waited out in place; one asking for longer is left to rest. */
        const val MAX_WAIT_MS = 10_000L
        const val MIN_REST_MS = 60_000L
        const val MAX_REST_MS = 30 * 60_000L
        const val GOOGLE_REST_MS = 10 * 60_000L
    }
}

