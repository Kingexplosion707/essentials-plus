package com.tx24.spicyplayer.network.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTrackMatcherTest {
    private val source = LocalTrackMetadata(
        title = "Beyoncé & Jay-Z - Déjà Vu",
        artist = "Beyoncé feat. Jay-Z",
        album = "B'Day",
        durationMs = 239_000,
    )

    @Test
    fun `normalizes unicode punctuation and featuring artists`() {
        assertEquals("beyonce and jay z deja vu", SpotifyTrackMatcher.normalize(source.title))
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Déjà Vu"),
            listOf(candidate(id = "correct", title = "Deja Vu")),
        )

        assertTrue(result is SpotifyTrackResolution.Matched)
        assertEquals("correct", (result as SpotifyTrackResolution.Matched).track.candidate.id)
    }

    @Test
    fun `artists joined with an ampersand match separately listed artists`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu", artist = "Beyoncé & JAY-Z"),
            listOf(candidate(id = "correct", title = "Deja Vu")),
        )

        assertTrue(result is SpotifyTrackResolution.Matched)
    }

    @Test
    fun `rejects a live version for a studio source`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu"),
            listOf(candidate(id = "live", title = "Deja Vu - Live", durationMs = 240_000)),
        )

        assertEquals(SpotifyTrackResolution.NotFound, result)
    }

    @Test
    fun `rejects a candidate with materially different duration`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu"),
            listOf(candidate(id = "short", title = "Deja Vu", durationMs = 210_000)),
        )

        assertEquals(SpotifyTrackResolution.NotFound, result)
    }

    @Test
    fun `tied copies of the same recording become alternates`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu", album = ""),
            listOf(
                candidate(id = "a", title = "Deja Vu", durationMs = 239_000),
                candidate(id = "b", title = "Deja Vu", durationMs = 239_200),
            ),
        ) as SpotifyTrackResolution.Matched

        assertEquals("a", result.track.candidate.id)
        assertEquals(listOf("b"), result.alternates.map { it.candidate.id })
    }

    @Test
    fun `bilingual player title matches its spotify title`() {
        val medicine = SpotifyTrackCandidate("medicine", "Medicine", listOf("Sasuke Haraguchi"), "Medicine", 120_352)
        val result = SpotifyTrackMatcher.resolve(
            LocalTrackMetadata("イガク - Medicine", "Sasuke Haraguchi", "Medicine", 120_000),
            listOf(medicine),
        )

        assertEquals("medicine", (result as SpotifyTrackResolution.Matched).track.candidate.id)
    }

    @Test
    fun `language version does not tie with the original`() {
        val original = SpotifyTrackCandidate("jp", "メズマライザー (feat. 初音ミク&重音テト)", listOf("32ki", "Hatsune Miku", "重音テト"), "メズマライザー", 156_972)
        val result = SpotifyTrackMatcher.resolve(
            LocalTrackMetadata("メズマライザー - Mesmerizer (feat. Hatsune Miku&Kasane Teto)", "32ki, Hatsune Miku & Kasane Teto", "Mesmerizer", 157_000),
            listOf(
                original,
                original.copy(id = "compilation", album = "Critical Damage", durationMs = 156_760),
                SpotifyTrackCandidate("en", "Mesmerizer - Official English Version", listOf("32ki", "Will Stetson", "Rachie"), "Mesmerizer (Official English Version)", 157_013),
            ),
        ) as SpotifyTrackResolution.Matched

        assertEquals(setOf("jp", "compilation"), (listOf(result.track) + result.alternates).map { it.candidate.id }.toSet())
    }

    @Test
    fun `romaji title with an added featured artist is the same recording`() {
        val result = SpotifyTrackMatcher.resolve(
            LocalTrackMetadata("ヤラララ(YARARARA)", "AnythingBecomeMoe", "", 147_000),
            listOf(
                SpotifyTrackCandidate("a", "ヤラララ(YARARARA)", listOf("AnythingBecomeMoe"), "ヤラララ(YARARARA)", 147_307),
                SpotifyTrackCandidate("b", "YARARARA", listOf("AnythingBecomeMoe", "Kasane Teto"), "YARARARA", 147_195),
            ),
        ) as SpotifyTrackResolution.Matched

        assertEquals(setOf("a", "b"), (listOf(result.track) + result.alternates).map { it.candidate.id }.toSet())
    }

    @Test
    fun `unknown length from a queue entry still matches`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu", durationMs = 0),
            listOf(candidate(id = "only", title = "Deja Vu")),
        )

        assertEquals("only", (result as SpotifyTrackResolution.Matched).track.candidate.id)
    }

    @Test
    fun `shared featuring credit does not make two songs match`() {
        val result = SpotifyTrackMatcher.resolve(
            LocalTrackMetadata("Crew Love (feat. Drake)", "The Weeknd", "", 239_000),
            listOf(candidate(id = "other", title = "Wicked Games (feat. Drake)").copy(artists = listOf("The Weeknd"))),
        )

        assertEquals(SpotifyTrackResolution.NotFound, result)
    }

    @Test
    fun `near tie between clearly different recordings stays ambiguous`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu", album = ""),
            listOf(
                candidate(id = "a", title = "Deja Vu", durationMs = 239_000),
                candidate(id = "b", title = "Deja Vu", durationMs = 242_000),
            ),
        )

        assertTrue(result is SpotifyTrackResolution.Ambiguous)
    }

    @Test
    fun `deduplicates spotify ids before deciding ambiguity`() {
        val duplicate = candidate(id = "same", title = "Deja Vu")
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu"),
            listOf(duplicate, duplicate),
        )

        assertTrue(result is SpotifyTrackResolution.Matched)
    }

    @Test
    fun `uses unique exact album match to resolve duplicate releases`() {
        val result = SpotifyTrackMatcher.resolve(
            source.copy(title = "Deja Vu"),
            listOf(
                candidate(id = "single", title = "Deja Vu").copy(album = "Deja Vu"),
                candidate(id = "album", title = "Deja Vu", durationMs = 239_200),
                candidate(id = "compilation", title = "Deja Vu", durationMs = 238_900).copy(album = "Hits"),
            ),
        )

        assertEquals("album", (result as SpotifyTrackResolution.Matched).track.candidate.id)
    }

    private fun candidate(
        id: String,
        title: String,
        durationMs: Long = 239_000,
    ) = SpotifyTrackCandidate(
        id = id,
        title = title,
        artists = listOf("Beyoncé", "Jay-Z"),
        album = "B'Day",
        durationMs = durationMs,
    )
}
