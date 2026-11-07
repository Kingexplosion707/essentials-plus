package com.sameerasw.essentials.utils.lyrics

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads the live lyrics notification posted by the Spicy Lyrics Plus app ("Lyrics notification" in
 * its Settings → Device). Its title is the line being sung and its text is "Title — Artist".
 *
 * Fed by [com.sameerasw.essentials.services.NotificationListener]; read by the island's media
 * plugin, which falls back to LRCLIB when Spicy isn't installed or isn't posting.
 */
object SpicyLyricsBridge {
    /** Spicy Lyrics Plus, and the upstream Spicy Lyrics app it forks. */
    val PACKAGES = setOf("com.behnoud.spicylyrics", "com.tx24.spicyplayer")
    private const val CHANNEL_ID = "live_lyrics"
    private const val INSTRUMENTAL = "♪"
    private const val SONG_SEPARATOR = " — "

    data class SpicyLine(
        /** The sung line, or null in an instrumental gap. */
        val text: String?,
        val title: String,
        val artist: String,
        val key: String,
        val atElapsed: Long,
    )

    private val _line = MutableStateFlow<SpicyLine?>(null)
    val line: StateFlow<SpicyLine?> = _line.asStateFlow()

    fun isInstalled(context: Context): Boolean = PACKAGES.any { pkg ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isCandidate(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in PACKAGES) return false
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sbn.notification.channelId else null
        return channel == null || channel == CHANNEL_ID
    }

    fun onPosted(sbn: StatusBarNotification) {
        if (!isCandidate(sbn)) return
        val extras = sbn.notification.extras ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val song = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val sep = song.lastIndexOf(SONG_SEPARATOR)
        if (sep <= 0) {
            // "Nothing playing", or paused (Spicy swaps the song for a "Paused" label).
            _line.value = null
            return
        }
        val title = song.substring(0, sep).trim()
        val artist = song.substring(sep + SONG_SEPARATOR.length).trim()
        // Without lyrics Spicy shows the song title as the line: that's no lyrics, not a line.
        val sung = when {
            text.isBlank() || text == INSTRUMENTAL -> null
            text.equals(title, ignoreCase = true) -> {
                _line.value = null
                return
            }
            else -> text
        }
        _line.value = SpicyLine(sung, title, artist, sbn.key, SystemClock.elapsedRealtime())
    }

    fun onRemoved(key: String) {
        if (_line.value?.key == key) _line.value = null
    }

    fun clear() {
        _line.value = null
    }

    /** Whether [line] is about the track [title] by [artist] (players and Spicy tidy names differently). */
    fun matches(line: SpicyLine, title: String, artist: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
        val a = norm(line.title)
        val b = norm(title)
        if (a.isEmpty() || b.isEmpty()) return false
        val titleOk = a == b || a.startsWith(b) || b.startsWith(a)
        val artistOk = artist.isBlank() || line.artist.isBlank() ||
            norm(artist).let { n -> n.contains(norm(line.artist)) || norm(line.artist).contains(n) }
        return titleOk && artistOk
    }
}
