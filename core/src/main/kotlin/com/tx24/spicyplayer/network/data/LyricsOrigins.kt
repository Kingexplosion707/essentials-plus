package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.network.data.providers.SpicyUnisonLyricsProvider

/**
 * A source in Settings → Sources: where lyrics come from (Apple Music, LRCLIB, …), whichever
 * provider brings them. The user orders sources; an answer ranks at its source's place, and
 * within it by the provider's place in [providerIds].
 */
data class LyricsOrigin(
    val id: String,
    val displayName: String,
    /** The providers (and blends) that can bring its lyrics, in a fixed order. */
    val providerIds: List<String>,
    /** The sync types it can give, best first: the ones the user may switch off for it. */
    val qualities: List<RemoteLyricsQuality>,
    /**
     * The attribution origin names ([LyricsAttribution.originName]) that place an answer here,
     * for a provider that serves several sources.
     */
    val originNames: Set<String> = emptySet(),
    /** How lyrics from it are credited, whichever provider brought them. */
    val credit: String = displayName,
)

/** One provider's switch within one source. */
data class SourcePair(val originId: String, val providerId: String) {
    val key: String get() = "$originId/$providerId"

    companion object {
        fun parse(key: String): SourcePair? =
            key.split('/', limit = 2).takeIf { it.size == 2 && it.none(String::isBlank) }?.let { SourcePair(it[0], it[1]) }
    }
}

/** The built-in sources, which provider serves which, and how an answer finds its source. */
object LyricsOrigins {
    const val SPICY_COMMUNITY = "spicy_community"
    const val AMLL = "amll_ttml_db"
    const val APPLE_MUSIC = "apple_music"
    const val LRC_RED_AI = "lrc_red_ai"
    const val UNISON = "unison"
    const val SPOTIFY = "spotify"
    const val KUGOU = "kugou"
    const val NETEASE = "netease"
    const val QQ_MUSIC = "qq_music"
    const val KUWO = "kuwo"
    const val LRCLIB = "lrclib"
    const val GENIUS = "genius"
    const val YOUTUBE = "youtube"

    /** The origin name lrc.red gives the syncs its in-house model makes. */
    const val LRC_RED_AI_NAME = "lrc.red AI"

    // Any source may give unsynced lyrics now and then, so each can have them switched off.
    private val ALL_TYPES = listOf(RemoteLyricsQuality.WORD_SYNCED, RemoteLyricsQuality.LINE_SYNCED, RemoteLyricsQuality.PLAIN)
    private val LINE_PLAIN = listOf(RemoteLyricsQuality.LINE_SYNCED, RemoteLyricsQuality.PLAIN)

    private const val SPICY = RemoteLyricsSource.SPICY_ID
    private const val RMM = "rmm_revival"
    private const val LRC_RED = RemoteLyricsSource.LRC_RED_ID
    private const val LRCMUX = RemoteLyricsSource.LRCMUX_ID

    /**
     * The blends whose word timing comes from [providerId]: they stand in its source, ahead of it,
     * the ones that fill gaps from another source first.
     */
    private fun blendsTimedBy(providerId: String) = LyricsBlends.ALL.filter { it.timingSourceId == providerId }
        .sortedBy { blend -> blend.spareSourceId?.let(FILLERS::indexOf) ?: FILLERS.size }
        .map { it.id }

    private val FILLERS = listOf("kugou", "netease", "qq_music", "kuwo")

    /** Every built-in source, in the order a fresh install has them. */
    val BUILT_IN: List<LyricsOrigin> = listOf(
        LyricsOrigin(SPICY_COMMUNITY, "Spicy Lyrics community", listOf(SPICY, RMM), ALL_TYPES,
            RemoteLyricsSource.SPICY_OWN_ORIGINS, credit = "Spicy Lyrics"),
        LyricsOrigin(AMLL, "AMLL TTML DB", listOf(AMLL), ALL_TYPES),
        LyricsOrigin(APPLE_MUSIC, "Apple Music", listOf(SPICY, RMM, LRC_RED), ALL_TYPES, setOf("Apple Music")),
        // Unison's own API and Spicy Lyrics' store list of it; RMM Revival and Spicy Lyrics' main API don't serve it.
        LyricsOrigin(UNISON, "Unison", listOf(UNISON, SpicyUnisonLyricsProvider.ID), ALL_TYPES, setOf("Unison")),
        // Spotify's lyrics are Musixmatch's, and line synced at best.
        LyricsOrigin(SPOTIFY, "Spotify (Musixmatch)", listOf(SPICY, RMM), LINE_PLAIN, setOf("Spotify"), credit = "Musixmatch"),
        LyricsOrigin(KUGOU, "KuGou", blendsTimedBy(KUGOU) + KUGOU + LRCMUX, ALL_TYPES, setOf("KuGou")),
        LyricsOrigin(NETEASE, "NetEase", blendsTimedBy(NETEASE) + NETEASE, ALL_TYPES),
        LyricsOrigin(QQ_MUSIC, "QQ Music", blendsTimedBy(QQ_MUSIC) + QQ_MUSIC, ALL_TYPES),
        LyricsOrigin(KUWO, "Kuwo", blendsTimedBy(KUWO) + KUWO, ALL_TYPES),
        LyricsOrigin(LRCLIB, "LRCLIB", listOf(LRCLIB, LRCMUX), LINE_PLAIN, setOf("LRCLIB")),
        LyricsOrigin(LRC_RED_AI, "lrc.red AI syncs", listOf(LRC_RED), ALL_TYPES, setOf(LRC_RED_AI_NAME), credit = "lrc.red (AI sync)"),
        LyricsOrigin(GENIUS, "Genius", listOf(GENIUS, LRCMUX), listOf(RemoteLyricsQuality.PLAIN), setOf("Genius")),
        LyricsOrigin(YOUTUBE, "YouTube", listOf("youtube_transcript", LRCMUX), LINE_PLAIN, setOf("YouTube Music")),
    )

    /**
     * The sources for [providers]: the built-in ones that have a provider among them, each with
     * only those providers (a blend counts while its donors are there), then one source per
     * provider no built-in source lists, such as each custom source, in their default order.
     */
    fun forProviders(providers: Collection<LyricsSourceDescriptor>): List<LyricsOrigin> {
        val present = providers.map { it.id }.toSet()
        fun available(id: String) = id in present || LyricsBlends.byId(id)?.donorIds?.all(present::contains) == true
        val builtIn = BUILT_IN.mapNotNull { origin ->
            origin.copy(providerIds = origin.providerIds.filter(::available)).takeIf { it.providerIds.any(present::contains) }
        }
        val listed = BUILT_IN.flatMap { it.providerIds }.toSet()
        val own = providers.filter { it.id !in listed }
            .sortedWith(compareBy<LyricsSourceDescriptor> { it.defaultPriority }.thenBy { it.id })
            .map { LyricsOrigin(it.id, it.displayName, listOf(it.id), it.qualities()) }
        return builtIn + own
    }

    /**
     * The source [providerId]'s answer belongs to: the one source that lists it, or, for a
     * provider serving several, the one whose [LyricsOrigin.originNames] has [originName].
     * Null when it names none of them: such an answer ranks after every source.
     */
    fun originOf(origins: List<LyricsOrigin>, providerId: String, originName: String?): LyricsOrigin? {
        val candidates = origins.filter { providerId in it.providerIds }
        return candidates.singleOrNull() ?: candidates.firstOrNull { originName != null && originName in it.originNames }
    }

    private fun LyricsSourceDescriptor.qualities(): List<RemoteLyricsQuality> = buildList {
        if (LyricsCapability.WORD_SYNC in capabilities) add(RemoteLyricsQuality.WORD_SYNCED)
        if (LyricsCapability.LINE_SYNC in capabilities) add(RemoteLyricsQuality.LINE_SYNCED)
        if (LyricsCapability.PLAIN_TEXT in capabilities) add(RemoteLyricsQuality.PLAIN)
    }.ifEmpty { listOf(RemoteLyricsQuality.PLAIN) }
}

/**
 * A [RemoteLyricsPolicy] read against the sources of a set of providers: which providers are
 * asked, where each answer ranks, and whether it counts.
 *
 * An answer ranks by (its source's place in the user's order, its provider's place in that
 * source). A provider is asked once per lookup when any of its switches is on in a source that is
 * on. Its answer counts when its switch in the source the answer belongs to is on, that source is
 * on, and the source allows the answer's sync type.
 */
class SourceRanking(providers: Collection<LyricsSourceDescriptor>, val policy: RemoteLyricsPolicy) {
    private val descriptors = providers.associateBy { it.id }

    /** The sources, in the user's order. */
    val origins: List<LyricsOrigin> = LyricsOrigins.forProviders(providers).let { all ->
        val byId = all.associateBy { it.id }
        LyricsSourcePreferenceNormalizer.order(policy.sourceOrder, all.map { it.id }).map(byId::getValue)
    }
    private val index = origins.withIndex().associate { (i, origin) -> origin.id to i }

    fun sourceOn(originId: String): Boolean = originId in index && originId !in policy.disabledSourceIds

    /** The pair's own switch, whatever its source's. */
    fun pairOn(originId: String, providerId: String): Boolean = policy.pairs[SourcePair(originId, providerId).key]
        ?: (LyricsBlends.byId(providerId) == null && descriptors[providerId]?.defaultEnabled == true)

    /** Switched on with its source, and for a blend, with every donor asked (live in a source of its own). */
    fun live(origin: LyricsOrigin, providerId: String): Boolean {
        if (providerId !in origin.providerIds || !sourceOn(origin.id) || !pairOn(origin.id, providerId)) return false
        val blend = LyricsBlends.byId(providerId) ?: return true
        return blend.donorIds.all(::asked)
    }

    /** Whether [providerId] is asked: any of its switches is live. Blends are built, never asked. */
    fun asked(providerId: String): Boolean =
        LyricsBlends.byId(providerId) == null && origins.any { live(it, providerId) }

    /** The blends to build, in rank order. */
    val liveBlends: List<LyricsBlendDefinition> = origins.flatMap { origin ->
        origin.providerIds.mapNotNull(LyricsBlends::byId).filter { live(origin, it.id) }
    }

    /** Where an answer from [providerId] in [origin] ranks: lower is better. */
    fun place(origin: LyricsOrigin, providerId: String): Int =
        index.getValue(origin.id) * SLOTS + origin.providerIds.indexOf(providerId).coerceAtLeast(0)

    /**
     * The best place [providerId] could still answer at: its earliest live switch, leaving out the
     * sources in [except]. Null when it has none.
     */
    fun bestPlace(providerId: String, except: Set<String> = emptySet()): Int? =
        origins.filter { it.id !in except && live(it, providerId) }.minOfOrNull { place(it, providerId) }

    /** Where a provider that isn't asked is listed: its first source's place. */
    fun homePlace(providerId: String): Int =
        origins.firstOrNull { providerId in it.providerIds }?.let { place(it, providerId) } ?: AFTER_ALL

    fun originOf(providerId: String, payload: RemoteLyricsPayload): LyricsOrigin? =
        LyricsOrigins.originOf(origins, providerId, payload.attribution?.originName)

    /** Where [providerId]'s [payload] ranks: its source's place, or after every source. */
    fun answerPlace(providerId: String, payload: RemoteLyricsPayload): Int =
        originOf(providerId, payload)?.let { place(it, providerId) } ?: AFTER_ALL

    /**
     * Why [providerId]'s answer of [quality] doesn't count, or null when it does. An answer from
     * a source none of the provider's lists counts as long as the provider is asked.
     */
    fun filtered(providerId: String, payload: RemoteLyricsPayload, quality: RemoteLyricsQuality): String? {
        val origin = originOf(providerId, payload) ?: return null
        return when {
            !sourceOn(origin.id) || !pairOn(origin.id, providerId) -> "Switched off for ${origin.displayName}"
            quality in policy.syncTypesOff[origin.id].orEmpty() -> "${quality.typeName()} is off for ${origin.displayName}"
            else -> null
        }
    }

    private fun RemoteLyricsQuality.typeName() = when (this) {
        RemoteLyricsQuality.WORD_SYNCED -> "Word sync"
        RemoteLyricsQuality.LINE_SYNCED -> "Line sync"
        else -> "Unsynced"
    }

    companion object {
        /** Room for a source's providers between two sources' places. */
        private const val SLOTS = 1_000
        const val AFTER_ALL = Int.MAX_VALUE / 2
    }
}
