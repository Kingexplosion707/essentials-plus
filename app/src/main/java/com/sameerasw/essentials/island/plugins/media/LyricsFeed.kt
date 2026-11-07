package com.sameerasw.essentials.island.plugins.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import com.sameerasw.essentials.utils.lyrics.LrclibLyrics
import com.sameerasw.essentials.utils.lyrics.SpicyLyricsBridge
import com.sameerasw.essentials.utils.lyrics.TrackLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The lyric line being sung for the active media session.
 *
 * Source order: the Spicy Lyrics Plus app's live notification when it's posting lines for this
 * song (word-synced, many sources), else LRCLIB line-synced lyrics timed off the media session.
 */
class LyricsFeed(
    private val context: Context,
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val onLine: (LyricLine?) -> Unit,
) {
    data class LyricLine(val text: String, val source: Source, val nextChangeInMs: Long?)

    enum class Source { SPICY, LRCLIB }

    private data class Track(
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
    )

    private var controller: MediaController? = null
    private var track: Track? = null
    private var lyrics: TrackLyrics? = null
    private var fetchJob: Job? = null
    private var spicyJob: Job? = null
    private var shown: LyricLine? = null
    private var spicyInstalled = false
    private val tick = Runnable { update() }

    fun start() {
        spicyInstalled = SpicyLyricsBridge.isInstalled(context)
        spicyJob = scope.launch { SpicyLyricsBridge.line.collectLatest { update() } }
    }

    fun stop() {
        handler.removeCallbacks(tick)
        fetchJob?.cancel()
        spicyJob?.cancel()
        controller = null
        track = null
        lyrics = null
        emit(null)
    }

    /** Points the feed at [c] (or nothing). Cheap to call on every media change. */
    fun follow(c: MediaController?) {
        controller = c
        val metadata = c?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        if (c == null || title.isBlank()) {
            track = null
            lyrics = null
            fetchJob?.cancel()
            update()
            return
        }
        val key = "${title}\u0000$artist"
        if (track?.key != key) {
            track = Track(
                key = key,
                title = title,
                artist = artist,
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            )
            lyrics = LrclibLyrics.cached(key)
            fetchJob?.cancel()
            if (lyrics == null) fetchLrclib(track!!)
        }
        update()
    }

    private fun fetchLrclib(t: Track) {
        fetchJob = scope.launch {
            // Give Spicy a moment: when it's already posting lines for this song, skip the network.
            if (spicyInstalled) delay(SPICY_GRACE_MS)
            if (track?.key != t.key || spicyLine() != null) return@launch
            var wait = RETRY_START_MS
            repeat(MAX_ATTEMPTS) {
                val found = withContext(Dispatchers.IO) {
                    runCatching { LrclibLyrics.fetch(t.key, t.title, t.artist, t.album, t.durationMs) }.getOrNull()
                }
                if (track?.key != t.key) return@launch
                if (found != null) {
                    lyrics = found
                    update()
                    return@launch
                }
                delay(wait)
                wait *= 2
            }
        }
    }

    private fun spicyLine(): SpicyLyricsBridge.SpicyLine? {
        val t = track ?: return null
        val line = SpicyLyricsBridge.line.value ?: return null
        return line.takeIf { SpicyLyricsBridge.matches(it, t.title, t.artist) }
    }

    private fun update() {
        handler.removeCallbacks(tick)
        val c = controller
        val state = c?.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        if (c == null || track == null || !playing) {
            emit(null)
            return
        }
        spicyLine()?.let { spicy ->
            emit(spicy.text?.let { LyricLine(it, Source.SPICY, null) })
            return
        }
        // Spicy stopped posting for this song after we skipped LRCLIB for it: ask LRCLIB now.
        val t = track!!
        if (lyrics == null && fetchJob?.isActive != true) fetchLrclib(t)
        val synced = lyrics?.takeIf { it.synced }
        if (synced == null) {
            emit(null)
            return
        }
        val pos = positionMs(state)
        val line = synced.lineAt(pos)
        val next = synced.nextStartAfter(pos)
        val untilNext = next?.let { ((it - pos) / state.playbackSpeed.coerceAtLeast(0.1f)).toLong() }
        emit(line?.let { LyricLine(it.text, Source.LRCLIB, untilNext) })
        if (untilNext != null) handler.postDelayed(tick, untilNext.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
    }

    private fun emit(line: LyricLine?) {
        if (line?.text == shown?.text && line?.source == shown?.source) return
        shown = line
        onLine(line)
    }

    private fun positionMs(state: PlaybackState): Long {
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed
        return (state.position + elapsed.toLong()).coerceAtLeast(0L)
    }

    companion object {
        private const val SPICY_GRACE_MS = 1_500L
        private const val RETRY_START_MS = 5_000L
        private const val MAX_ATTEMPTS = 3
        private const val MIN_WAIT_MS = 16L
        private const val MAX_WAIT_MS = 2_000L
    }
}
