package com.tx24.spicyplayer.network.data.spotify

import com.google.gson.Gson
import com.tx24.spicyplayer.network.canvas.SpotifyCanvasFinder
import java.io.File
import java.util.concurrent.TimeUnit
import com.tx24.spicyplayer.network.data.counted
import okhttp3.OkHttpClient

/**
 * The app's one anonymous Spotify session and match memory, shared by the lyrics lookup and the
 * track extras so a song is matched once and the token fetched once.
 */
object SharedSpotify {
    private val client = OkHttpClient.Builder()
        .counted()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        // The read timeout restarts with every byte: this caps a whole call, so a trickling
        // answer can't hold a lyric lookup's match open.
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val catalog = AnonymousSpotifyCatalogSearch(client, gson)
    val resolver = SpotifyTrackResolver(catalog)

    @Volatile private var extras: SpotifyTrackExtras? = null
    @Volatile private var canvas: SpotifyCanvasFinder? = null

    fun extras(cacheDir: File): SpotifyTrackExtras = extras ?: synchronized(this) {
        extras ?: SpotifyTrackExtras(client, gson, catalog, resolver, cacheDir).also { extras = it }
    }

    /** The Spotify Canvas lookup, on the same connections, given up on sooner: it's only decoration. */
    fun canvas(cacheDir: File): SpotifyCanvasFinder = canvas ?: synchronized(this) {
        canvas ?: SpotifyCanvasFinder(
            client = client.newBuilder().callTimeout(CANVAS_TIMEOUT_S, TimeUnit.SECONDS).build(),
            // v2: answers from the current Canvas service, not the one before it.
            memoFile = File(cacheDir, "canvas/known-v2.json"),
        ).also { canvas = it }
    }

    private const val CALL_TIMEOUT_S = 20L
    private const val CANVAS_TIMEOUT_S = 6L
}
