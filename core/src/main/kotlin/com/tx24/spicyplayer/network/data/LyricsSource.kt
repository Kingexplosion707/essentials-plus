package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.platform.CoreLog
import com.tx24.spicyplayer.network.service.LyricsService
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher
import com.tx24.spicyplayer.network.model.LrclibSearchEntry
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

private val STAMPS = Regex("""\[[^\]]*]""")
/** LRCLIB lengths are whole seconds and players round differently: this close is the same recording. */
private const val SAME_LENGTH_SECONDS = 2

@Singleton
class LyricsSource @Inject constructor(
    private val lyricsService: LyricsService
) : RemoteLyricsProvider {

    override val descriptor = LyricsSourceDescriptor(
        id = "lrclib",
        displayName = "LRCLIB",
        defaultPriority = 100,
        capabilities = setOf(
            LyricsCapability.LINE_SYNC,
            LyricsCapability.PLAIN_TEXT,
        ),
        upstreamFamily = "lrclib",
        releaseChannel = SourceReleaseChannel.RECOMMENDED,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
        return try {
            val response = lyricsService.getSongLyrics(
                request.artist,
                request.title,
                // A fetch-ahead queue entry has no album or length. LRCLIB rejects duration=0,
                // so leave unknown fields out instead.
                request.album.takeIf { it.isNotBlank() },
                request.durationSeconds.takeIf { it > 0 },
            )
            // An instrumental is a record with neither field filled in: no lyrics, not a broken answer.
            if (response.plainLyrics.isNullOrBlank() && response.syncedLyrics.isNullOrBlank()) {
                ProviderResult.Miss
            } else if (thin(response.plainLyrics, response.syncedLyrics, request.durationSeconds)) {
                // Records can be overwritten by anyone (Never Gonna Give You Up's reads "probe"):
                // a fuller copy of the same recording from the search beats a near-empty one.
                val fuller = runCatching { searchFallback(request) }.getOrNull() as? ProviderResult.Hit
                fuller ?: ProviderResult.Hit(RemoteLyricsPayload(plainLyrics = response.plainLyrics, syncedLyrics = response.syncedLyrics))
            } else ProviderResult.Hit(
                RemoteLyricsPayload(
                    plainLyrics = response.plainLyrics,
                    syncedLyrics = response.syncedLyrics,
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> runCatching { searchFallback(request) }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, retryable = true)
                }
                429 -> ProviderResult.CoolingDown(
                    RetryAfterParser.deadline(e.response()?.headers()?.get("Retry-After"))
                )
                // The exact lookup is overloaded far more often than the search: ask that before queueing.
                503 -> runCatching { searchFallback(request) }.getOrNull()?.takeIf { it is ProviderResult.Hit }
                    ?: ProviderResult.Queued(
                        e.response()?.headers()?.get("Retry-After")?.let {
                            RetryAfterParser.deadline(it, Instant.now())
                        }
                    )
                401, 403 -> ProviderResult.Unavailable(
                    category = ProviderFailureCategory.AUTHENTICATION,
                    message = "LRCLIB rejected the request (HTTP ${e.code()})",
                )
                in 400..499 -> ProviderResult.Unavailable(
                    category = ProviderFailureCategory.CLIENT_REQUEST,
                    message = "LRCLIB request was invalid (HTTP ${e.code()})",
                )
                else -> {
                    CoreLog.w("LyricsSource") { "lrclib request failed with HTTP ${e.code()}" }
                    val fallback = runCatching { searchFallback(request) }.getOrNull()
                    if (fallback is ProviderResult.Hit) fallback else ProviderResult.Unavailable(
                        category = ProviderFailureCategory.SERVER,
                        message = "LRCLIB server error (HTTP ${e.code()})",
                        retryable = e.code() >= 500,
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            ProviderResult.Unavailable(
                category = ProviderFailureCategory.TIMEOUT,
                message = e.message,
                retryable = true,
            )
        } catch (e: IOException) {
            ProviderResult.Unavailable(
                category = ProviderFailureCategory.NETWORK,
                message = e.message,
                retryable = true,
            )
        } catch (e: Exception) {
            ProviderResult.Unavailable(
                category = ProviderFailureCategory.UNKNOWN,
                message = e.message,
            )
        }
    }

    private suspend fun searchFallback(request: LyricsLookupRequest): ProviderResult {
        val candidates = lyricsService.searchSongLyrics(request.artist, request.title)
        val match = chooseSearchResult(request, candidates) ?: return ProviderResult.Miss
        return ProviderResult.Hit(RemoteLyricsPayload(match.plainLyrics, match.syncedLyrics))
    }

    /** Too little to be a song's lyrics: under three lines and eight words, for a song over a minute long. */
    fun thin(plain: String?, synced: String?, durationSeconds: Int): Boolean {
        if (durationSeconds in 1..60) return false
        val lines = (synced?.takeIf(String::isNotBlank) ?: plain.orEmpty()).lines()
            .map { it.replace(STAMPS, "").trim() }.filter(String::isNotEmpty)
        return lines.size < 3 && lines.sumOf { it.split(Regex("""\s+""")).size } < 8
    }

    fun chooseSearchResult(request: LyricsLookupRequest, candidates: List<LrclibSearchEntry>): LrclibSearchEntry? {
        val title = SpotifyTrackMatcher.normalize(request.title)
        val artist = SpotifyTrackMatcher.normalize(request.artist)
        return candidates.asSequence()
            .filter { SpotifyTrackMatcher.normalize(it.trackName.orEmpty()) == title }
            .filter { SpotifyTrackMatcher.normalize(it.artistName.orEmpty()) == artist }
            .filter { it.duration != null && request.durationSeconds > 0 && kotlin.math.abs(it.duration - request.durationSeconds) <= 8 }
            .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
            .filterNot { thin(it.plainLyrics, it.syncedLyrics, request.durationSeconds) }
            // A copy within SAME_LENGTH_SECONDS of the track is the same recording, ahead of one
            // from the same album further off (a radio edit, a live take): the timing follows the
            // recording, not the album. Then the album, then the closest length.
            .sortedWith(compareBy<LrclibSearchEntry> {
                kotlin.math.abs(requireNotNull(it.duration) - request.durationSeconds) > SAME_LENGTH_SECONDS
            }.thenByDescending {
                SpotifyTrackMatcher.normalize(it.albumName.orEmpty()) == SpotifyTrackMatcher.normalize(request.album)
            }.thenBy { kotlin.math.abs(requireNotNull(it.duration) - request.durationSeconds) })
            .firstOrNull()
    }
}
