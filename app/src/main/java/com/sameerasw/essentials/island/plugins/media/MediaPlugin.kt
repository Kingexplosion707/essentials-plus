package com.sameerasw.essentials.island.plugins.media

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import androidx.compose.ui.graphics.Color
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.island.model.CompactCell
import com.sameerasw.essentials.island.model.CompactPlacement
import com.sameerasw.essentials.island.model.ExpandedContent
import com.sameerasw.essentials.island.model.IslandItem
import com.sameerasw.essentials.island.model.IslandPriority
import com.sameerasw.essentials.island.model.LineContent
import com.sameerasw.essentials.island.model.PluginRequest
import com.sameerasw.essentials.island.plugins.BaseIslandPlugin
import com.sameerasw.essentials.island.plugins.accentFrom
import com.sameerasw.essentials.island.plugins.launchPackage
import com.sameerasw.essentials.island.plugins.sendPendingIntent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sameerasw.essentials.island.gestures.IslandSlideFeedback
import com.sameerasw.essentials.island.gestures.SlideFeedback
import com.sameerasw.essentials.island.ui.components.EqualizerBars
import com.sameerasw.essentials.island.ui.components.MediaArtCue
import com.sameerasw.essentials.island.ui.components.TrackSkipPill
import com.sameerasw.essentials.island.ui.components.IslandBitmap
import com.sameerasw.essentials.services.NotificationListener
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MediaPlugin : BaseIslandPlugin() {
    override val id = ID

    override val settingKeys = setOf(
        SettingsRepository.KEY_ISLAND_SHOW_MEDIA,
        SettingsRepository.KEY_ISLAND_MEDIA_EXCLUDED_APPS,
        SettingsRepository.KEY_ISLAND_MEDIA_SHOW_PREVIOUS,
        SettingsRepository.KEY_ISLAND_MEDIA_SHOW_LIKE,
        SettingsRepository.KEY_ISLAND_MEDIA_USE_ART_COLOR,
        SettingsRepository.KEY_ISLAND_MEDIA_PLAYER_STYLE,
        SettingsRepository.KEY_ISLAND_MEDIA_LYRICS,
    )

    private val ART_RETRY_DELAYS_MS = longArrayOf(1500L, 3000L)

    private data class Track(
        val key: String,
        val packageName: String,
        val title: String,
        val artist: String,
        val artwork: Bitmap?,
        val accent: Int?,
    )

    private var source: MediaSessionSource? = null
    private var active: MediaController? = null
    private var track: Track? = null
    private var playing = false
    private var liked = false
    private var lyricsFeed: LyricsFeed? = null
    private var lyric: LyricsFeed.LyricLine? = null

    private var lastController: MediaController? = null
    private var lastTrack: Track? = null
    private val pausedGrace = Runnable {
        active = null
        track = null
        render()
    }

    override fun onStart() {
        val c = ctx!!
        lyricsFeed = LyricsFeed(context, c.scope, c.mainHandler) { line -> onLyric(line) }.also { it.start() }
        source = MediaSessionSource(context, c.mainHandler) { evaluate() }.also { it.start() }
    }

    override fun onStop() {
        IslandMediaState.current.value = null
        lastController = null
        lastTrack = null
        ctx?.mainHandler?.removeCallbacks(pausedGrace)
        source?.stop()
        source = null
        lyricsFeed?.stop()
        lyricsFeed = null
        lyric = null
        active = null
        track = null
    }

    override fun refresh() = evaluate()

    private fun evaluate() {
        val c = ctx ?: return
        if (!settings.isIslandShowMediaEnabled()) {
            c.mainHandler.removeCallbacks(pausedGrace)
            active = null
            track = null
            render()
            return
        }
        val excluded = settings.loadIslandMediaExcludedApps().filter { it.isEnabled }.map { it.packageName }.toSet()
        val controllers = source?.all.orEmpty()
        val playingController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING && it.packageName !in excluded
        }

        if (playingController != null) {
            c.mainHandler.removeCallbacks(pausedGrace)
            active = playingController
            lastController = playingController
            playing = true
            liked = MediaSessionSource.isLiked(playingController)
            val metadata = playingController.metadata
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            if (title.isBlank() && artist.isBlank()) return
            val key = "${playingController.packageName}_${title}_$artist"
            if (track?.key == key) {
                render()
                return
            }
            val isNewTrack = track != null
            c.scope.launch {
                var hadArt = true
                val (art, accent) = withContext(Dispatchers.IO) {
                    val bmp = MediaSessionSource.artwork(context, metadata)
                    hadArt = bmp != null
                    val resolved = bmp ?: MediaSessionSource.appIcon(context, playingController.packageName)
                    resolved to accentFrom(resolved)
                }
                if (active?.sessionToken != playingController.sessionToken) return@launch
                track = Track(key, playingController.packageName, title, artist, art, accent)
                lastTrack = track
                render()
                if (isNewTrack && settings.isIslandMediaPeekSongChangeEnabled()) {
                    c.request(PluginRequest.Peek(ITEM_KEY, settings.getIslandPeekDurationMs()))
                }
                if (!hadArt) retryArtwork(key, playingController)
            }
            return
        }

        val current = active
        val paused = current != null && track != null &&
            controllers.any { it.sessionToken == current.sessionToken } &&
            current.packageName !in excluded &&
            current.playbackState?.state == PlaybackState.STATE_PAUSED
        val transient = current != null && track != null &&
            controllers.any { it.sessionToken == current.sessionToken } &&
            current.packageName !in excluded &&
            current.playbackState?.state.let { it == PlaybackState.STATE_BUFFERING || it == PlaybackState.STATE_CONNECTING || it == PlaybackState.STATE_SKIPPING_TO_NEXT || it == PlaybackState.STATE_SKIPPING_TO_PREVIOUS || it == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM }
        if (transient) return
        if (paused) {
            playing = false
            render()
            c.mainHandler.removeCallbacks(pausedGrace)
            val keep = settings.isIslandMediaKeepWhenPausedEnabled()
            if (!keep || !settings.isIslandCatchUpStoredInfinite()) {
                c.mainHandler.postDelayed(pausedGrace, if (keep) settings.getIslandCatchUpStoredMs() else PAUSED_GRACE_MS)
            }
        } else {
            c.mainHandler.removeCallbacks(pausedGrace)
            active = null
            track = null
            render()
        }
    }

    private fun artAccent(t: Track): Color? =
        if (settings.isIslandMediaUseArtColorEnabled()) t.accent?.let { Color(it) } else null

    private fun canShowLike(controller: MediaController) =
        settings.isIslandMediaShowLikeEnabled() && controller.ratingType != 0

    private fun supportsAction(controller: MediaController, action: Long) =
        (controller.playbackState?.actions ?: 0L) and action != 0L

    private fun render() {
        val t = track
        val controller = active
        publishLastPlayer()
        if (t == null || controller == null) {
            publish(null)
            lyricsFeed?.follow(null)
            return
        }
        val artAccent = artAccent(t)
        val lyricText = lyric?.text?.takeIf { settings.isIslandMediaLyricsEnabled() }
        val lyricLine = lyricText?.let { splitAroundCamera(it) }
        val playerStyle = MediaPlayerStyle.from(settings.getIslandMediaPlayerStyle())
        val isPlaying = playing
        val isLiked = liked
        val canSkipBack = supportsAction(controller, PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        val canSkipForward = supportsAction(controller, PlaybackState.ACTION_SKIP_TO_NEXT)
        val actions = MediaActions(
            playPause = { togglePlay() },
            next = { active?.transportControls?.skipToNext() },
            previous = if (settings.isIslandMediaShowPreviousEnabled()) ({ active?.transportControls?.skipToPrevious() }) else null,
            like = if (canShowLike(controller)) ({ like() }) else null,
            progress = { active?.let(MediaSessionSource::position) ?: 0f },
            canSeek = { active?.let(MediaSessionSource::canSeek) ?: false },
            seekTo = { fraction -> active?.let { MediaSessionSource.seekTo(it, fraction) } },
        )
        publish(
            IslandItem(
                key = ITEM_KEY,
                priority = IslandPriority.MEDIA,
                placement = CompactPlacement.Dynamic,
                compact = listOf(
                    CompactCell("media.art") { MediaArtCue(t.artwork, mediaAccent(artAccent), 22.dp) },
                    CompactCell("media.eq") {
                        val slide by IslandSlideFeedback.state.collectAsState()
                        val track = slide as? SlideFeedback.Track
                        if (track != null) {
                            TrackSkipPill(track.next, track.armed, mediaAccent(artAccent))
                        } else {
                            EqualizerBars(isPlaying, mediaAccent(artAccent))
                        }
                    },
                ),
                line = lyricLine?.let { (first, second) ->
                    LineContent(
                        icon = { IslandBitmap(t.artwork, 24.dp, circle = true) },
                        start = first,
                        end = second,
                        endSlot = { EqualizerBars(isPlaying, mediaAccent(artAccent)) },
                    )
                } ?: LineContent(
                    icon = { IslandBitmap(t.artwork, 24.dp, circle = true) },
                    start = t.artist,
                    end = t.title,
                    endSlot = { EqualizerBars(isPlaying, mediaAccent(artAccent)) },
                ),
                expanded = ExpandedContent { scope ->
                    MediaExpanded(t.title, t.artist, t.artwork, mediaAccent(t.accent?.let { Color(it) }), isPlaying, isLiked, actions, scope, canSkipBack = canSkipBack, canSkipForward = canSkipForward, packageName = t.packageName, style = playerStyle, lyric = lyricText)
                },
                accent = artAccent,
                onOpen = { openPlayer() },
                sourcePackage = t.packageName,
                hideExpandedQueue = true,
            ),
        )
        lyricsFeed?.follow(if (settings.isIslandMediaLyricsEnabled()) controller else null)
    }

    private fun onLyric(line: LyricsFeed.LyricLine?) {
        lyric = line
        render()
        val c = ctx ?: return
        if (line == null || active == null || !settings.isIslandMediaLyricsEnabled()) return
        if (!settings.isIslandMediaLyricsPeekEnabled()) return
        // Hold the pill open until the next line (when known), so lines flow without flicker.
        val hold = line.nextChangeInMs?.plus(LYRIC_HOLD_SLACK_MS) ?: LYRIC_DEFAULT_HOLD_MS
        c.request(PluginRequest.Peek(ITEM_KEY, hold.coerceIn(LYRIC_MIN_HOLD_MS, LYRIC_MAX_HOLD_MS)))
    }

    /** Splits a lyric line into the parts drawn either side of the camera, at a word near the middle. */
    private fun splitAroundCamera(text: String): Pair<String, String> {
        val words = text.trim().split(Regex("\\s+"))
        if (words.size < 2) return text.trim() to ""
        val total = text.length
        var used = 0
        var cut = 1
        for (i in words.indices) {
            used += words[i].length + 1
            if (used >= total / 2) {
                cut = (i + 1).coerceIn(1, words.size - 1)
                break
            }
        }
        return words.take(cut).joinToString(" ") to words.drop(cut).joinToString(" ")
    }

    private fun publishLastPlayer() {
        val controller = lastController
        val t = lastTrack
        val alive = controller != null && source?.all.orEmpty().any { it.sessionToken == controller.sessionToken }
        if (!settings.isIslandShowMediaEnabled() || !alive || t == null || controller == null) {
            if (!alive) {
                lastController = null
                lastTrack = null
            }
            IslandMediaState.current.value = null
            return
        }
        val actions = MediaActions(
            playPause = {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
            },
            next = { controller.transportControls.skipToNext() },
            previous = if (settings.isIslandMediaShowPreviousEnabled()) ({ controller.transportControls.skipToPrevious() }) else null,
            like = if (canShowLike(controller)) ({ like() }) else null,
            progress = { MediaSessionSource.position(controller) },
            canSeek = { MediaSessionSource.canSeek(controller) },
            seekTo = { fraction -> MediaSessionSource.seekTo(controller, fraction) },
        )
        IslandMediaState.current.value = MediaSnapshot(
            title = t.title,
            artist = t.artist,
            artwork = t.artwork,
            accent = artAccent(t),
            playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
            liked = MediaSessionSource.isLiked(controller),
            actions = actions,
            open = {
                if (!sendPendingIntent(context, controller.sessionActivity)) launchPackage(context, controller.packageName)
            },
            canSkipBack = supportsAction(controller, PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            canSkipForward = supportsAction(controller, PlaybackState.ACTION_SKIP_TO_NEXT),
            packageName = t.packageName,
            playerAccent = t.accent?.let { Color(it) },
            style = MediaPlayerStyle.from(settings.getIslandMediaPlayerStyle()),
        )
    }

    private suspend fun retryArtwork(key: String, controller: MediaController) {
        ART_RETRY_DELAYS_MS.forEach { wait ->
            delay(wait)
            if (track?.key != key || active?.sessionToken != controller.sessionToken) return
            val art = withContext(Dispatchers.IO) { MediaSessionSource.artwork(context, controller.metadata) } ?: return@forEach
            if (track?.key != key || active?.sessionToken != controller.sessionToken) return
            track = track?.copy(artwork = art, accent = accentFrom(art))
            lastTrack = track
            render()
            return
        }
    }

    private fun togglePlay() {
        val controller = active ?: return
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    private fun like() {
        context.sendBroadcast(Intent(NotificationListener.ACTION_LIKE_CURRENT_SONG).setPackage(context.packageName))
        ctx?.mainHandler?.postDelayed({
            active?.let { liked = MediaSessionSource.isLiked(it) }
            render()
        }, 400L)
    }

    private fun openPlayer() {
        val controller = active ?: return
        if (!sendPendingIntent(context, controller.sessionActivity)) launchPackage(context, controller.packageName)
    }

    companion object {
        const val ID = "media"
        const val ITEM_KEY = "media"
        private const val PAUSED_GRACE_MS = 3000L
        private const val LYRIC_HOLD_SLACK_MS = 1_200L
        private const val LYRIC_DEFAULT_HOLD_MS = 6_000L
        private const val LYRIC_MIN_HOLD_MS = 2_500L
        private const val LYRIC_MAX_HOLD_MS = 10_000L
    }
}
