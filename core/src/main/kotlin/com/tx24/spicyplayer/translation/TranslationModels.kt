package com.tx24.spicyplayer.translation

import com.tx24.spicyplayer.lyrics.TimedLine
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import java.security.MessageDigest
import java.util.Locale

enum class TranslationMode(val label: String) { UnderLine("Under each line"), Replace("Replace") }
enum class TranslationProvider(val label: String) {
    Google("Google Translate"), DeepL("DeepL"),
    OpenAi("AI (OpenAI-compatible)"), Gemini("AI (Gemini)"), GeminiNano("On-device AI (Gemini Nano)");

    /** A language model, asked with a prompt: it can get a line count wrong, and is asked again verse by verse. */
    val isAi: Boolean get() = this == OpenAi || this == Gemini || this == GeminiNano
    /** Nothing leaves the phone. */
    val onDevice: Boolean get() = this == GeminiNano
    /** The most text one request carries; Gemini Nano reads under 4,000 tokens, prompt included. */
    val textLimit: Int get() = when (this) {
        GeminiNano -> 1_200
        OpenAi, Gemini -> 4_000
        else -> TranslationBatching.GOOGLE_TEXT_LIMIT
    }

    companion object {
        fun stored(value: String?): TranslationProvider = entries.firstOrNull { it.name == value } ?: Google
    }
}

/** Who translated a line; [Lyrics] is the lyrics' own translation, which came with them. */
enum class TranslationOrigin(val label: String) {
    Lyrics("Lyrics"), Genius("Genius"), Google("Google"), DeepL("DeepL"),
    // Named as the providers are: a machine line's origin is its provider's.
    OpenAi("AI"), Gemini("Gemini"), GeminiNano("Gemini Nano"),
}

object TranslationConsent {
    /**
     * An OpenAI-compatible translator's consent names its [service] (host): agreeing to send
     * lyrics to one server doesn't cover another one set later.
     */
    fun id(provider: TranslationProvider, human: Boolean, service: String? = null): String =
        "translation_${provider.name.lowercase(Locale.ROOT)}" +
            (if (provider == TranslationProvider.OpenAi) "_" + service.orEmpty().lowercase(Locale.ROOT) else "") +
            if (human) "_genius" else ""

    fun covered(disclosed: Set<String>, provider: TranslationProvider, human: Boolean, service: String? = null): Boolean =
        id(provider, human, service) in disclosed ||
            (!human && (id(provider, true, service) in disclosed || provider == TranslationProvider.DeepL && "DeepL" in disclosed))
}

data class TranslationPreferences(
    val targetLanguage: String = languageCode(Locale.getDefault().language) ?: "en",
    val automatic: Boolean = false,
    val provider: TranslationProvider = TranslationProvider.Google,
    val excludedLanguages: Set<String> = emptySet(),
    val humanTranslations: Boolean = true,
    /** Which AI service and model translate ("host model"), so changing either translates afresh. Blank for the others. */
    val aiVariant: String = "",
)

/** A main line and each of its background voices have distinct, stable addresses. */
data class LyricAddress(val lineIndex: Int, val backgroundIndex: Int? = null)
data class OriginalLine(
    val index: Int, val address: LyricAddress, val text: String, val boundary: Boolean, val verseStart: Boolean = false,
    /** The lyrics' own translation of the line. */
    val own: String? = null,
)

data class TranslationDocument(
    val song: String,
    val source: String,
    val lines: List<OriginalLine>,
    /** The language of the lines' [OriginalLine.own] translations, when the lyrics say. */
    val ownLanguage: String? = null,
) {
    val context: String get() = lines.joinToString("\n") { if (it.boundary) "" else it.text }
    val hash: String = translationHash(listOf(song, source, ownLanguage.orEmpty()) + lines.flatMap {
        listOf(it.address.toString(), it.text, it.boundary.toString(), it.verseStart.toString(), it.own.orEmpty())
    })
    /** The own translations as one text, for detecting their language. */
    val ownText: String get() = lines.mapNotNull { it.own }.joinToString("\n")

    companion object {
        fun from(song: String, source: String, lines: List<TimedLine>, ownLanguage: String? = null): TranslationDocument {
            val leads = lines.mapIndexedNotNull { index, line ->
                line.groupId?.takeIf { line.role == LineRole.LEAD }?.let { it to index }
            }.toMap()
            val backgrounds = mutableMapOf<Int, Int>()
            var previousLead: TimedLine? = null
            return TranslationDocument(song, source, lines.mapIndexed { index, line ->
                val parent = line.groupId?.let(leads::get) ?: index
                val address = if (line.role == LineRole.BACKGROUND) {
                    val background = backgrounds.getOrDefault(parent, 0)
                    backgrounds[parent] = background + 1
                    LyricAddress(parent, background)
                } else LyricAddress(index)
                val text = buildString {
                    // A line split to wrap has blank pieces holding its romanization; they aren't text.
                    line.words.filter { it.text.isNotEmpty() }.forEach { word ->
                        if (isNotEmpty() && !word.attached) append(' ')
                        append(word.text)
                    }
                }
                // A timed instrumental gap is a verse boundary even when TTML has no blank rows.
                val verseStart = line.role == LineRole.LEAD && previousLead?.let { line.startMs - it.endMs >= 3_000L } == true
                if (line.role == LineRole.LEAD) previousLead = line
                val boundary = line.role == LineRole.INTERLUDE || !isTranslationContent(text)
                OriginalLine(index, address, text, boundary, verseStart, own = line.translation?.takeIf { !boundary && it.isNotBlank() })
            }, languageCode(ownLanguage))
        }
    }
}

fun isTranslationContent(text: String): Boolean = text.any(Char::isLetterOrDigit) && !INSTRUMENTAL_MARKER.matches(text.trim())
private val INSTRUMENTAL_MARKER = Regex("(?:\\[(?:instrumental|interlude|music)]|\\((?:instrumental|interlude|music)\\))", RegexOption.IGNORE_CASE)

/**
 * The one code a language goes by everywhere: in the pickers, in stored settings and in what a
 * provider detects. The region is dropped (`zh-TW` is `zh`) and an old or alternative code becomes
 * the listed one, so excluding Filipino (`tl`) also catches lyrics detected as `fil`.
 */
fun languageCode(code: String?): String? = code?.trim()?.lowercase(Locale.ROOT)
    ?.replace('_', '-')?.substringBefore('-')?.let { LANGUAGE_ALIASES[it] ?: it }?.takeUnless { it in NO_LANGUAGE }

private val LANGUAGE_ALIASES = mapOf(
    "iw" to "he", "in" to "id", "ji" to "yi", "jw" to "jv", "mo" to "ro",
    "nb" to "no", "nn" to "no", "fil" to "tl", "tgl" to "tl", "prs" to "fa",
)
private val NO_LANGUAGE = setOf("und", "auto", "")

fun skipTranslation(source: String?, target: String, excluded: Set<String>): Boolean {
    val language = languageCode(source) ?: return false
    return language == languageCode(target) || excluded.any { languageCode(it) == language }
}

/**
 * Whether the translate button would help with lyrics in [language]: not once they're known to be
 * in the target language or one left untranslated, but always while a translation shows, so it
 * can be turned off. An unknown language keeps the button.
 */
fun translateButtonHelps(translated: Boolean, language: String?, preferences: TranslationPreferences): Boolean =
    translated || !skipTranslation(language, preferences.targetLanguage, preferences.excludedLanguages)

/**
 * Whether a fair share of [lines] are in another script than the rest, like Japanese with English
 * verses: two languages, so no one guess at the song's language speaks for all of it.
 */
fun mixedScripts(lines: List<String>): Boolean {
    val worded = lines.filter { line -> line.any(Char::isLetter) }
    if (worded.size < MIXED_MIN_LINES) return false
    val other = worded.count { line -> line.any { it.isLetter() && !latinLetter(it) } }
    return other.toDouble() / worded.size in MIXED_SHARE
}

/** Latin, accented Latin and Vietnamese's extra letters. */
private fun latinLetter(c: Char) = c.code < 0x250 || c.code in 0x1E00..0x1EFF

private const val MIXED_MIN_LINES = 4
/** A line or two in another script is a loanword or a title, not a second language. */
private val MIXED_SHARE = 0.15..0.85

fun translationHash(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach {
        val bytes = it.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

data class TranslationKey(
    val lyricsHash: String,
    val targetLanguage: String,
    val provider: TranslationProvider,
    val sourceLanguage: String?,
    val humanTranslations: Boolean = true,
    /** The AI service and model ([TranslationPreferences.aiVariant]); blank for the other providers. */
    val variant: String = "",
) {
    fun fileName(version: Int = TRANSLATION_CACHE_VERSION): String = translationHash(
        listOf(version.toString(), lyricsHash, targetLanguage, provider.name, sourceLanguage.orEmpty(), humanTranslations.toString()) +
            listOfNotNull(variant.takeIf(String::isNotEmpty)),
    ) + ".json"
}

const val TRANSLATION_CACHE_VERSION = 9

data class TranslationResult(
    val key: TranslationKey,
    val texts: List<String?>,
    /** The song's main language, for display; a mixed song's lines can each be in another. */
    val detectedLanguage: String?,
    val origins: List<TranslationOrigin?> = texts.map { if (it == null) null else TranslationOrigin.valueOf(key.provider.name) },
    /** Each line's own language, where the provider said; null where it didn't. */
    val languages: List<String?> = texts.map { null },
) {
    /** Without the lines in a language that is now [excluded]; a line of unknown language goes by the song's. */
    fun without(excluded: Set<String>): TranslationResult {
        val codes = excluded.mapNotNull(::languageCode).toSet()
        if (codes.isEmpty()) return this
        val dropped = texts.indices.filter { languageCode(languages.getOrNull(it) ?: detectedLanguage) in codes }
        if (dropped.isEmpty()) return this
        return copy(texts = texts.mapIndexed { i, text -> text.takeIf { i !in dropped } },
            origins = origins.mapIndexed { i, origin -> origin.takeIf { i !in dropped } })
    }

    /** Where the lines came from, for the debug report and a tap's message: "Genius 22, Google 37, none 4". */
    fun lineSources(): String {
        val counts = origins.groupingBy { it?.label ?: "none" }.eachCount()
        val order = TranslationOrigin.entries.map { it.label } + "none"
        return order.mapNotNull { name -> counts[name]?.let { "$name $it" } }.joinToString(", ")
    }

    /** Who translated the lines on screen, for the credits: "Genius + Google". */
    fun credit(): String? = TranslationOrigin.entries.filter { it in origins }.takeIf { it.isNotEmpty() }
        ?.joinToString(" + ") { it.label }
}

/** A generation also rejects an old request after off/on, even when its document is identical. */
class TranslationSession {
    data class Ticket(val generation: Long, val key: TranslationKey)
    private var generation = 0L
    private var current: Ticket? = null
    fun begin(key: TranslationKey): Ticket = Ticket(++generation, key).also { current = it }
    fun invalidate() { generation++; current = null }
    fun accepts(ticket: Ticket): Boolean = current == ticket
}

/** One translated line; [language] is the line's own, when the provider tells it line by line. */
data class TranslationEntry(val text: String?, val needsTranslation: Boolean = true, val language: String? = null)
data class TranslatorResponse(val lines: List<TranslationEntry?>, val detectedLanguage: String? = null)
data class TranslatorRequest(val lines: List<String>, val target: String, val source: String?, val context: String)

interface Translator {
    val provider: TranslationProvider
    suspend fun translate(request: TranslatorRequest): TranslatorResponse
}

open class TranslationFailure(message: String) : IllegalArgumentException(message)

/** The provider asked to be left alone until [untilMs] (wall clock); nothing is sent to it before then. */
class ProviderResting(val provider: TranslationProvider, val untilMs: Long, message: String) : TranslationFailure(message)

/** The provider is rate limited for now; [retryAfterMs] is how long it asked to wait, when it said. */
class ProviderBusy(val retryAfterMs: Long? = null, val detail: String? = null) : java.io.IOException("Translation is busy.")

/** Where the romanization on screen comes from. */
enum class RomanizationOrigin(val label: String) { Lyrics("Lyrics"), Genius("Genius"), OnDevice("On-device") }

/**
 * The sources of a song's romanization: the lyrics' own, else Genius's for [humanLines] of the
 * [romanizedLines] lines that have one and the phone's for the rest.
 */
fun romanizationOrigins(sourceRomanized: Boolean, humanLines: Int, romanizedLines: Int): List<RomanizationOrigin> = when {
    sourceRomanized -> listOf(RomanizationOrigin.Lyrics)
    humanLines <= 0 -> listOf(RomanizationOrigin.OnDevice)
    humanLines >= romanizedLines -> listOf(RomanizationOrigin.Genius)
    else -> listOf(RomanizationOrigin.Genius, RomanizationOrigin.OnDevice)
}

fun interface SongLanguageDetector { fun detect(originalLyrics: String): String? }

object DeepLKey {
    fun valid(input: String): Boolean = KEY.matches(input.trim())
    fun host(key: String): String = if (key.trim().endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
    private val KEY = Regex("[A-Za-z0-9_-]{16,}(?::fx)?")
}

object TranslationLanguages {
    /** Every language the pickers offer, keyed by its [languageCode], in name order. */
    val common = linkedMapOf(
        "af" to "Afrikaans", "sq" to "Albanian", "am" to "Amharic", "ar" to "Arabic",
        "hy" to "Armenian", "az" to "Azerbaijani", "eu" to "Basque", "bn" to "Bengali",
        "bs" to "Bosnian", "bg" to "Bulgarian", "my" to "Burmese", "ca" to "Catalan",
        "ceb" to "Cebuano", "zh" to "Chinese", "hr" to "Croatian", "cs" to "Czech",
        "da" to "Danish", "nl" to "Dutch", "en" to "English", "et" to "Estonian",
        "tl" to "Filipino", "fi" to "Finnish", "fr" to "French", "ka" to "Georgian",
        "de" to "German", "el" to "Greek", "gu" to "Gujarati", "ha" to "Hausa",
        "he" to "Hebrew", "hi" to "Hindi", "hu" to "Hungarian", "is" to "Icelandic",
        "ig" to "Igbo", "id" to "Indonesian", "ga" to "Irish", "it" to "Italian",
        "ja" to "Japanese", "jv" to "Javanese", "kn" to "Kannada", "kk" to "Kazakh",
        "km" to "Khmer", "ko" to "Korean", "lo" to "Lao", "lv" to "Latvian",
        "lt" to "Lithuanian", "mk" to "Macedonian", "ms" to "Malay", "ml" to "Malayalam",
        "mr" to "Marathi", "mn" to "Mongolian", "ne" to "Nepali", "no" to "Norwegian",
        "ps" to "Pashto", "fa" to "Persian", "pl" to "Polish", "pt" to "Portuguese",
        "pa" to "Punjabi", "ro" to "Romanian", "ru" to "Russian", "sr" to "Serbian",
        "si" to "Sinhala", "sk" to "Slovak", "sl" to "Slovenian", "so" to "Somali",
        "es" to "Spanish", "sw" to "Swahili", "sv" to "Swedish", "ta" to "Tamil",
        "te" to "Telugu", "th" to "Thai", "tr" to "Turkish", "uk" to "Ukrainian",
        "ur" to "Urdu", "uz" to "Uzbek", "vi" to "Vietnamese", "cy" to "Welsh",
        "xh" to "Xhosa", "yo" to "Yoruba", "zu" to "Zulu",
    )

    /** Listed languages DeepL can't translate into or from. Google handles every listed one. */
    private val DEEPL_MISSING = setOf("am", "km", "kn", "lo", "si", "so", "yo")

    fun name(code: String): String = common[languageCode(code)] ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code }

    /** Whether [provider] can translate into or from [code]. A language not listed here is left to the provider. */
    fun supports(provider: TranslationProvider, code: String): Boolean = when (provider) {
        TranslationProvider.DeepL -> languageCode(code) !in DEEPL_MISSING
        else -> true
    }

    /** The languages [provider] can translate into, by name, plus [current] so a saved choice still shows. */
    fun targets(provider: TranslationProvider, current: String? = null): List<Pair<String, String>> =
        withCurrent(common.filterKeys { supports(provider, it) }, listOfNotNull(current))

    /** Every listed language by name, for languages left untranslated or a song's language, plus [current] ones. */
    fun all(current: Collection<String> = emptyList()): List<Pair<String, String>> = withCurrent(common, current)

    private fun withCurrent(languages: Map<String, String>, current: Collection<String>): List<Pair<String, String>> =
        (languages + current.mapNotNull(::languageCode).filter { it !in languages }.associateWith(::name))
            .toList().sortedBy { it.second }

    /** [code] as Google's endpoints take it: they still use the old codes for Hebrew and Javanese. */
    fun googleCode(code: String): String = when (val language = languageCode(code)) {
        null -> code
        "he" -> "iw"
        "jv" -> "jw"
        else -> language
    }
}
