package com.tx24.spicyplayer.lyrics

data class TimedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val attached: Boolean,
    /** Source-supplied romanization (TTML); on-device romanization fills gaps later. */
    val romanized: String? = null,
)

data class TimedLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<TimedWord>,
    val role: com.tx24.spicyplayer.lyrics.spicy.models.LineRole = com.tx24.spicyplayer.lyrics.spicy.models.LineRole.LEAD,
    val groupId: Int? = null,
    val agent: String? = null,
    val oppositeAligned: Boolean = false,
    /** Word-synced lyrics' line that is timed only as a whole ([MixedSync]); drawn as line-synced. */
    val lineTimed: Boolean = false,
    /** The source's own translation of this line. */
    val translation: String? = null,
    /** Synced lyrics' line with no timing of its own ([com.tx24.spicyplayer.lyrics.spicy.models.Line.untimed]). */
    val untimed: Boolean = false,
)

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Ready(
        val lines: List<TimedLine>,
        val source: String?,
        val maker: com.tx24.spicyplayer.network.data.LyricsContributor?,
        val uploader: com.tx24.spicyplayer.network.data.LyricsContributor?,
        val songwriters: List<String>,
        val provider: String = "Spicy Lyrics",
        val lyricsType: com.tx24.spicyplayer.lyrics.spicy.models.LyricsType = com.tx24.spicyplayer.lyrics.spicy.models.LyricsType.Syllable,
        /** The source brought its own romanization (TTML), which a human one from Genius doesn't replace. */
        val sourceRomanized: Boolean = false,
        /** The language of the lines' own translations ([TimedLine.translation]), when the source says. */
        val translationLanguage: String? = null,
    ) : LyricsState
    /**
     * A notice in place of lyrics. [kind] gives its headline and explanation; [detail] says which
     * sources answered what, and [code] is a short code for a report (an HTTP status, an exception).
     */
    data class Error(val kind: NoticeKind, val detail: String? = null, val code: String? = null) : LyricsState {
        val message: String get() = kind.title
        val body: String get() = kind.body
    }
}
