package com.tx24.spicyplayer.lyrics.spicy.models

enum class LineRole { LEAD, BACKGROUND, INTERLUDE }

/**
 * Represents a full line of lyrics composed of multiple [Word]s.
 */
data class Line(
    /** The list of words that make up this line. */
    val words: List<Word>,
    /** The official start time of the line in milliseconds. */
    val startMs: Long,
    /** The normalized line lifetime, independent from the final word's timing. */
    val endMs: Long = words.lastOrNull()?.endMs ?: startMs,
    /** Optional identifier for the singer (agent). */
    val agent: String? = null,
    /** Structural role in the normalized lyric timeline. */
    val role: LineRole = LineRole.LEAD,
    /** Stable group shared by a lead line and its background vocals. */
    val groupId: Int? = null,
    /** If true, the line should be aligned to the opposite side (e.g., right-aligned for harmonies). */
    val oppositeAligned: Boolean = false,
    /** Display-only text with no word timings. The original document is kept separately. */
    val translationReplaces: Boolean = false,
    /** Timed only as a whole inside word-synced lyrics: animated and drawn as line-synced. */
    val lineTimed: Boolean = false,
    /** The source's own translation of this line (TTML `x-translation` or the head's entry). */
    val translation: String? = null,
    /** Synced lyrics' line with no timing of its own, placed in the gap around it; lit whole, never wiped. */
    val untimed: Boolean = false,
) {
    val isBackground: Boolean get() = role == LineRole.BACKGROUND
    val isInterlude: Boolean get() = role == LineRole.INTERLUDE
    /** Compatibility property while footer rendering is moved out of the timed layout. */
    val isSongwriter: Boolean get() = false

    /** The duration of the entire line in milliseconds, guaranteed to be at least 1ms. */
    val duration: Long
        get() = (endMs - startMs).coerceAtLeast(1)
}
