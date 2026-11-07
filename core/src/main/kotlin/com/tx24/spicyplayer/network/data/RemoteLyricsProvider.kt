package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.lyrics.LrcConverter
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import com.tx24.spicyplayer.network.data.blend.BlendDonors
import java.time.Instant

/** Metadata available when looking up lyrics for a local track. */
data class LyricsLookupRequest(
    val artist: String,
    val title: String,
    val album: String,
    val durationSeconds: Int,
    /** Manual/cache override; bypasses metadata matching when present. */
    val spotifyTrackId: String? = null,
) {
    /**
     * Whether a catalogue entry [seconds] long can be this track. A length of zero on either side is
     * unknown and rules nothing out: a queue entry fetched ahead, or a player that sends the length
     * after the title, has none, and taken as zero it would rule out every song.
     */
    fun lengthMatches(seconds: Int, toleranceSeconds: Int = 8): Boolean =
        seconds <= 0 || durationSeconds <= 0 || kotlin.math.abs(seconds - durationSeconds) <= toleranceSeconds
}

enum class LyricsCapability {
    WORD_SYNC,
    LINE_SYNC,
    PLAIN_TEXT,
    TRANSLATION,
    TRANSLITERATION,
    CONTRIBUTOR_CREDITS,
}

enum class SourceReleaseChannel {
    RECOMMENDED,
    EXTENDED,
    EXPERIMENTAL,
}

enum class TransportRequirement {
    DIRECT_OK,
    DIRECT_OR_BROKER,
    BROKER_ONLY,
}

/** Stable metadata used for ordering, settings, diagnostics, and future migrations. */
data class LyricsSourceDescriptor(
    val id: String,
    val displayName: String,
    val defaultPriority: Int,
    val capabilities: Set<LyricsCapability>,
    val upstreamFamily: String = id,
    val transportRequirement: TransportRequirement = TransportRequirement.DIRECT_OK,
    val releaseChannel: SourceReleaseChannel = SourceReleaseChannel.RECOMMENDED,
    /** Whether its switches in Settings → Sources start on. */
    val defaultEnabled: Boolean = true,
)

/**
 * Provider-neutral payload returned by an online lyrics service.
 *
 * TTML is first-class so word timing is never flattened to LRC. Attribution is
 * intentionally added only after the released Spicy API contract is mapped.
 */
data class RemoteLyricsPayload(
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val ttmlLyrics: String? = null,
    val sourceId: String? = null,
    val attribution: LyricsAttribution? = null,
    /**
     * For a blend: how much its timing donor's clock wanders against the base's line sync
     * (lower is steadier), where enough of the song paired to say. See [LyricsBlends.steadiestFirst].
     */
    val blendSteadiness: Double? = null,
) {
    fun isEmpty(): Boolean =
        plainLyrics.isNullOrBlank() && syncedLyrics.isNullOrBlank() && ttmlLyrics.isNullOrBlank()
}

data class LyricsContributor(
    val username: String,
    val profileUrl: String? = null,
    val avatarUrl: String? = null,
)

data class LyricsAttribution(
    val providerName: String,
    /** Original catalogue/community source when the provider syndicates several origins. */
    val originName: String? = null,
    val songwriters: List<String> = emptyList(),
    val maker: LyricsContributor? = null,
    val uploader: LyricsContributor? = null,
)

enum class ProviderFailureCategory {
    NETWORK,
    TIMEOUT,
    AUTHENTICATION,
    CLIENT_REQUEST,
    SERVER,
    MALFORMED_RESPONSE,
    UNKNOWN,
}

/** A provider miss is deliberately distinct from an unreachable provider. */
sealed interface ProviderResult {
    data class Hit(val payload: RemoteLyricsPayload) : ProviderResult
    data object Miss : ProviderResult
    data object NeedsMatch : ProviderResult
    /** Resting until [retryAt]; [reason] is the refusal that started it, when known. */
    data class CoolingDown(val retryAt: Instant, val reason: String? = null) : ProviderResult
    data class Queued(val retryAt: Instant? = null) : ProviderResult
    data class Unavailable(
        val category: ProviderFailureCategory,
        val message: String? = null,
        val retryable: Boolean = false,
    ) : ProviderResult
}

/** A single online source. Ordering is policy, not part of fetch behavior. */
interface RemoteLyricsProvider {
    val descriptor: LyricsSourceDescriptor

    suspend fun fetch(request: LyricsLookupRequest): ProviderResult

    /** Gets whatever every lookup needs first (a token, say) ready before the first song asks. */
    suspend fun warmUp() {}
}

/**
 * Snapshot of source preferences for one lookup. Sources are [LyricsOrigin]s; each provider has a
 * switch per source it serves ([SourcePair]).
 */
data class RemoteLyricsPolicy(
    /** Source IDs, first asked first; a source missing here goes where the default order puts it. */
    val sourceOrder: List<String> = emptyList(),
    /** Sources switched off as a whole. */
    val disabledSourceIds: Set<String> = emptySet(),
    /**
     * Provider switches by [SourcePair.key]. A pair not here takes its provider's
     * [LyricsSourceDescriptor.defaultEnabled]; a blend's is off.
     */
    val pairs: Map<String, Boolean> = emptyMap(),
    /** Sync types switched off per source ID. */
    val syncTypesOff: Map<String, Set<RemoteLyricsQuality>> = emptyMap(),
)

enum class RemoteLyricsQuality(val rank: Int) {
    NONE(0),
    PLAIN(1),
    LINE_SYNCED(2),
    WORD_SYNCED(3),
}

/**
 * Measures the best usable representation in a provider response.
 *
 * TTML is parsed here instead of being trusted by file extension. A malformed or
 * empty TTML body can therefore fall back to a valid LRC/plain representation,
 * and cannot stop the provider chain as a fake word-synced hit.
 */
fun RemoteLyricsPayload.measuredQuality(): RemoteLyricsQuality {
    val ttmlQuality = parsedTtmlQuality()
    return when {
        ttmlQuality != RemoteLyricsQuality.NONE -> ttmlQuality
        !syncedLyrics.isNullOrBlank() -> RemoteLyricsQuality.LINE_SYNCED
        !plainLyrics.isNullOrBlank() -> RemoteLyricsQuality.PLAIN
        else -> RemoteLyricsQuality.NONE
    }
}

/**
 * True when these lyrics are only a note saying the song has no words ("纯音乐，请欣赏"). Taken as
 * lyrics, a note stamped across the song outranks every real answer further down the list, so it
 * counts as a miss. Reads the same representation [measuredQuality] ranks.
 */
fun RemoteLyricsPayload.isNoWordsNote(): Boolean {
    val lines = when {
        !ttmlLyrics.isNullOrBlank() -> TtmlLyricsParser.parse(ttmlLyrics.byteInputStream()).lines
            .map { line -> line.words.joinToString("") { it.text } }
            .ifEmpty { return false }
        !syncedLyrics.isNullOrBlank() -> syncedLyrics.lines().map { it.replace(LRC_STAMP, "") }
        else -> plainLyrics.orEmpty().lines()
    }
    // Nothing but stamps and "♪" (or an empty record) says as much as the note does.
    if (lines.none { line -> line.any(Char::isLetterOrDigit) }) return true
    return BlendDonors.isNoWordsNote(lines)
}

/**
 * These lyrics with synced text that has no real timing read as plain text. Some sources fill the
 * synced field with untimed text (QQ Music) or stamp every line with one time, e.g. the song's
 * end (Kuwo): ranked as line synced, they beat real plain lyrics and then show nothing, or every
 * line at once. Lines with words need two distinct times between them to count as synced (or one,
 * for a single line).
 */
fun RemoteLyricsPayload.withoutFakeTiming(): RemoteLyricsPayload {
    val synced = syncedLyrics?.takeIf(String::isNotBlank) ?: return this
    val lines = synced.lines()
    val textLines = lines.filter { line -> line.replace(LRC_STAMP, "").any(Char::isLetterOrDigit) }
    val times = textLines.flatMap { line -> LRC_TIME.findAll(line).map { it.value } }.toSet()
    if (times.size >= 2 || (times.size == 1 && textLines.size == 1)) return this
    // Tag lines ("[ar:...]") go; a stamp alone is a stanza break.
    val text = lines.filterNot { line -> LRC_TAG.matches(line.trim()) }
        .joinToString("\n") { it.replace(LRC_STAMP, "").trim() }
        .trim()
    return copy(syncedLyrics = null, plainLyrics = plainLyrics?.takeIf(String::isNotBlank) ?: text.ifBlank { null })
}

/**
 * These lyrics with enhanced LRC (`<mm:ss.xx>` stamps before words) in the synced field as
 * word-timed TTML too, so its word timing ranks and renders as such instead of the stamps showing
 * as text. Lyrics that already have TTML keep it.
 */
fun RemoteLyricsPayload.withWordTimingAsTtml(): RemoteLyricsPayload {
    if (!ttmlLyrics.isNullOrBlank()) return this
    val synced = syncedLyrics?.takeIf { LRC_WORD_TIME.containsMatchIn(it) } ?: return this
    return LrcConverter.toTtml(synced)?.let { copy(ttmlLyrics = it) } ?: this
}

private val LRC_STAMP = Regex("""\[[^\]]*]""")
private val LRC_TAG = Regex("""\[[a-zA-Z]+:[^\]]*]""")
// The stamps the lyrics adapter reads: what passes here must render there.
private val LRC_TIME = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,4})?]""")
private val LRC_WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,4})?>""")

private fun RemoteLyricsPayload.parsedTtmlQuality(): RemoteLyricsQuality {
    val ttml = ttmlLyrics?.takeIf(String::isNotBlank) ?: return RemoteLyricsQuality.NONE
    val document = TtmlLyricsParser.parse(ttml.byteInputStream())
    val hasText = document.lines.any { line ->
        // A line with only its romanization still shows it.
        line.words.any { word -> word.text.isNotBlank() || !word.romanizedText.isNullOrBlank() }
    }
    if (!hasText) return RemoteLyricsQuality.NONE

    return when (document.type) {
        LyricsType.Syllable -> RemoteLyricsQuality.WORD_SYNCED
        LyricsType.Line -> RemoteLyricsQuality.LINE_SYNCED
        LyricsType.Static -> RemoteLyricsQuality.PLAIN
    }
}
