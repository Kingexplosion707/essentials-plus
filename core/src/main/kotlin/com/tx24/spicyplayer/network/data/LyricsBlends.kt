package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.network.data.blend.BlendDoc
import com.tx24.spicyplayer.network.data.blend.BlendDocuments
import com.tx24.spicyplayer.network.data.blend.BlendDonor
import com.tx24.spicyplayer.network.data.blend.BlendDonors
import com.tx24.spicyplayer.network.data.blend.BlendQuality
import com.tx24.spicyplayer.network.data.blend.BlendText
import com.tx24.spicyplayer.network.data.blend.LyricsBlender

/**
 * A blend: the lines of whatever ranks above it, under a donor's word timing, with a second donor
 * for the lines the first cannot place. It costs no request of its own: the donors are providers already in the walk.
 */
data class LyricsBlendDefinition(
    val id: String,
    val displayName: String,
    /** Whose word timing this is: the donor that decides where the blend is ranked. */
    val timingSourceId: String,
    val spareSourceId: String? = null,
) {
    val donorIds: List<String> get() = listOfNotNull(timingSourceId, spareSourceId)

    val descriptor = LyricsSourceDescriptor(
        id = id,
        displayName = displayName,
        defaultPriority = Int.MAX_VALUE,
        capabilities = setOf(LyricsCapability.WORD_SYNC),
        upstreamFamily = "blend",
        releaseChannel = SourceReleaseChannel.EXPERIMENTAL,
        defaultEnabled = false,
    )
}

object LyricsBlends {
    val ALL = listOf(
        LyricsBlendDefinition("blend_qq", "Blend: QQ Music timing", "qq_music"),
        LyricsBlendDefinition("blend_kugou", "Blend: Kugou timing", "kugou"),
        LyricsBlendDefinition("blend_netease", "Blend: NetEase timing", "netease"),
        LyricsBlendDefinition("blend_netease_qq", "Blend: NetEase timing, QQ Music fill", "netease", "qq_music"),
        LyricsBlendDefinition("blend_netease_kugou", "Blend: NetEase timing, Kugou fill", "netease", "kugou"),
        // Kuwo's word timing is mostly QQ Music's own numbers: more coverage, not a second opinion.
        LyricsBlendDefinition("blend_kuwo", "Blend: Kuwo timing", "kuwo"),
        LyricsBlendDefinition("blend_netease_kuwo", "Blend: NetEase timing, Kuwo fill", "netease", "kuwo"),
    )

    fun byId(id: String): LyricsBlendDefinition? = ALL.firstOrNull { it.id == id }

    /** How much steadier a blend must be than the first measured one to move ahead of it. */
    private const val PICK = 0.015
    /** ...or this share of the first one's figure, where that is more. */
    private const val PICK_SHARE = 0.2

    /**
     * Answers tied at the best quality, in rank order, with a meaningfully steadier blend moved
     * to the front of its run of consecutive blends (`_steadiest`). Two blends of one song differ
     * in whose clock is under the words, and the measured wander says which is better where the
     * user's order can't. A blend never passes an answer that isn't one. [isBlend] and
     * [steadiness] read each answer.
     */
    fun <T> steadiestFirst(tied: List<T>, isBlend: (T) -> Boolean, steadiness: (T) -> Double?): List<T> {
        val out = tied.toMutableList()
        var k = 0
        while (k < out.size) {
            var end = k
            while (end < out.size && isBlend(out[end])) end++
            if (end == k) { k++; continue }
            val run = out.subList(k, end)
            val marks = run.withIndex().mapNotNull { (i, it) -> steadiness(it)?.let { v -> i to v } }
            if (marks.size >= 2) {
                val (at, best) = marks.minBy { it.second }
                val first = marks.first().second
                if (first - best > maxOf(PICK, PICK_SHARE * first)) run.add(0, run.removeAt(at))
            }
            k = end
        }
        return out
    }

    /**
     * Builds [blend] from what the walk already holds. [above] is every
     * usable answer ranked above the blend, in rank order; the best of them by timing is the base,
     * [fallbackBase] only where none of them has any lyrics. [modelBase] is lrc.red's model sync of
     * the song, if it has one: where nothing above is Apple Music's own, it stands in as Apple's
     * lines, first among equals, with only its line starts kept ([BlendDocuments.startsOnly]).
     */
    fun build(
        blend: LyricsBlendDefinition,
        request: LyricsLookupRequest,
        above: List<Pair<LyricsSourceDescriptor, RemoteLyricsPayload>>,
        fallbackBase: Pair<LyricsSourceDescriptor, RemoteLyricsPayload>?,
        donors: Map<String, RemoteLyricsPayload?>,
        modelBase: Pair<LyricsSourceDescriptor, RemoteLyricsPayload>? = null,
        nameOf: (String) -> String,
    ): ProviderResult {
        val ranked = above.mapNotNull { (source, payload) ->
            BlendDocuments.from(payload)?.let { Base(source, payload, it) }
        }.filter { BlendText.quality(it.doc) != BlendQuality.NONE }
        val model = modelBase?.takeIf { ranked.none { it.payload.attribution?.originName == APPLE_MUSIC } }
            ?.let { (source, payload) ->
                BlendDocuments.from(payload)?.let(BlendDocuments::startsOnly)?.let {
                    Base(source, payload.copy(attribution = payload.attribution?.copy(originName = APPLE_MUSIC)), it)
                }
            }
        val picks = (listOfNotNull(model) + ranked).sortedByDescending { BlendText.quality(it.doc).rank }
        val base = picks.firstOrNull()
            ?: fallbackBase?.let { (source, payload) -> BlendDocuments.from(payload)?.let { Base(source, payload, it) } }
            ?: return ProviderResult.Miss

        fun donor(id: String?): BlendDonor? {
            id ?: return null
            val doc = donors[id]?.let(BlendDocuments::from)?.let { BlendDonors.shape(it, request.title, request.artist) }
            return BlendDonor(doc, nameOf(id), id)
        }
        val timing = donor(blend.timingSourceId)!!
        val spare = donor(blend.spareSourceId)
        if (timing.doc == null && spare?.doc == null) return ProviderResult.Miss

        val words = base.payload.attribution?.let { it.originName ?: it.providerName } ?: base.source.displayName
        val outcome = LyricsBlender.blended(base.doc, words, base.source.id, timing, spare) ?: return ProviderResult.Miss
        // Nothing borrowed: the base's own document, which already stands above this blend.
        if (outcome.via == null && outcome.alone == base.source.id) return ProviderResult.Miss
        if (outcome.quality == BlendQuality.NONE) return ProviderResult.Miss
        val writers = (base.payload.attribution?.songwriters.orEmpty() + outcome.doc.songwriters).distinct()
        return ProviderResult.Hit(RemoteLyricsPayload(
            ttmlLyrics = BlendDocuments.toTtml(BlendDoc(outcome.doc.lines), outcome.quality),
            blendSteadiness = outcome.steady,
            attribution = LyricsAttribution(
                providerName = blend.displayName,
                originName = outcome.via ?: outcome.alone?.let(nameOf),
                songwriters = writers,
            ),
        ))
    }

    private class Base(val source: LyricsSourceDescriptor, val payload: RemoteLyricsPayload, val doc: BlendDoc)

    private const val APPLE_MUSIC = "Apple Music"
}
