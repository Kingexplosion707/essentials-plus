package com.tx24.spicyplayer.network.data

/**
 * The changes Settings → Sources makes to a [RemoteLyricsPolicy], and the move from the old
 * per-provider preferences. Every function takes the providers there are and returns the policy
 * with each source in its place and each switch spelled out, ready to store.
 */
object LyricsSourceSettings {
    /** [policy] with every source in the order and every switch and sync type spelled out. */
    fun normalized(policy: RemoteLyricsPolicy, providers: Collection<LyricsSourceDescriptor>): RemoteLyricsPolicy {
        val ranking = SourceRanking(providers, policy)
        val ids = ranking.origins.map { it.id }.toSet()
        return RemoteLyricsPolicy(
            sourceOrder = ranking.origins.map { it.id },
            disabledSourceIds = policy.disabledSourceIds.filterTo(mutableSetOf(), ids::contains),
            pairs = ranking.origins.flatMap { origin ->
                origin.providerIds.map { provider -> SourcePair(origin.id, provider).key to ranking.pairOn(origin.id, provider) }
            }.toMap(),
            syncTypesOff = ranking.origins.associate { origin ->
                origin.id to policy.syncTypesOff[origin.id].orEmpty().filterTo(mutableSetOf()) { it in origin.qualities }
            }.filterValues { it.isNotEmpty() },
        )
    }

    /** The sources in [order] (by id, all of them); one it leaves out slots in by the default order. */
    fun reorder(policy: RemoteLyricsPolicy, providers: Collection<LyricsSourceDescriptor>, order: List<String>): RemoteLyricsPolicy =
        normalized(policy.copy(sourceOrder = order), providers)

    /**
     * Whether source [originId] reads as on: not switched off, with a provider (not just a blend)
     * switched on in it.
     */
    fun isOn(ranking: SourceRanking, originId: String): Boolean {
        val origin = ranking.origins.firstOrNull { it.id == originId } ?: return false
        return ranking.sourceOn(originId) && origin.providerIds.any { LyricsBlends.byId(it) == null && ranking.pairOn(originId, it) }
    }

    /**
     * The provider to switch on along with source [originId], when switching it on would leave
     * it with none: its first one. Null when one is on already.
     */
    fun providerToSwitchOn(ranking: SourceRanking, originId: String): String? {
        val origin = ranking.origins.firstOrNull { it.id == originId } ?: return null
        val providers = origin.providerIds.filter { LyricsBlends.byId(it) == null }
        return providers.firstOrNull().takeIf { providers.none { ranking.pairOn(originId, it) } }
    }

    fun setSourceEnabled(policy: RemoteLyricsPolicy, providers: Collection<LyricsSourceDescriptor>, originId: String, enabled: Boolean): RemoteLyricsPolicy {
        val switched = policy.copy(
            disabledSourceIds = if (enabled) policy.disabledSourceIds - originId else policy.disabledSourceIds + originId,
        )
        val first = if (enabled) providerToSwitchOn(SourceRanking(providers, switched), originId) else null
        return normalized(first?.let { switched.copy(pairs = switched.pairs + (SourcePair(originId, it).key to true)) } ?: switched, providers)
    }

    /** Switches [pair] on or off; switching one on switches its source on too. */
    fun setPairEnabled(policy: RemoteLyricsPolicy, providers: Collection<LyricsSourceDescriptor>, pair: SourcePair, enabled: Boolean): RemoteLyricsPolicy =
        normalized(
            policy.copy(
                pairs = policy.pairs + (pair.key to enabled),
                disabledSourceIds = if (enabled) policy.disabledSourceIds - pair.originId else policy.disabledSourceIds,
            ),
            providers,
        )

    /** Keeps only [allowed] of source [originId]'s sync types. */
    fun setSyncTypes(policy: RemoteLyricsPolicy, providers: Collection<LyricsSourceDescriptor>, originId: String, allowed: Set<RemoteLyricsQuality>): RemoteLyricsPolicy {
        val origin = SourceRanking(providers, policy).origins.firstOrNull { it.id == originId } ?: return normalized(policy, providers)
        return normalized(policy.copy(syncTypesOff = policy.syncTypesOff + (originId to (origin.qualities.toSet() - allowed))), providers)
    }

    /**
     * Sources and switches from the old per-provider preferences ([order], [disabled] and the
     * switched-on [blends]):
     * - each source takes the earliest old place its lyrics ranked at ([LEGACY_PLACES]: for Apple
     *   Music and Spotify the old rank-only slots, whose lyrics came by relay); one with none of
     *   them in the old order goes where the default order puts it;
     * - a source whose old slot was switched off (ranked after everything) goes last, still on;
     * - each provider's switch in each source is the provider's old switch, and a blend's is on
     *   when it was switched on.
     */
    fun migrate(
        order: List<String>?,
        disabled: Set<String>,
        blends: Set<String>,
        providers: Collection<LyricsSourceDescriptor>,
    ): RemoteLyricsPolicy {
        val old = LyricsSourcePreferenceNormalizer.normalize(order, disabled, providers + RELAY_SLOTS)
        val position = old.order.withIndex().associate { (i, id) -> id to i }
        val origins = LyricsOrigins.forProviders(providers)
        val placed = origins.mapNotNull { origin ->
            (LEGACY_PLACES[origin.id] ?: origin.providerIds).mapNotNull(position::get).minOrNull()?.let { origin.id to it }
        }.sortedBy { it.second }.map { it.first }
        val sourceOrder = LyricsSourcePreferenceNormalizer.order(placed, origins.map { it.id })
            .sortedBy { if (it in RELAY_SLOT_IDS && it in old.disabledSourceIds) 1 else 0 }
        val pairs = origins.flatMap { origin ->
            origin.providerIds.map { provider ->
                val on = if (LyricsBlends.byId(provider) != null) provider in blends else provider !in old.disabledSourceIds
                SourcePair(origin.id, provider).key to on
            }
        }.toMap()
        return normalized(RemoteLyricsPolicy(sourceOrder, emptySet(), pairs), providers)
    }

    /** The old rank-only places for lyrics relayed from Apple Music and Spotify. */
    private val RELAY_SLOTS = listOf(
        LyricsSourceDescriptor(LyricsOrigins.APPLE_MUSIC, "Apple Music", 40, emptySet()),
        LyricsSourceDescriptor(LyricsOrigins.SPOTIFY, "Spotify", 110, emptySet()),
    )
    private val RELAY_SLOT_IDS = RELAY_SLOTS.map { it.id }.toSet()

    /**
     * The old order entries whose place a source's lyrics ranked at. Relays (Spicy Lyrics, RMM
     * Revival, lrc.red, LRCMux) placed their answers by origin, so their own places don't count for
     * the sources they relay. Sources not listed take their providers' places.
     */
    private val LEGACY_PLACES = mapOf(
        LyricsOrigins.SPICY_COMMUNITY to listOf(RemoteLyricsSource.SPICY_ID),
        LyricsOrigins.APPLE_MUSIC to listOf(LyricsOrigins.APPLE_MUSIC, "bini_lyrics"),
        LyricsOrigins.UNISON to listOf(LyricsOrigins.UNISON),
        LyricsOrigins.SPOTIFY to listOf(LyricsOrigins.SPOTIFY),
        LyricsOrigins.KUGOU to listOf("kugou"),
        LyricsOrigins.NETEASE to listOf("netease"),
        LyricsOrigins.QQ_MUSIC to listOf("qq_music"),
        LyricsOrigins.KUWO to listOf("kuwo"),
        LyricsOrigins.LRCLIB to listOf(LyricsOrigins.LRCLIB),
        LyricsOrigins.GENIUS to listOf(LyricsOrigins.GENIUS),
        LyricsOrigins.YOUTUBE to listOf("youtube_transcript"),
    )
}
