package com.tx24.spicyplayer.playback

import kotlin.math.abs

/** Reading a player's pause reports, which the lyric clock ([LyricClock]) can't take at their word. */
object ClockCorrection {
    /** Smaller differences are report jitter, not worth a visible jump. */
    const val JITTER_MS = 150L
    /** A pause report at least this far behind the clock means the song really went back. */
    const val STALE_PAUSE_MAX_MS = 5_000L

    /**
     * Players report a pause badly. The position they give is an old sample: YouTube Music's is up
     * to ~1.3 s behind where the audio stopped, KDE Connect's up to ~4 s. Reports while playing are
     * sound. So a pause report behind a clock that was in step is not believed: the clock keeps its
     * place and the gap becomes the bias returned here.
     *
     * From then on each report is read either as is or [biasMs] later, whichever agrees with the
     * clock: YouTube Music keeps counting from its stale sample after resuming, while the audio
     * carries on from where it really was; KDE Connect is right again on resume; and either may
     * correct itself later. A report that fits neither is a seek, and drops the bias.
     *
     * [pauseReport]: the first report of a pause after playing. [rawMs]: the report's position
     * brought to now. [predictedMs]: where the clock is.
     */
    fun reportBiasMs(rawMs: Long, predictedMs: Long, biasMs: Long, pauseReport: Boolean): Long {
        if (pauseReport) {
            val behind = predictedMs - rawMs
            // Position 0 is the player going back to the start, not an old sample.
            return if (rawMs > 0L && behind >= JITTER_MS && behind < STALE_PAUSE_MAX_MS) behind else 0L
        }
        // Back at the start: a restart or a new song, which no old sample explains.
        if (biasMs == 0L || rawMs == 0L) return 0L
        val asIs = abs(rawMs - predictedMs)
        val late = abs(rawMs + biasMs - predictedMs)
        return if (late < asIs && late < STALE_PAUSE_MAX_MS) biasMs else 0L
    }
}
