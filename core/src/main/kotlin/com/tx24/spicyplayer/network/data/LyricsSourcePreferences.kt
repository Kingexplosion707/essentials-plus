package com.tx24.spicyplayer.network.data

data class NormalizedLyricsSourcePreferences(
    val order: List<String>,
    val disabledSourceIds: Set<String>,
)

/**
 * The old per-provider source preferences brought up to date with the providers there are:
 * read once more when they're moved to sources ([LyricsSourceSettings.migrate]).
 */
object LyricsSourcePreferenceNormalizer {
    /**
     * [stored] without unknown or repeated IDs, with every ID of [defaults] it lacks inserted
     * after its nearest default neighbour above (first when none is there): new entries slot in
     * by the default order without undoing the user's moves. Null [stored] is the default order.
     */
    fun order(stored: List<String>?, defaults: List<String>): List<String> {
        if (stored == null) return defaults
        val known = defaults.toSet()
        return stored.filter(known::contains).distinct().toMutableList().apply {
            defaults.forEachIndexed { defaultIndex, id ->
                if (id in this) return@forEachIndexed
                val preceding = defaults.subList(0, defaultIndex).asReversed().firstOrNull { it in this }
                add(preceding?.let { indexOf(it) + 1 } ?: 0, id)
            }
        }
    }

    fun normalize(
        storedOrder: List<String>?,
        storedDisabledSourceIds: Set<String>,
        descriptors: Collection<LyricsSourceDescriptor>,
    ): NormalizedLyricsSourcePreferences {
        val defaults = descriptors
            .sortedWith(compareBy<LyricsSourceDescriptor> { it.defaultPriority }.thenBy { it.id })
        val knownById = defaults.associateBy(LyricsSourceDescriptor::id)
        val originallyKnown = storedOrder
            ?.asSequence()
            ?.filter(knownById::containsKey)
            ?.distinct()
            ?.toList()
        val order = order(storedOrder, defaults.map(LyricsSourceDescriptor::id))

        val newDefaultDisabled = defaults
            .asSequence()
            .filterNot(LyricsSourceDescriptor::defaultEnabled)
            .filter { storedOrder == null || it.id !in originallyKnown.orEmpty() }
            .map(LyricsSourceDescriptor::id)
            .toSet()
        val disabled = storedDisabledSourceIds
            .filterTo(mutableSetOf(), knownById::containsKey)
            .apply { addAll(newDefaultDisabled) }

        return NormalizedLyricsSourcePreferences(order, disabled)
    }
}
