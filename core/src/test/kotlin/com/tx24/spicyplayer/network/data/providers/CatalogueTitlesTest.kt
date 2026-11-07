package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CatalogueTitlesTest {
    @Test
    fun `a bracketed tag doesn't stand between two titles, a version marker does`() {
        assertTrue(CatalogueTitles.sameTitle("新時代 (ウタ from ONE PIECE FILM RED)", "新時代"))
        assertTrue(CatalogueTitles.sameTitle("新時代", "新時代 (ウタ from ONE PIECE FILM RED)"))
        assertFalse(CatalogueTitles.sameTitle("新時代 (Live)", "新時代"))
        assertFalse(CatalogueTitles.sameTitle("Galaxies (RogueRemix)", "Galaxies"))
        assertTrue(CatalogueTitles.sameTitle("Galaxies (Remix)", "Galaxies (Remix)"))
        assertFalse(CatalogueTitles.sameTitle("Lemonade", "Lemon"))
    }

    @Test
    fun `a byline in another script stands in only with an exact title and length`() {
        fun same(theirs: String, title: String, seconds: Int) =
            CatalogueTitles.sameArtist(theirs, "TK from 凛として時雨", title, "unravel", seconds, 238)
        assertTrue(same("TK from Ling tosite sigure", "unravel", 239))
        assertFalse(same("TK from Ling tosite sigure", "unravel", 245))
        assertFalse(same("TK from Ling tosite sigure", "unravel (Acoustic)", 238))
        // The same script is no excuse: Ado's cover of unravel is still somebody else's.
        assertFalse(CatalogueTitles.sameArtist("Ado", "TK from Ling tosite sigure", "unravel", "unravel", 238, 238))
        assertTrue(CatalogueTitles.sameArtist("Ado, Someone", "Ado", "x", "y", 1, 100))
    }

    @Test
    fun `a Japanese title matches the romanised one by its reading`() {
        assertTrue(CatalogueTitles.sameReading("可愛くてごめん (feat. かぴ)", "Kawaikutegomen (feat. capi)"))
        assertFalse(CatalogueTitles.sameReading("可愛くてごめん", "Kawaii Gomen"))
        // Ours in its own script already: nothing to read.
        assertFalse(CatalogueTitles.sameReading("可愛くてごめん", "可愛くてごめん"))
    }

    @Test
    fun `NetEase's mora-spaced romaji is cut where the reader's words end`() {
        assertEquals("subarashiki sekai ni", NetEaseRomaji.rejoin("su ba ra shi ki se ka i ni", "subarashiki sekai ni"))
        // Where the two read the line differently, NetEase's letters stand as they were.
        assertEquals("u sse e wa", NetEaseRomaji.rejoin("u sse e wa", "kaibutsu"))
    }

    @Test
    fun `native names come from the song's home storefront`() = runBlocking {
        assumeTrue(System.getenv("RUN_ALL_SOURCES_TEST") == "1")
        val names = NativeNames(OkHttpClient(), Gson())
        assertEquals("新時代" to "Ado", names.of(LyricsLookupRequest("Ado", "New Genesis", "", 227)))
        // Already in its own script: nothing to look up.
        assertEquals(null, names.of(LyricsLookupRequest("Ado", "新時代", "", 227)))
    }
}
