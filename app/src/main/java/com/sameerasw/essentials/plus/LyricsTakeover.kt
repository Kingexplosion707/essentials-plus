package com.sameerasw.essentials.plus

import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.island.model.IslandItem
import com.sameerasw.essentials.island.model.IslandPriority
import com.sameerasw.essentials.island.state.CompactArrangement

/**
 * Essentials Plus: while Spicy Lyrics' island item is up, the compact island gives it a whole side
 * of the camera, with only the clock (optionally) on the other side, instead of letting the other
 * icons crowd it out. Calls, notifications and alerts still take over as usual; when the lyrics
 * item goes, the island lays out as before. Settings → Island → Lyrics (Essentials Plus).
 */
object LyricsTakeover {
    const val KEY_ENABLED = "plus_lyrics_takeover"
    const val KEY_SIDE = "plus_lyrics_side"
    const val KEY_CLOCK = "plus_lyrics_keep_clock"
    const val SIDE_RIGHT = "right"
    const val SIDE_LEFT = "left"
    val KEYS = setOf(KEY_ENABLED, KEY_SIDE, KEY_CLOCK)

    /** The apps whose island item is lyrics. */
    private val LYRICS_PACKAGES = setOf("com.behnoud.spicylyrics", "com.behnoud.spicylyrics.debug")
    private const val TIME_KEY = "time"

    @Volatile var enabled = true
        private set
    @Volatile var lyricsOnRight = true
        private set
    @Volatile var keepClock = true
        private set

    fun load(settings: SettingsRepository) {
        enabled = settings.getBoolean(KEY_ENABLED, true)
        lyricsOnRight = settings.getString(KEY_SIDE, SIDE_RIGHT) != SIDE_LEFT
        keepClock = settings.getBoolean(KEY_CLOCK, true)
    }

    fun isLyrics(key: String): Boolean = LYRICS_PACKAGES.any { key.startsWith("$it:") }

    /**
     * The compact layout while the lyrics are up: the lyrics alone on their side, the clock (if
     * kept) on the other. Null when it doesn't apply (off, no lyrics, or something more urgent),
     * so the island lays out as usual.
     */
    fun arrange(items: Map<String, IslandItem>): CompactArrangement? {
        if (!enabled) return null
        val lyrics = items.values.firstOrNull { it.compactVisible && isLyrics(it.key) && it.compact.isNotEmpty() } ?: return null
        val urgent = items.values.any {
            it.compactVisible && it.key != lyrics.key && it.key != TIME_KEY && it.effectivePriority <= IslandPriority.NOTIFICATION
        }
        if (urgent) return null
        // Ordered inner (by the camera) to outer: the text by the camera, a cover cell outside it.
        val lyricCells = lyrics.compact.filterNot { it.soloOnly }.ifEmpty { lyrics.compact }.map { it.key }.reversed()
        val clock = items[TIME_KEY]?.takeIf { keepClock && it.compactVisible }
        val clockCells = clock?.compact?.filterNot { it.soloOnly }?.map { it.key }.orEmpty()
        val visible = setOfNotNull(lyrics.key, clock?.key)
        return if (lyricsOnRight) {
            CompactArrangement(before = clockCells, after = lyricCells, visibleItems = visible)
        } else {
            CompactArrangement(before = lyricCells, after = clockCells, visibleItems = visible)
        }
    }
}
