package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.network.data.ProviderAttempt
import com.tx24.spicyplayer.network.data.ProviderAttemptOutcome
import com.tx24.spicyplayer.network.data.ProviderFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsNoticesTest {
    private fun notice(vararg attempts: ProviderAttempt) = LyricsNotices.noLyrics(attempts.toList()) { it.uppercase() }

    @Test fun oneFailingSourceAmongMissesIsStillNoLyrics() {
        val error = notice(
            ProviderAttempt("lrclib", ProviderAttemptOutcome.MISS),
            ProviderAttempt("netease", ProviderAttemptOutcome.MISS),
            ProviderAttempt("musixmatch", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.SERVER, message = "Musixmatch HTTP 503"),
        )
        assertEquals(NoticeKind.NoLyrics, error.kind)
        assertEquals("2 sources had none. Couldn't check MUSIXMATCH (HTTP 503)", error.detail)
    }

    @Test fun anOfflinePhoneIsOfflineWhateverTheSourcesSay() {
        // Offline for a while, the sources back off and answer "cooling down"; a local one has none.
        val error = LyricsNotices.noLyrics(
            listOf(
                ProviderAttempt("local", ProviderAttemptOutcome.MISS),
                ProviderAttempt("spicy_lyrics", ProviderAttemptOutcome.COOLING_DOWN),
                ProviderAttempt("lrclib", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.NETWORK),
            ),
            offline = true,
        ) { it.uppercase() }
        assertEquals(NoticeKind.Offline, error.kind)
    }

    @Test fun anOfflinePhoneWhoseSourcesAllAnsweredIsNoLyrics() {
        val error = LyricsNotices.noLyrics(listOf(ProviderAttempt("local", ProviderAttemptOutcome.MISS)), offline = true) { it }
        assertEquals(NoticeKind.NoLyrics, error.kind)
    }

    @Test fun everySourceOfflineAsksToGoOnline() {
        val error = notice(
            ProviderAttempt("lrclib", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.NETWORK),
            ProviderAttempt("spicy_lyrics", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.TIMEOUT),
        )
        assertEquals(NoticeKind.Offline, error.kind)
        assertEquals("Couldn't reach LRCLIB (no connection), SPICY_LYRICS (timed out)", error.detail)
    }

    @Test fun aSourceBackingOffDoesntHideThatTheRestCouldntConnect() {
        // Trying again offline: the source that failed first is cooling down, with no reason of its own.
        val error = notice(
            ProviderAttempt("spicy_lyrics", ProviderAttemptOutcome.COOLING_DOWN),
            ProviderAttempt("lrclib", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.NETWORK),
        )
        assertEquals(NoticeKind.Offline, error.kind)
        // Only sources backing off say nothing about the connection.
        assertEquals(NoticeKind.RateLimited, notice(ProviderAttempt("spicy_lyrics", ProviderAttemptOutcome.COOLING_DOWN)).kind)
    }

    @Test fun skippedAndDisabledSourcesDontCount() {
        val error = notice(
            ProviderAttempt("lrclib", ProviderAttemptOutcome.MISS),
            ProviderAttempt("qq", ProviderAttemptOutcome.DISABLED),
        )
        assertEquals("Checked 1 source, none had it", error.detail)
    }

    @Test fun blendsDontCountAsSources() {
        val error = notice(
            ProviderAttempt("netease", ProviderAttemptOutcome.MISS),
            ProviderAttempt("blend_netease", ProviderAttemptOutcome.MISS),
        )
        assertEquals("Checked 1 source, none had it", error.detail)
    }

    @Test fun noneHadItIsNoLyrics() {
        val error = notice(ProviderAttempt("lrclib", ProviderAttemptOutcome.MISS))
        assertEquals(NoticeKind.NoLyrics, error.kind)
        assertNull(error.code)
    }

    @Test fun noLyricsDoesntAskForAReport() {
        // A song with no lyrics isn't a bug: no "Copy details" or Discord, and the text doesn't ask for them.
        assertFalse(NoticeKind.NoLyrics.report)
        assertFalse(NoticeKind.NoLyrics.body.contains("send them to us"))
        assertFalse(NoticeKind.NoLyrics.body.contains("details"))
    }

    @Test fun nothingAskedMeansNoSources() {
        assertEquals(NoticeKind.NoSources, notice(ProviderAttempt("lrclib", ProviderAttemptOutcome.DISABLED)).kind)
    }

    @Test fun everySourceCoolingDownIsRateLimited() {
        val error = notice(
            ProviderAttempt("lrclib", ProviderAttemptOutcome.COOLING_DOWN),
            ProviderAttempt("musixmatch", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.CLIENT_REQUEST, message = "HTTP 429"),
        )
        assertEquals(NoticeKind.RateLimited, error.kind)
        assertEquals("429", error.code)
    }

    @Test fun mostlyServerErrorsIsServerError() {
        val error = notice(
            ProviderAttempt("lrclib", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.SERVER, message = "HTTP 503"),
            ProviderAttempt("netease", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.SERVER, message = "HTTP 503"),
            ProviderAttempt("musixmatch", ProviderAttemptOutcome.COOLING_DOWN),
        )
        assertEquals(NoticeKind.ServerError, error.kind)
        assertEquals("503", error.code)
        assertEquals("LRCLIB (HTTP 503), NETEASE (HTTP 503), MUSIXMATCH (rate limited)", error.detail)
    }

    @Test fun mixedFailuresAreUnavailable() {
        val error = notice(
            ProviderAttempt("lrclib", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.AUTHENTICATION),
            ProviderAttempt("netease", ProviderAttemptOutcome.MALFORMED_HIT),
            ProviderAttempt("musixmatch", ProviderAttemptOutcome.UNAVAILABLE, failureCategory = ProviderFailureCategory.SERVER),
        )
        assertEquals(NoticeKind.Unavailable, error.kind)
    }

    @Test fun lookupAndRenderFailuresHaveTheirOwnKinds() {
        assertEquals(NoticeKind.UnknownTrack, LyricsNotices.missingMetadata.kind)
        val lookup = LyricsNotices.lookupFailed(IllegalStateException("boom"))
        assertEquals(NoticeKind.Unknown, lookup.kind)
        assertEquals("IllegalStateException", lookup.code)
        assertEquals(NoticeKind.RenderFailed, LyricsNotices.renderFailed("LRCLIB", IllegalArgumentException()).kind)
    }
}
