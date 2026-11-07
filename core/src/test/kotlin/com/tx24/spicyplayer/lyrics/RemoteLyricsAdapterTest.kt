package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.network.data.LyricsAttribution
import com.tx24.spicyplayer.network.data.LyricsSourceDescriptor
import com.tx24.spicyplayer.network.data.RemoteLyricsPayload
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.RemoteLyricsSelection
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteLyricsAdapterTest {
    @Test fun keepsProviderAndTtmlSongwriterCredits() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><head><metadata><songwriters><songwriter>Ada</songwriter></songwriters></metadata></head><body><div><p begin="0s" end="2s">Hello</p></div></body></tt>"""
        val selection = RemoteLyricsSelection(
            LyricsSourceDescriptor("example", "Example", 1, emptySet()),
            RemoteLyricsPayload(
                ttmlLyrics = ttml,
                attribution = LyricsAttribution(providerName = "Example", songwriters = listOf("Ada", "Ben")),
            ),
            RemoteLyricsQuality.LINE_SYNCED,
        )

        val ready = RemoteLyricsAdapter.render(selection, 2_000)

        assertEquals("Example", ready.provider)
        assertEquals(listOf("Ada", "Ben"), ready.songwriters)
        assertEquals(LyricsType.Line, ready.lyricsType)
    }

    @Test fun keepsTtmlRomanization() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><translations><translation xml:lang="ja-Latn"><text for="L1"><span>ko</span><span>re</span></text></translation></translations></iTunesMetadata></metadata></head><body><div><p begin="0s" end="2s" itunes:key="L1"><span begin="0s" end="1s">こ</span><span begin="1s" end="2s">れ</span></p></div></body></tt>"""
        val selection = RemoteLyricsSelection(
            LyricsSourceDescriptor("example", "Example", 1, emptySet()),
            RemoteLyricsPayload(ttmlLyrics = ttml),
            RemoteLyricsQuality.WORD_SYNCED,
        )

        val words = RemoteLyricsAdapter.render(selection, 2_000).lines.single().words

        assertEquals(listOf("ko", "re"), words.map { it.romanized })
    }

    @Test fun fillsRomanizationGapsAndRomanizesPlainText() {
        val synced = RemoteLyricsSelection(
            LyricsSourceDescriptor("example", "Example", 1, emptySet()),
            RemoteLyricsPayload(syncedLyrics = "[00:01.00]사랑"),
            RemoteLyricsQuality.LINE_SYNCED,
        )
        val plain = synced.copy(payload = RemoteLyricsPayload(plainLyrics = "사랑\nhello"), quality = RemoteLyricsQuality.PLAIN)

        assertEquals("sarang", RemoteLyricsAdapter.render(synced, 5_000).lines.single().words.joinToString("") { it.romanized.orEmpty() })
        val static = RemoteLyricsAdapter.render(plain, 5_000)
        assertEquals(LyricsType.Static, static.lyricsType)
        assertEquals(listOf("sarang", null), static.lines.map { line -> line.words.joinToString("") { it.romanized.orEmpty() }.ifEmpty { null } })
    }

    @Test fun lineTimedTextIsSplitSoItCanWrap() {
        val lrc = RemoteLyricsSelection(
            LyricsSourceDescriptor("example", "Example", 1, emptySet()),
            RemoteLyricsPayload(syncedLyrics = "[00:01.00]a long line here\n[00:03.00]君と歩いた"),
            RemoteLyricsQuality.LINE_SYNCED,
        )

        val lines = RemoteLyricsAdapter.render(lrc, 5_000).lines

        assertEquals(listOf("a", "long", "line", "here"), lines[0].words.map { it.text })
        assertEquals(listOf(false, false, false, false), lines[0].words.map { it.attached })
        assertEquals(listOf("君", "と", "歩", "い", "た"), lines[1].words.map { it.text })
        assertEquals(listOf(false, true, true, true, true), lines[1].words.map { it.attached })
    }

    @Test fun aLineTimedSourceRomanizationSurvivesTheSplit() {
        val head = """<iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><transliterations><transliteration>""" +
            """<text for="L1"><span begin="0s" end="2s">kimi to</span> <span begin="2s" end="4s">aruita</span></text></transliteration></transliterations></iTunesMetadata>"""
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line"><head><metadata>$head</metadata></head>""" +
            """<body><div><p begin="0s" end="4s" itunes:key="L1">君と歩いた</p></div></body></tt>"""
        val selection = RemoteLyricsSelection(
            LyricsSourceDescriptor("example", "Example", 1, emptySet()),
            RemoteLyricsPayload(ttmlLyrics = ttml),
            RemoteLyricsQuality.LINE_SYNCED,
        )

        val words = RemoteLyricsAdapter.render(selection, 4_000).lines.single().words

        // The original's pieces, blank when romanized, then the romanization's, blank otherwise.
        assertEquals(listOf("君", "と", "歩", "い", "た", "", "", ""), words.map { it.text })
        assertEquals(listOf("", "", "", "", "", "kimi", "to", "aruita"), words.map { it.romanized })
        assertEquals(listOf(false, true, true, true, true, false, false, false), words.map { it.attached })
    }

    @Test fun lineTimedLinesInWordSyncedLyricsAreFlaggedAndKeepTheirRomajiSpaces() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>""" +
            """<p begin="0s" end="2s"><span begin="0s" end="1s">I</span> <span begin="1s" end="2s">know</span></p>""" +
            """<p begin="2s" end="6s"><span begin="2s" end="6s">今日は空が青い</span></p>""" +
            "</div></body></tt>"
        val selection = RemoteLyricsSelection(
            LyricsSourceDescriptor("example", "Example", 1, emptySet()),
            RemoteLyricsPayload(ttmlLyrics = ttml), RemoteLyricsQuality.WORD_SYNCED,
        )
        val ready = RemoteLyricsAdapter.render(selection, 6_000)
        assertEquals(listOf(false, true), ready.lines.map { it.lineTimed })
        val romaji = ready.lines[1].words.filter { it.text.isEmpty() }.mapNotNull { it.romanized }
        // Cut into words to wrap, not one glued run.
        assertEquals(true, romaji.size > 1)
        assertEquals(ready.lines[1].words.filter { it.text.isNotEmpty() }.joinToString("") { it.text }, "今日は空が青い")
    }

    private fun lrc(synced: String) = RemoteLyricsSelection(
        LyricsSourceDescriptor("example", "Example", 1, emptySet()),
        RemoteLyricsPayload(syncedLyrics = synced),
        RemoteLyricsQuality.LINE_SYNCED,
    )

    @Test fun aLineStampedTheSameAsTheNextIsNotEndedByIt() {
        val lines = RemoteLyricsAdapter.render(lrc("[00:01.00]こんにちは\n[00:01.00]Hello\n[00:05.00]Next"), 9_000).lines
        assertEquals(listOf(5_000L, 5_000L), lines.take(2).map { it.endMs })
    }

    @Test fun singleDigitSecondsAndOffsetAreRead() {
        val lines = RemoteLyricsAdapter.render(lrc("[offset:500]\n[0:1.00]One\n[0:3.00]Two"), 9_000).lines
        assertEquals(listOf(500L, 2_500L), lines.map { it.startMs })
    }
}
