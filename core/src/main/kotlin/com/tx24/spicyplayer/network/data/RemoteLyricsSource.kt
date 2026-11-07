package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.network.data.blend.LyricsFurniture
import com.tx24.spicyplayer.network.model.NetworkErrorException
import com.tx24.spicyplayer.network.model.NotFoundException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

enum class ProviderAttemptOutcome {
    HIT,
    MISS,
    NEEDS_MATCH,
    DISABLED,
    COOLING_DOWN,
    QUEUED,
    UNAVAILABLE,
    MALFORMED_HIT,
    /** Still being asked. */
    PENDING,
    /** Never asked or cancelled: a source ranked above it already answered word-synced. */
    SKIPPED,
    /**
     * Answered, but for a source it's switched off for, or in a sync type that source has off;
     * the attempt's message says which.
     */
    FILTERED,
}

data class ProviderAttempt(
    val sourceId: String,
    val outcome: ProviderAttemptOutcome,
    val quality: RemoteLyricsQuality = RemoteLyricsQuality.NONE,
    val retryAt: Instant? = null,
    val failureCategory: ProviderFailureCategory? = null,
    val message: String? = null,
)

data class RemoteLyricsSelection(
    val source: LyricsSourceDescriptor,
    val payload: RemoteLyricsPayload,
    val quality: RemoteLyricsQuality,
)

sealed interface RemoteLyricsResolution {
    val attempts: List<ProviderAttempt>

    data class Found(
        val selection: RemoteLyricsSelection,
        override val attempts: List<ProviderAttempt>,
    ) : RemoteLyricsResolution

    data class NotFound(
        override val attempts: List<ProviderAttempt>,
    ) : RemoteLyricsResolution

    data class Unavailable(
        override val attempts: List<ProviderAttempt>,
        val earliestRetryAt: Instant? = null,
    ) : RemoteLyricsResolution
}

/**
 * Applies source policy and selects the best usable payload: better timing wins, and between
 * equal timing the user's order wins, never whichever server answered first. The order is of
 * sources ([LyricsOrigin]): an answer ranks at the place of the source it came from, whichever
 * provider brought it ([SourceRanking]). TTML is parsed
 * before it can win, so malformed or empty XML never terminates the chain.
 *
 * Fetching walks the ranking: the top-ranked source leads alone, since one request
 * usually settles the song; the rest fan out in parallel if it misses or is slow. The walk stops
 * once a word-synced answer has nothing ranked above it still out.
 */
@Singleton
class RemoteLyricsSource @Inject constructor(
    providers: Set<@JvmSuppressWildcards RemoteLyricsProvider>,
    private val cooldowns: ProviderCooldownTracker,
    private val diagnostics: LyricsDiagnostics = LyricsDiagnostics(),
    /**
     * The song's title and artist as its home storefront writes them, where the player's are
     * translated or romanised; the Chinese catalogues are asked again under those after a miss.
     */
    private val nativeNames: (suspend (LyricsLookupRequest) -> Pair<String, String>?)? = null,
) {
    private val providers = providers.toList()

    /**
     * [known] holds results from earlier lookups of the same request and receives new ones, so a
     * caller that keeps it (per track) never asks a source twice, e.g. after a policy change.
     * [onUpdate] gets the best-so-far resolution each time a source lands.
     */
    suspend fun resolveLyrics(
        request: LyricsLookupRequest,
        policy: RemoteLyricsPolicy = RemoteLyricsPolicy(),
        now: Instant = Instant.now(),
        known: MutableMap<String, ProviderResult> = mutableMapOf(),
        onUpdate: suspend (RemoteLyricsResolution) -> Unit = {},
    ): RemoteLyricsResolution = coroutineScope {
        val ranking = SourceRanking(providers.map { it.descriptor }, policy)
        // Asked once each, however many sources a provider serves; the best-placed leads.
        val asked = providers.filter { ranking.asked(it.descriptor.id) }
            .sortedBy { ranking.bestPlace(it.descriptor.id) ?: SourceRanking.AFTER_ALL }
        val askedIds = asked.map { it.descriptor.id }.toSet()
        val cooling = asked.mapNotNull { provider ->
            val id = provider.descriptor.id
            if (id in known) null else cooldowns.retryAt(id, now)?.let { id to it }
        }.toMap()
        val toAsk = asked.filter { it.descriptor.id !in known && it.descriptor.id !in cooling }
        val qualities = mutableMapOf<String, RemoteLyricsQuality>()
        val pending = mutableSetOf<String>()
        val landed = Channel<Pair<String, Result<ProviderResult>>>(Channel.UNLIMITED)
        val jobs = mutableMapOf<String, Job>()

        // Blends rank among the providers but are built here, from what the providers answered.
        // Their results stay out of [known]: they depend on the order, which the caller may change.
        val blends = ranking.liveBlends
        val blendIds = blends.map { it.id }.toSet()
        val descriptors = providers.associate { it.descriptor.id to it.descriptor } + blends.associate { it.id to it.descriptor }
        // Every provider and live blend, listed by where it could first rank.
        val entries = descriptors.keys.sortedBy(ranking::homePlace)
        val blendResults = mutableMapOf<String, ProviderResult>()
        val waiting = blendIds.toMutableSet()

        fun ask(provider: RemoteLyricsProvider) {
            val id = provider.descriptor.id
            pending += id
            jobs[id] = launch {
                // Carried as a Result so a provider's own CancellationException reaches the caller.
                val result = try {
                    Result.success(fetch(provider, request))
                } catch (cancelled: CancellationException) {
                    Result.failure(cancelled)
                }
                landed.trySend(id to result)
            }
        }
        fun resultOf(id: String) = known[id] ?: blendResults[id]
        fun quality(id: String) = (resultOf(id) as? ProviderResult.Hit)?.let { hit ->
            qualities.getOrPut(id) { hit.payload.measuredQuality() }
        } ?: RemoteLyricsQuality.NONE
        // The quality of the answer where it counts, else none.
        fun counted(id: String): RemoteLyricsQuality {
            val hit = resultOf(id) as? ProviderResult.Hit ?: return RemoteLyricsQuality.NONE
            val quality = quality(id)
            return if (ranking.filtered(id, hit.payload, quality) == null) quality else RemoteLyricsQuality.NONE
        }
        fun answered(id: String) = id in known || id in cooling
        // An answer ranks at its source's place. One not in yet (or no answer) ranks at the best
        // place it could still take: a relay still out may yet bring lyrics from a source above.
        fun place(id: String): Int {
            if (id !in blendIds && id !in askedIds) return ranking.homePlace(id)
            val hit = resultOf(id) as? ProviderResult.Hit
            if (hit != null && quality(id) != RemoteLyricsQuality.NONE) return ranking.answerPlace(id, hit.payload)
            // RMM Revival relays Spicy Lyrics' API by Apple Music ID: once Spicy Lyrics has really
            // answered, the relay can't bring a community sync it didn't.
            val spicyAnswered = known[SPICY_ID].let { it is ProviderResult.Hit || it == ProviderResult.Miss }
            val except = if (id == RMM_ID && spicyAnswered) setOf(LyricsOrigins.SPICY_COMMUNITY) else emptySet()
            return ranking.bestPlace(id, except) ?: ranking.bestPlace(id) ?: ranking.homePlace(id)
        }
        fun ranked() = entries.sortedBy(::place)
        fun startBlends() {
            for (blend in blends) {
                if (blend.id !in waiting) continue
                val order = ranked()
                val above = order.take(order.indexOf(blend.id)).filter { it in askedIds }
                // Word timing from a source ranked above is what the blend was for (`_outdone`).
                if (above.any { counted(it) == RemoteLyricsQuality.WORD_SYNCED }) { waiting -= blend.id; continue }
                if (!above.all(::answered) || !blend.donorIds.all(::answered)) continue
                val usable = above.filter { counted(it) != RemoteLyricsQuality.NONE }
                val fallback = LRCLIB_ID.takeIf { it in askedIds && it !in above }
                if (usable.isEmpty() && fallback != null && !answered(fallback)) continue
                waiting -= blend.id
                pending += blend.id
                // Read here, on the walk's own thread; the build runs on another.
                val hits = { id: String -> (known[id] as? ProviderResult.Hit)?.payload }
                val bases = usable.map { descriptors.getValue(it) to hits(it)!! }
                val fallbackBase = fallback?.takeIf { usable.isEmpty() && counted(it) != RemoteLyricsQuality.NONE }
                    ?.let { id -> hits(id)?.let { descriptors.getValue(id) to it } }
                val donors = blend.donorIds.associateWith(hits)
                // lrc.red's model sync ranks below the blends, so it is never among the bases above.
                val modelBase = LRC_RED_ID.takeIf { it in askedIds && it !in above }?.let(hits)
                    ?.takeIf { it.attribution?.originName == LyricsOrigins.LRC_RED_AI_NAME }
                    ?.let { descriptors.getValue(LRC_RED_ID) to it }
                val names = descriptors.mapValues { it.value.displayName }
                jobs[blend.id] = launch(Dispatchers.Default) {
                    val result = try {
                        LyricsBlends.build(blend, request, bases, fallbackBase, donors, modelBase) { id -> names[id] ?: id }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        ProviderResult.Unavailable(ProviderFailureCategory.UNKNOWN, "Blend failed: ${error.message}")
                    }
                    landed.trySend(blend.id to Result.success(result))
                }
            }
        }
        fun current() = select(ranked().map(descriptors::getValue), ranking, askedIds, known + blendResults, pending + waiting, cooling, qualities)

        var resolution = current()
        if (known.isNotEmpty()) onUpdate(resolution)
        toAsk.firstOrNull()?.let(::ask)
        var fannedOut = toAsk.size <= 1
        startBlends()
        // Not asked yet counts as still out: a relayed answer can land below sources the lead skipped.
        fun outstanding() = pending + waiting + toAsk.map { it.descriptor.id }.filter { it !in known }
        while (!settled(resolution, ranked(), outstanding())) {
            if (!fannedOut && pending.isEmpty()) {
                // The lead answered without settling the song: ask everyone else.
                toAsk.drop(1).forEach(::ask)
                fannedOut = true
                startBlends()
            }
            if (pending.isEmpty()) break
            val next = if (fannedOut) landed.receive() else landed.receiveWithin(LEAD_HOLD_MS)
            if (next == null) {
                // ponytail: fixed hold for a slow lead; adapt per source if its latency varies a lot
                toAsk.drop(1).forEach(::ask)
                fannedOut = true
                continue
            }
            val (id, outcome) = next
            val result = outcome.getOrThrow()
            pending -= id
            if (id in blendIds) {
                blendResults[id] = result
            } else {
                if (result is ProviderResult.CoolingDown) cooldowns.record(id, result.retryAt, result.reason)
                known[id] = result
            }
            startBlends()
            resolution = current()
            onUpdate(resolution)
        }
        pending.toList().forEach { id -> jobs[id]?.cancel(); pending -= id }
        waiting.clear()
        current()
    }

    /** Whether nothing still out could replace the current pick: word timing is the ceiling. */
    private fun settled(
        resolution: RemoteLyricsResolution,
        ranked: List<String>,
        pending: Set<String>,
    ): Boolean {
        val best = (resolution as? RemoteLyricsResolution.Found)?.selection ?: return false
        if (best.quality != RemoteLyricsQuality.WORD_SYNCED) return false
        val rank = ranked.indexOf(best.source.id)
        return ranked.take(rank).none { it in pending }
    }

    private fun select(
        ranked: List<LyricsSourceDescriptor>,
        ranking: SourceRanking,
        asked: Set<String>,
        known: Map<String, ProviderResult>,
        pending: Set<String>,
        cooling: Map<String, Instant>,
        qualities: MutableMap<String, RemoteLyricsQuality>,
    ): RemoteLyricsResolution {
        val attempts = mutableListOf<ProviderAttempt>()
        var best: RemoteLyricsSelection? = null
        val counted = mutableListOf<RemoteLyricsSelection>()
        var hadUnavailableProvider = false
        var earliestRetryAt: Instant? = null

        for (source in ranked) {
            // A blend is in the ranking only while it is live.
            if (source.upstreamFamily != BLEND_FAMILY && source.id !in asked) {
                attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.DISABLED)
                continue
            }
            val result = known[source.id]
            if (result == null) {
                val retryAt = cooling[source.id]
                attempts += when {
                    source.id in pending -> ProviderAttempt(source.id, ProviderAttemptOutcome.PENDING)
                    retryAt != null -> {
                        hadUnavailableProvider = true
                        earliestRetryAt = earliest(earliestRetryAt, retryAt)
                        ProviderAttempt(source.id, ProviderAttemptOutcome.COOLING_DOWN, retryAt = retryAt, message = cooldowns.reason(source.id))
                    }
                    else -> ProviderAttempt(source.id, ProviderAttemptOutcome.SKIPPED)
                }
                continue
            }

            when (result) {
                is ProviderResult.Hit -> {
                    val quality = qualities.getOrPut(source.id) { result.payload.measuredQuality() }
                    if (quality == RemoteLyricsQuality.NONE) {
                        attempts += ProviderAttempt(
                            source.id,
                            ProviderAttemptOutcome.MALFORMED_HIT,
                            failureCategory = ProviderFailureCategory.MALFORMED_RESPONSE,
                        )
                        hadUnavailableProvider = true
                        continue
                    }
                    val filtered = ranking.filtered(source.id, result.payload, quality)
                    if (filtered != null) {
                        attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.FILTERED, quality, message = filtered)
                        continue
                    }
                    attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.HIT, quality)
                    val selection = RemoteLyricsSelection(source, result.payload, quality)
                    counted += selection
                    if (best == null || quality.rank > best.quality.rank) best = selection
                }

                ProviderResult.Miss ->
                    attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.MISS)

                ProviderResult.NeedsMatch ->
                    attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.NEEDS_MATCH)

                is ProviderResult.CoolingDown -> {
                    attempts += ProviderAttempt(
                        source.id,
                        ProviderAttemptOutcome.COOLING_DOWN,
                        retryAt = result.retryAt,
                        message = result.reason,
                    )
                    earliestRetryAt = earliest(earliestRetryAt, result.retryAt)
                    hadUnavailableProvider = true
                }

                is ProviderResult.Queued -> {
                    attempts += ProviderAttempt(
                        source.id,
                        ProviderAttemptOutcome.QUEUED,
                        retryAt = result.retryAt,
                    )
                    result.retryAt?.let { earliestRetryAt = earliest(earliestRetryAt, it) }
                    hadUnavailableProvider = true
                }

                is ProviderResult.Unavailable -> {
                    attempts += ProviderAttempt(
                        source.id,
                        ProviderAttemptOutcome.UNAVAILABLE,
                        failureCategory = result.category,
                        message = result.message,
                    )
                    hadUnavailableProvider = true
                }
            }
        }

        best?.let { top ->
            // Among the answers tied at the best quality, a much steadier blend may go first.
            val tied = counted.filter { it.quality == top.quality }
            val pick = LyricsBlends.steadiestFirst(tied, { it.source.upstreamFamily == BLEND_FAMILY }, { it.payload.blendSteadiness }).first()
            return RemoteLyricsResolution.Found(pick, attempts)
        }
        return if (hadUnavailableProvider || pending.isNotEmpty()) {
            RemoteLyricsResolution.Unavailable(attempts, earliestRetryAt)
        } else {
            RemoteLyricsResolution.NotFound(attempts)
        }
    }

    private suspend fun fetch(provider: RemoteLyricsProvider, request: LyricsLookupRequest): ProviderResult = try {
        // A note saying the song has no words ("纯音乐，请欣赏") is no answer: other sources may have them.
        // Synced text without real timing is plain text, and ranks as such.
        val furnished = provider.descriptor.id in FURNISHED
        var asked = request
        var answer = provider.fetch(request)
        // The Chinese catalogues file songs under their native names ("新時代", not "New Genesis").
        if (furnished && answer == ProviderResult.Miss) {
            nativeNames?.invoke(request)?.let { (title, artist) ->
                asked = request.copy(title = title, artist = artist)
                answer = provider.fetch(asked)
            }
        }
        answer.let { result ->
            val hit = (result as? ProviderResult.Hit)?.let { it.copy(payload = it.payload.withoutFakeTiming().withWordTimingAsTtml()) }
                // The Chinese catalogues write lines nobody sings into their lyrics: credits, headers, translations.
                ?.let { if (furnished) it.copy(payload = LyricsFurniture.clean(it.payload, asked.title, asked.artist)) else it }
                ?: return@let result
            if (hit.payload.isEmpty() || hit.payload.isNoWordsNote()) ProviderResult.Miss else hit
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: NotFoundException) {
        ProviderResult.Miss
    } catch (error: NetworkErrorException) {
        ProviderResult.Unavailable(
            category = ProviderFailureCategory.NETWORK,
            message = error.message,
            retryable = true,
        )
    } catch (error: Exception) {
        ProviderResult.Unavailable(
            category = ProviderFailureCategory.UNKNOWN,
            message = error.message,
        )
    }

    /** Compatibility boundary for the existing repository while it migrates to typed outcomes. */
    suspend fun getLyrics(
        request: LyricsLookupRequest,
        policy: RemoteLyricsPolicy = RemoteLyricsPolicy(),
    ): RemoteLyricsPayload {
        val started = System.nanoTime()
        val resolution = resolveLyrics(request, policy)
        diagnostics.record(LyricsLookupDiagnostic(
            title = request.title,
            recordedAt = Instant.now(),
            durationMs = (System.nanoTime() - started) / 1_000_000L,
            outcome = when (resolution) {
                is RemoteLyricsResolution.Found -> "Found: ${resolution.selection.source.id}"
                is RemoteLyricsResolution.NotFound -> "Not found"
                is RemoteLyricsResolution.Unavailable -> "Temporarily unavailable"
            },
            attempts = resolution.attempts,
        ))
        return when (resolution) {
        is RemoteLyricsResolution.Found -> resolution.selection.payload.copy(
            sourceId = resolution.selection.source.id,
            attribution = resolution.selection.payload.attribution ?: LyricsAttribution(
                providerName = resolution.selection.source.displayName,
            ),
        )
        is RemoteLyricsResolution.NotFound -> throw LyricsNotFoundException(resolution.attempts)
        is RemoteLyricsResolution.Unavailable -> throw LyricsSourcesUnavailableException(resolution.attempts, resolution.earliestRetryAt)
        }
    }

    class LyricsNotFoundException(val attempts: List<ProviderAttempt>) : NotFoundException("Lyrics not found")
    class LyricsSourcesUnavailableException(
        val attempts: List<ProviderAttempt>,
        val retryAt: Instant?,
    ) : NetworkErrorException("Lyrics sources are temporarily unavailable")

    companion object {
        const val SPICY_ID = "spicy_lyrics"
        /** Relays Spicy Lyrics' API by Apple Music ID. */
        const val RMM_ID = "rmm_revival"
        const val LRC_RED_ID = "lrc_red"
        const val LRCMUX_ID = "lrcmux"
        /** Whose answers [LyricsFurniture] tidies. */
        val FURNISHED = setOf("qq_music", "kugou", "kuwo", "netease")
        /** Origin names (SpicyLyricsProvider.spicyOriginName) for Spicy Lyrics' own syncs. */
        val SPICY_OWN_ORIGINS = setOf("Spicy Lyrics", "Spicy Lyrics Community")

        /** How long the lead source is asked alone before everyone else is asked too. */
        const val LEAD_HOLD_MS = 1_000L
        /** Where a blend takes its lines when nothing ranked above it has any. */
        const val LRCLIB_ID = "lrclib"
        const val BLEND_FAMILY = "blend"
    }

    private fun earliest(current: Instant?, candidate: Instant): Instant = when {
        current == null -> candidate
        candidate.isBefore(current) -> candidate
        else -> current
    }
}

/**
 * The next element, or null once [ms] pass. Unlike `withTimeoutOrNull { receive() }`, an element
 * arriving as the time runs out is never taken and then dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun <T> ReceiveChannel<T>.receiveWithin(ms: Long): T? = select {
    onReceive { it }
    onTimeout(ms) { null }
}
