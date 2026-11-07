package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.network.model.NetworkErrorException
import com.tx24.spicyplayer.network.model.NotFoundException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteLyricsSourceTest {
    private val request = LyricsLookupRequest("Artist", "Title", "Album", 180)

    @Test
    fun `uses default priority order`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("fallback", 100, calls, ProviderResult.Hit(plain("fallback"))),
            provider("preferred", 10, calls, ProviderResult.Hit(wordTtml("preferred"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("preferred", result.selection.source.id)
        assertEquals(listOf("preferred"), calls)
        assertEquals(RemoteLyricsQuality.WORD_SYNCED, result.selection.quality)
    }

    @Test
    fun `a source chosen for one song is asked alone and wins over a better answer already known`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("preferred", 10, calls, ProviderResult.Hit(wordTtml("preferred"))),
            provider("chosen", 100, calls, ProviderResult.Hit(plain("chosen"))),
        )
        val known = mutableMapOf<String, ProviderResult>()
        source.resolveLyrics(request, known = known)
        assertEquals(listOf("preferred"), calls)
        // The way a song's chosen source is asked: every other source switched off for the walk.
        val only = RemoteLyricsPolicy(disabledSourceIds = setOf("preferred"))
        val result = source.resolveLyrics(request, only, known = known) as RemoteLyricsResolution.Found
        assertEquals("chosen", result.selection.source.id)
        assertEquals(listOf("preferred", "chosen"), calls)
    }

    @Test
    fun `Spicy Lyrics relaying Apple Music ranks in Apple Music's place`() = runBlocking {
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(from("Apple Music"))),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("amll_ttml_db", result.selection.source.id)
    }

    @Test
    fun `Spicy Lyrics relaying Spotify ranks in Spotify's place`() = runBlocking {
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(from("Spotify"))),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("amll_ttml_db", result.selection.source.id)
    }

    @Test
    fun `relayed Spotify lyrics beat a source ordered below Spotify`() = runBlocking {
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(from("Spotify"))),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
        )
        val order = RemoteLyricsPolicy(sourceOrder = listOf(LyricsOrigins.SPOTIFY, LyricsOrigins.AMLL))

        val result = source.resolveLyrics(request, order) as RemoteLyricsResolution.Found

        assertEquals("spicy_lyrics", result.selection.source.id)
    }

    @Test
    fun `LRCMux relaying KuGou ranks in KuGou's place`() = runBlocking {
        val source = source(
            provider("kugou", 1, result = ProviderResult.Miss),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
            provider("lrcmux", 3, result = ProviderResult.Hit(from("KuGou", by = "LRCMux"))),
        )
        val order = RemoteLyricsPolicy(sourceOrder = listOf(LyricsOrigins.KUGOU, LyricsOrigins.AMLL))

        val result = source.resolveLyrics(request, order) as RemoteLyricsResolution.Found

        assertEquals("lrcmux", result.selection.source.id)
    }

    @Test
    fun `an answer from a switched-off source doesn't count`() = runBlocking {
        val source = source(
            provider("lrcmux", 1, result = ProviderResult.Hit(from("KuGou", by = "LRCMux"))),
            provider("kugou", 2, result = ProviderResult.Miss),
            provider("amll_ttml_db", 3, result = ProviderResult.Hit(synced("amll"))),
        )
        val policy = RemoteLyricsPolicy(
            sourceOrder = listOf(LyricsOrigins.KUGOU, LyricsOrigins.AMLL),
            disabledSourceIds = setOf(LyricsOrigins.KUGOU),
        )

        val result = source.resolveLyrics(request, policy) as RemoteLyricsResolution.Found

        assertEquals("amll_ttml_db", result.selection.source.id)
        val lrcmux = result.attempts.single { it.sourceId == "lrcmux" }
        assertEquals(ProviderAttemptOutcome.FILTERED, lrcmux.outcome)
        assertEquals("Switched off for KuGou", lrcmux.message)
        // KuGou is off, so Kugou is not asked at all.
        assertEquals(ProviderAttemptOutcome.DISABLED, result.attempts.single { it.sourceId == "kugou" }.outcome)
    }

    @Test
    fun `a provider switched off for one source still counts for another`() = runBlocking {
        val calls = mutableListOf<String>()
        // Spicy Lyrics is off for Apple Music but on for its community syncs.
        val policy = RemoteLyricsPolicy(pairs = mapOf(SourcePair(LyricsOrigins.APPLE_MUSIC, "spicy_lyrics").key to false))
        fun lookup(answer: RemoteLyricsPayload) = runBlocking {
            source(
                provider("spicy_lyrics", 1, calls, ProviderResult.Hit(answer)),
                provider("lrclib", 2, calls, ProviderResult.Hit(synced("lrclib"))),
            ).resolveLyrics(request, policy) as RemoteLyricsResolution.Found
        }

        val apple = lookup(from("Apple Music"))
        assertEquals("lrclib", apple.selection.source.id)
        assertEquals(ProviderAttemptOutcome.FILTERED, apple.attempts.single { it.sourceId == "spicy_lyrics" }.outcome)
        assertEquals("Switched off for Apple Music", apple.attempts.single { it.sourceId == "spicy_lyrics" }.message)

        val community = lookup(from("Spicy Lyrics Community"))
        assertEquals("spicy_lyrics", community.selection.source.id)
    }

    @Test
    fun `an answer in a sync type its source has off doesn't count`() = runBlocking {
        val lineSynced = synced("apple").copy(attribution = LyricsAttribution("lrc.red", originName = "Apple Music"))
        val source = source(
            provider("lrc_red", 1, result = ProviderResult.Hit(lineSynced)),
            provider("lrclib", 2, result = ProviderResult.Hit(plain("lrclib"))),
        )
        val wordOnly = RemoteLyricsPolicy(
            syncTypesOff = mapOf(LyricsOrigins.APPLE_MUSIC to setOf(RemoteLyricsQuality.LINE_SYNCED, RemoteLyricsQuality.PLAIN)),
        )

        val result = source.resolveLyrics(request, wordOnly) as RemoteLyricsResolution.Found

        assertEquals("lrclib", result.selection.source.id)
        val lrcRed = result.attempts.single { it.sourceId == "lrc_red" }
        assertEquals(ProviderAttemptOutcome.FILTERED, lrcRed.outcome)
        assertEquals(RemoteLyricsQuality.LINE_SYNCED, lrcRed.quality)
        assertEquals("Line sync is off for Apple Music", lrcRed.message)
    }

    @Test
    fun `lrc red's own syncs rank as lrc red AI syncs, after LRCLIB by default`() = runBlocking {
        val ai = wordTtml("ai").copy(attribution = LyricsAttribution("lrc.red", originName = LyricsOrigins.LRC_RED_AI_NAME))
        val source = source(
            provider("lrc_red", 1, result = ProviderResult.Hit(ai)),
            provider("lrclib", 2, result = ProviderResult.Hit(wordTtml("lrclib"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("lrclib", result.selection.source.id)
    }

    @Test
    fun `a provider serving several sources is asked once`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("spicy_lyrics", 1, calls, ProviderResult.Hit(synced("spotify").copy(attribution = LyricsAttribution("Spicy Lyrics", originName = "Spotify")))),
            provider("lrcmux", 2, calls, ProviderResult.Hit(from("LRCLIB", by = "LRCMux"))),
            provider("lrclib", 3, calls, ProviderResult.Miss),
            provider("genius", 4, calls, ProviderResult.Miss),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals(listOf("genius", "lrclib", "lrcmux", "spicy_lyrics"), calls.sorted())
        assertEquals(1, result.attempts.count { it.sourceId == "lrcmux" })
        assertEquals(1, result.attempts.count { it.sourceId == "spicy_lyrics" })
    }

    @Test
    fun `an answer from a source the provider doesn't list ranks after every source but counts`() = runBlocking {
        val source = source(
            provider("lrcmux", 1, result = ProviderResult.Hit(from("Somewhere new", by = "LRCMux"))),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
        )
        assertEquals("amll_ttml_db", (source.resolveLyrics(request) as RemoteLyricsResolution.Found).selection.source.id)

        val alone = source(provider("lrcmux", 1, result = ProviderResult.Hit(from("Somewhere new", by = "LRCMux"))))
        assertEquals("lrcmux", (alone.resolveLyrics(request) as RemoteLyricsResolution.Found).selection.source.id)
    }

    @Test
    fun `Spicy Lyrics community syncs keep its place`() = runBlocking {
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(from("Spicy Lyrics Community"))),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("spicy_lyrics", result.selection.source.id)
    }

    @Test
    fun `RMM Revival's Apple Music copy ranks with Apple Music, behind Spicy Lyrics' copy`() = runBlocking {
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(from("Apple Music", by = "Spicy Lyrics"))),
            provider("amll_ttml_db", 2, result = ProviderResult.Miss),
            provider("rmm_revival", 3, result = ProviderResult.Hit(from("Apple Music", by = "RMM Revival"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("spicy_lyrics", result.selection.source.id)
    }

    @Test
    fun `RMM Revival's copy of a Spicy Lyrics sync ranks in Spicy Lyrics' place`() = runBlocking {
        val slowRmm = object : RemoteLyricsProvider {
            override val descriptor = descriptor("rmm_revival", 3)
            override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                kotlinx.coroutines.delay(100)
                return ProviderResult.Hit(from("Spicy Lyrics Community", by = "RMM Revival"))
            }
        }
        // No Spotify match for Spicy Lyrics; AMLL answers first, but the relay is waited for.
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.NeedsMatch),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
            slowRmm,
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("rmm_revival", result.selection.source.id)
    }

    @Test
    fun `a relay is not waited for once Spicy Lyrics has answered`() = runBlocking {
        val source = source(
            provider("spicy_lyrics", 1, result = ProviderResult.Hit(from("Apple Music"))),
            provider("amll_ttml_db", 2, result = ProviderResult.Hit(wordTtml("amll"))),
            hanging("rmm_revival", 3),
        )

        val result = withTimeout(2_000) { source.resolveLyrics(request) } as RemoteLyricsResolution.Found

        assertEquals("amll_ttml_db", result.selection.source.id)
    }

    @Test
    fun `explicit source order overrides defaults`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("a", 10, calls, ProviderResult.Hit(wordTtml("a"))),
            provider("b", 100, calls, ProviderResult.Hit(wordTtml("b"))),
        )

        val result = source.resolveLyrics(
            request,
            RemoteLyricsPolicy(sourceOrder = listOf("b", "a")),
        ) as RemoteLyricsResolution.Found

        assertEquals("b", result.selection.source.id)
        assertEquals(listOf("b"), calls)
    }

    @Test
    fun `disabled provider is recorded and never called`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("disabled", 1, calls, ProviderResult.Hit(wordTtml("bad"))),
            provider("enabled", 2, calls, ProviderResult.Hit(wordTtml("good"))),
        )

        val result = source.resolveLyrics(
            request,
            RemoteLyricsPolicy(disabledSourceIds = setOf("disabled")),
        ) as RemoteLyricsResolution.Found

        assertEquals("enabled", result.selection.source.id)
        assertEquals(listOf("enabled"), calls)
        assertEquals(ProviderAttemptOutcome.DISABLED, result.attempts.first().outcome)
    }

    @Test
    fun `falls back after provider miss`() = runBlocking {
        val source = source(
            provider("missing", 10, result = ProviderResult.Miss),
            provider("found", 100, result = ProviderResult.Hit(synced("line"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("found", result.selection.source.id)
        assertEquals(
            listOf(ProviderAttemptOutcome.MISS, ProviderAttemptOutcome.HIT),
            result.attempts.map(ProviderAttempt::outcome),
        )
    }

    @Test
    fun `later richer result replaces earlier plain result`() = runBlocking {
        val source = source(
            provider("plain", 10, result = ProviderResult.Hit(plain("words"))),
            provider("line", 20, result = ProviderResult.Hit(synced("line"))),
            provider("word", 30, result = ProviderResult.Hit(wordTtml("ttml"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("word", result.selection.source.id)
        assertEquals(RemoteLyricsQuality.WORD_SYNCED, result.selection.quality)
        assertEquals(3, result.attempts.size)
    }

    @Test
    fun `equal quality keeps earlier source`() = runBlocking {
        val source = source(
            provider("first", 10, result = ProviderResult.Hit(synced("first"))),
            provider("second", 20, result = ProviderResult.Hit(synced("second"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("first", result.selection.source.id)
        assertEquals("[00:01.00]first", result.selection.payload.syncedLyrics)
    }

    @Test
    fun `word synced result stops later requests`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("word", 10, calls, ProviderResult.Hit(wordTtml("word"))),
            provider("unused", 20, calls, ProviderResult.Hit(wordTtml("unused"))),
        )

        source.resolveLyrics(request)

        assertEquals(listOf("word"), calls)
    }

    @Test
    fun `empty hit is a miss and fallback continues`() = runBlocking {
        val source = source(
            provider("broken", 10, result = ProviderResult.Hit(RemoteLyricsPayload())),
            provider("found", 20, result = ProviderResult.Hit(plain("lyrics"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("found", result.selection.source.id)
        assertEquals(ProviderAttemptOutcome.MISS, result.attempts.first().outcome)
    }

    @Test
    fun `malformed ttml falls back to valid synced representation in same hit`() = runBlocking {
        val source = source(
            provider(
                "mixed",
                10,
                result = ProviderResult.Hit(
                    RemoteLyricsPayload(
                        ttmlLyrics = "not xml",
                        syncedLyrics = "[00:01.00]usable line",
                    )
                ),
            )
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("mixed", result.selection.source.id)
        assertEquals(RemoteLyricsQuality.LINE_SYNCED, result.selection.quality)
    }

    @Test
    fun `static ttml is measured as plain and does not block richer fallback`() = runBlocking {
        val source = source(
            provider("static", 10, result = ProviderResult.Hit(staticTtml("plain"))),
            provider("line", 20, result = ProviderResult.Hit(synced("[00:01.00]line"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("line", result.selection.source.id)
        assertEquals(RemoteLyricsQuality.LINE_SYNCED, result.selection.quality)
    }

    @Test
    fun `empty ttml is rejected instead of stopping source chain`() = runBlocking {
        val source = source(
            provider("empty-ttml", 10, result = ProviderResult.Hit(ttml("<tt><body><div /></body></tt>"))),
            provider("plain", 20, result = ProviderResult.Hit(plain("fallback"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("plain", result.selection.source.id)
        assertEquals(ProviderAttemptOutcome.MALFORMED_HIT, result.attempts.first().outcome)
    }

    @Test
    fun `network failure does not hide later success`() = runBlocking {
        val source = source(
            provider(
                "offline",
                10,
                result = ProviderResult.Unavailable(ProviderFailureCategory.NETWORK),
            ),
            provider("found", 20, result = ProviderResult.Hit(plain("lyrics"))),
        )

        val result = source.resolveLyrics(request) as RemoteLyricsResolution.Found

        assertEquals("found", result.selection.source.id)
    }

    @Test
    fun `instrumental notes and empty records resolve not found, not unavailable`() = runBlocking {
        val result = source(
            provider("note", 10, result = ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = "[00:00.00] ♪ Instrumental ♪"))),
            provider("bracket", 20, result = ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = "[00:01.00][Instrumental]\n[01:00.00]♪"))),
            provider("empty", 30, result = ProviderResult.Hit(RemoteLyricsPayload())),
        ).resolveLyrics(request)

        assertTrue(result is RemoteLyricsResolution.NotFound)
    }

    @Test
    fun `all authoritative misses resolve not found`() = runBlocking {
        val result = source(
            provider("one", 10, result = ProviderResult.Miss),
            provider("two", 20, result = ProviderResult.Miss),
        ).resolveLyrics(request)

        assertTrue(result is RemoteLyricsResolution.NotFound)
    }

    @Test
    fun `unreachable source without hit resolves unavailable`() = runBlocking {
        val result = source(
            provider("missing", 10, result = ProviderResult.Miss),
            provider(
                "offline",
                20,
                result = ProviderResult.Unavailable(ProviderFailureCategory.NETWORK),
            ),
        ).resolveLyrics(request)

        assertTrue(result is RemoteLyricsResolution.Unavailable)
    }

    @Test
    fun `cooldown is remembered and suppresses later call`() = runBlocking {
        val now = Instant.parse("2026-09-21T12:00:00Z")
        val retryAt = now.plusSeconds(120)
        val calls = mutableListOf<String>()
        val cooling = provider(
            "limited",
            10,
            calls,
            ProviderResult.CoolingDown(retryAt, "HTTP 429: window exhausted"),
        )
        val source = source(cooling)

        val first = source.resolveLyrics(request, now = now)
        val second = source.resolveLyrics(request, now = now.plusSeconds(30))

        assertTrue(first is RemoteLyricsResolution.Unavailable)
        assertTrue(second is RemoteLyricsResolution.Unavailable)
        assertEquals(listOf("limited"), calls)
        assertEquals(
            ProviderAttemptOutcome.COOLING_DOWN,
            second.attempts.single().outcome,
        )
        // Why it rests is said both when refused and while it rests, not asked.
        assertEquals("HTTP 429: window exhausted", first.attempts.single().message)
        assertEquals("HTTP 429: window exhausted", second.attempts.single().message)
    }

    @Test
    fun `expired cooldown permits another call`() = runBlocking {
        val now = Instant.parse("2026-09-21T12:00:00Z")
        val calls = mutableListOf<String>()
        val tracker = ProviderCooldownTracker()
        tracker.record("source", now.plusSeconds(10))
        val source = RemoteLyricsSource(
            setOf(provider("source", 10, calls, ProviderResult.Miss)),
            tracker,
        )

        val result = source.resolveLyrics(request, now = now.plusSeconds(11))

        assertTrue(result is RemoteLyricsResolution.NotFound)
        assertEquals(listOf("source"), calls)
    }

    @Test
    fun `cancellation is never converted into provider failure`() = runBlocking {
        val cancelling = object : RemoteLyricsProvider {
            override val descriptor = descriptor("cancel", 10)

            override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                throw CancellationException("track changed")
            }
        }

        try {
            source(cancelling).resolveLyrics(request)
            fail("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("track changed", expected.message)
        }
    }

    @Test
    fun `legacy not found exception remains isolated during migration`() = runBlocking {
        val legacy = throwingProvider("legacy", 10, NotFoundException("missing"))
        val result = source(
            legacy,
            provider("found", 20, result = ProviderResult.Hit(plain("lyrics"))),
        ).resolveLyrics(request)

        assertTrue(result is RemoteLyricsResolution.Found)
    }

    @Test
    fun `slow lead does not hold back the rest`() = runBlocking {
        // The lead only answers once "word" has been asked, so this deadlocks unless the rest
        // fan out while the lead is still out.
        val wordAsked = CompletableDeferred<Unit>()
        val source = source(
            object : RemoteLyricsProvider {
                override val descriptor = descriptor("lead", 10)
                override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                    wordAsked.await()
                    return ProviderResult.Miss
                }
            },
            object : RemoteLyricsProvider {
                override val descriptor = descriptor("word", 20)
                override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                    wordAsked.complete(Unit)
                    return ProviderResult.Hit(wordTtml("word"))
                }
            },
        )

        val result = withTimeout(5_000) { source.resolveLyrics(request) } as RemoteLyricsResolution.Found

        assertEquals("word", result.selection.source.id)
    }

    @Test
    fun `best answer so far is reported as each source lands`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = source(
            provider("miss", 10, result = ProviderResult.Miss),
            provider("line", 20, result = ProviderResult.Hit(synced("[00:01.00]line"))),
            object : RemoteLyricsProvider {
                override val descriptor = descriptor("word", 30)
                override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                    gate.await()
                    return ProviderResult.Hit(wordTtml("word"))
                }
            },
        )
        val shown = mutableListOf<String>()

        val result = source.resolveLyrics(request) { update ->
            (update as? RemoteLyricsResolution.Found)?.selection?.source?.id?.let { id ->
                if (shown.lastOrNull() != id) shown += id
            }
            gate.complete(Unit)
        } as RemoteLyricsResolution.Found

        assertEquals(listOf("line", "word"), shown)
        assertEquals("word", result.selection.source.id)
    }

    @Test
    fun `known results are reused instead of asked again`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = source(
            provider("one", 10, calls, ProviderResult.Miss),
            provider("two", 20, calls, ProviderResult.Hit(synced("[00:01.00]line"))),
        )
        val known = mutableMapOf<String, ProviderResult>()

        source.resolveLyrics(request, known = known)
        val reordered = source.resolveLyrics(request, RemoteLyricsPolicy(sourceOrder = listOf("two", "one")), known = known)

        assertEquals(listOf("one", "two"), calls)
        assertEquals("two", (reordered as RemoteLyricsResolution.Found).selection.source.id)
    }

    @Test
    fun `word hit cancels sources ranked below it`() = runBlocking {
        val source = source(
            provider("miss", 10, result = ProviderResult.Miss),
            provider("word", 20, result = ProviderResult.Hit(wordTtml("word"))),
            hanging("slow", 30),
        )

        val result = withTimeout(5_000) { source.resolveLyrics(request) } as RemoteLyricsResolution.Found

        assertEquals("word", result.selection.source.id)
        assertEquals(ProviderAttemptOutcome.SKIPPED, result.attempts.last().outcome)
    }

    @Test(expected = NetworkErrorException::class)
    fun `compatibility getLyrics reports unavailable`() = runBlocking {
        source(
            provider(
                "offline",
                10,
                result = ProviderResult.Unavailable(ProviderFailureCategory.NETWORK),
            )
        ).getLyrics(request)
        Unit
    }

    @Test(expected = NotFoundException::class)
    fun `compatibility getLyrics reports not found`() = runBlocking {
        source(provider("missing", 10, result = ProviderResult.Miss)).getLyrics(request)
        Unit
    }

    @Test
    fun `a Chinese catalogue that misses is asked again under the native names`() = runBlocking {
        val asked = mutableListOf<String>()
        fun catalogue(id: String) = object : RemoteLyricsProvider {
            override val descriptor = descriptor(id, 10)
            override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                asked += "$id:${request.title}"
                return if (request.title == "新時代") ProviderResult.Hit(synced("新時代はこの未来だ")) else ProviderResult.Miss
            }
        }
        val source = RemoteLyricsSource(
            setOf(catalogue("kugou"), catalogue("lrclib")), ProviderCooldownTracker(),
            nativeNames = { if (it.title == "New Genesis") "新時代" to "Ado" else null },
        )
        val result = source.resolveLyrics(LyricsLookupRequest("Ado", "New Genesis", "", 227)) as RemoteLyricsResolution.Found
        assertEquals("kugou", result.selection.source.id)
        // Only the catalogues that file songs under native names are asked twice.
        assertEquals(listOf("kugou:New Genesis", "kugou:新時代", "lrclib:New Genesis"), asked.sorted())
    }

    private fun source(vararg providers: RemoteLyricsProvider) = RemoteLyricsSource(
        providers.toSet(),
        ProviderCooldownTracker(),
    )

    private fun provider(
        id: String,
        priority: Int,
        calls: MutableList<String>? = null,
        result: ProviderResult,
    ) = object : RemoteLyricsProvider {
        override val descriptor = descriptor(id, priority)

        override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
            calls?.add(id)
            return result
        }
    }

    /** Word-synced lyrics [by] a relay, from [origin]. */
    private fun from(origin: String, by: String = "Spicy Lyrics") =
        wordTtml(origin).copy(attribution = LyricsAttribution(by, originName = origin))

    private fun hanging(id: String, priority: Int) = object : RemoteLyricsProvider {
        override val descriptor = descriptor(id, priority)
        override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = awaitCancellation()
    }

    private fun throwingProvider(id: String, priority: Int, error: Exception) =
        object : RemoteLyricsProvider {
            override val descriptor = descriptor(id, priority)

            override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = throw error
        }

    private fun descriptor(id: String, priority: Int) = LyricsSourceDescriptor(
        id = id,
        displayName = id,
        defaultPriority = priority,
        capabilities = setOf(LyricsCapability.PLAIN_TEXT),
    )

    private fun plain(value: String) = RemoteLyricsPayload(plainLyrics = value)
    private fun synced(value: String) = RemoteLyricsPayload(syncedLyrics = "[00:01.00]$value")
    private fun ttml(value: String) = RemoteLyricsPayload(ttmlLyrics = value)
    private fun wordTtml(value: String) = ttml(
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word"><body><div><p begin="1s" end="3s"><span begin="1s" end="2s">$value</span></p></div></body></tt>"""
    )
    private fun staticTtml(value: String) = ttml(
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p>$value</p></div></body></tt>"""
    )
}
