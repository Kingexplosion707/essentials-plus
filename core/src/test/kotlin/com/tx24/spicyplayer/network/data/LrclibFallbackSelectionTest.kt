package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.network.model.LrclibSearchEntry
import com.tx24.spicyplayer.network.model.SongLyricsNetwork
import com.tx24.spicyplayer.network.service.LyricsService
import org.junit.Assert.assertEquals
import org.junit.Test

class LrclibFallbackSelectionTest {
    private val source = LyricsSource(object : LyricsService {
        override suspend fun getSongLyrics(artistName: String, trackName: String, albumName: String?, durationSeconds: Int?): SongLyricsNetwork = error("unused")
        override suspend fun searchSongLyrics(artistName: String, trackName: String): List<LrclibSearchEntry> = error("unused")
    })

    @Test fun prefersMatchingAlbumAndRejectsWrongRecording() {
        val request = LyricsLookupRequest("Creepy Nuts", "Bling-Bang-Bang-Born", "LEGION", 169)
        val otherAlbum = entry("Bling-Bang-Bang-Born", "Creepy Nuts", "Single", 169.0)
        val matchingAlbum = entry("Bling‐Bang‐Bang‐Born", "Creepy Nuts", "LEGION", 170.0)
        val wrongVersion = entry("Bling-Bang-Bang-Born (Live)", "Creepy Nuts", "LEGION", 169.0)

        assertEquals(matchingAlbum, source.chooseSearchResult(request, listOf(otherAlbum, wrongVersion, matchingAlbum)))
    }

    @Test fun instrumentalRecordIsAMissNotAnError() = kotlinx.coroutines.runBlocking {
        val instrumental = LyricsSource(object : LyricsService {
            override suspend fun getSongLyrics(artistName: String, trackName: String, albumName: String?, durationSeconds: Int?) =
                SongLyricsNetwork(239076, null, null)
            override suspend fun searchSongLyrics(artistName: String, trackName: String): List<LrclibSearchEntry> = error("unused")
        })
        assertEquals(ProviderResult.Miss, instrumental.fetch(LyricsLookupRequest("Daft Punk", "Aerodynamic", "Discovery", 213)))
    }

    @Test fun aNearEmptyRecordGivesWayToAFullerCopy() = kotlinx.coroutines.runBlocking {
        val full = entry("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", 213.0)
        val vandalised = LyricsSource(object : LyricsService {
            override suspend fun getSongLyrics(artistName: String, trackName: String, albumName: String?, durationSeconds: Int?) =
                SongLyricsNetwork(1009665, "probe", "[00:00.00]probe")
            override suspend fun searchSongLyrics(artistName: String, trackName: String) = listOf(full)
        })
        val hit = vandalised.fetch(LyricsLookupRequest("Rick Astley", "Never Gonna Give You Up", "Whenever You Need Somebody", 213)) as ProviderResult.Hit
        assertEquals(full.plainLyrics, hit.payload.plainLyrics)
        // A short song's few words are its lyrics.
        assertEquals(false, source.thin("Hey", null, 45))
    }

    @Test fun anOverloadedExactLookupAsksTheSearch() = kotlinx.coroutines.runBlocking {
        val full = entry("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", 213.0)
        fun busy(found: List<LrclibSearchEntry>) = LyricsSource(object : LyricsService {
            override suspend fun getSongLyrics(artistName: String, trackName: String, albumName: String?, durationSeconds: Int?): SongLyricsNetwork =
                throw retrofit2.HttpException(retrofit2.Response.error<Any>(503, okhttp3.ResponseBody.Companion.run { "".toResponseBody() }))
            override suspend fun searchSongLyrics(artistName: String, trackName: String) = found
        })
        val request = LyricsLookupRequest("Rick Astley", "Never Gonna Give You Up", "Whenever You Need Somebody", 213)
        assertEquals(full.plainLyrics, (busy(listOf(full)).fetch(request) as ProviderResult.Hit).payload.plainLyrics)
        // Nothing found either way: still worth asking again later.
        assertEquals(true, busy(emptyList()).fetch(request) is ProviderResult.Queued)
    }

    @Test fun theSameRecordingBeatsTheSameAlbumFurtherOff() {
        val request = LyricsLookupRequest("Creepy Nuts", "Bling-Bang-Bang-Born", "LEGION", 169)
        val albumEdit = entry("Bling-Bang-Bang-Born", "Creepy Nuts", "LEGION", 176.0)
        val sameLength = entry("Bling-Bang-Bang-Born", "Creepy Nuts", "Single", 170.0)
        assertEquals(sameLength, source.chooseSearchResult(request, listOf(albumEdit, sameLength)))
    }

    private fun entry(title: String, artist: String, album: String, duration: Double) =
        LrclibSearchEntry(title, artist, album, duration, "We're no strangers to love\nYou know the rules and so do I\nA full commitment's what I'm thinking of", null)
}
