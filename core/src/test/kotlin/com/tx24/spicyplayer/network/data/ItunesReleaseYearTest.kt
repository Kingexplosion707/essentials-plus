package com.tx24.spicyplayer.network.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItunesReleaseYearTest {
    private val songs = listOf(
        ItunesReleaseYear.Song("Numb (Live In Texas)", "Linkin Park", "Live In Texas", 190_000, "2003"),
        ItunesReleaseYear.Song("Numb", "Linkin Park", "Meteora 20th Anniversary Edition", 185_000, "2023"),
        ItunesReleaseYear.Song("Numb", "Linkin Park", "Meteora (Bonus Edition)", 185_000, "2003"),
        ItunesReleaseYear.Song("Numb", "Someone Else", "Covers", 185_000, "1999"),
    )

    @Test fun sameRecordWins() {
        assertEquals("2023", ItunesReleaseYear.pick(songs, "Numb", "Linkin Park", "Meteora 20th Anniversary Edition", 185_000))
    }

    @Test fun elseTheEarliestOfTheSameLength() {
        assertEquals("2003", ItunesReleaseYear.pick(songs, "Numb", "Linkin Park", "", 185_000))
    }

    @Test fun anotherArtistsSongDoesNotCount() {
        assertNull(ItunesReleaseYear.pick(songs, "Numb", "Nobody", "", 185_000))
    }

    @Test fun parsesTheSearchResponse() {
        val json = """{"resultCount":1,"results":[{"wrapperType":"track","trackName":"Numb","artistName":"Linkin Park",
            "collectionName":"Meteora","trackTimeMillis":185587,"releaseDate":"2003-03-25T08:00:00Z"}]}"""
        val parsed = ItunesReleaseYear.parse(json)
        assertEquals(listOf(ItunesReleaseYear.Song("Numb", "Linkin Park", "Meteora", 185_587, "2003")), parsed)
    }
}
