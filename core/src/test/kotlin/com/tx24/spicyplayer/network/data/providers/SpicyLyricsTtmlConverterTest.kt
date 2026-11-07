package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.tx24.spicyplayer.lyrics.spicy.models.LyricsType
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsQuality
import com.tx24.spicyplayer.network.data.measuredQuality
import com.tx24.spicyplayer.network.data.spotify.AnonymousSpotifyCatalogSearch
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolver
import okhttp3.OkHttpClient
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import org.junit.Assert.assertEquals
import org.junit.Test

class SpicyLyricsTtmlConverterTest {
    @Test fun preservesDuetSingerBackgroundAndExplicitAlignment() {
        val body = JsonParser.parseString("""{"Content":[
            {"Agent":"lead","Lead":{"Syllables":[{"Text":"A","StartTime":1,"EndTime":2}]},"Background":[{"Syllables":[{"Text":"oh","StartTime":1.5,"EndTime":2.5}]}]},
            {"Agent":"guest","OppositeAligned":false,"Lead":{"Syllables":[{"Text":"B","StartTime":3,"EndTime":4}]}},
            {"Agent":"lead","OppositeAligned":true,"Lead":{"Syllables":[{"Text":"C","StartTime":5,"EndTime":6}]}}
        ]}""").asJsonObject
        val ttml = requireNotNull(SpicyLyricsTtmlConverter.convert(body, body.getAsJsonArray("Content")))

        val lines = TtmlLyricsParser.parse(ttml.byteInputStream()).lines

        assertEquals(4, lines.size)
        assertEquals(LineRole.BACKGROUND, lines[1].role)
        assertEquals(lines[0].groupId, lines[1].groupId)
        assertEquals("lead", lines[0].agent)
        assertEquals("guest", lines[2].agent)
        assertEquals(false, lines[2].oppositeAligned)
        assertEquals(true, lines[3].oppositeAligned)
    }

    @Test fun keepsLineThatIsOnlyBackgroundVocals() {
        // Industry Baby's first line as the Spicy Lyrics API sends it: an empty lead, all background.
        val body = JsonParser.parseString("""{"Content":[
            {"Type":"Vocal","OppositeAligned":false,"Lead":{"Syllables":[],"StartTime":4.425,"EndTime":6.42},"Background":[{"Syllables":[
                {"Text":"D-","IsPartOfWord":true,"StartTime":4.425,"EndTime":4.618},
                {"Text":"Day","IsPartOfWord":false,"StartTime":4.618,"EndTime":4.987},
                {"Text":"trip","IsPartOfWord":false,"StartTime":4.987,"EndTime":6.42}],"StartTime":4.425,"EndTime":6.42}]},
            {"Type":"Vocal","OppositeAligned":false,"Lead":{"Syllables":[{"Text":"Baby","IsPartOfWord":false,"StartTime":6.74,"EndTime":7.1}],"StartTime":6.74,"EndTime":7.1}}
        ]}""").asJsonObject
        val ttml = requireNotNull(SpicyLyricsTtmlConverter.convert(body, body.getAsJsonArray("Content")))

        val lines = TtmlLyricsParser.parse(ttml.byteInputStream()).lines

        assertEquals(listOf(LineRole.LEAD, LineRole.BACKGROUND, LineRole.LEAD), lines.map { it.role })
        assertEquals(emptyList<String>(), lines[0].words.map { it.text })
        assertEquals(4_425L, lines[0].startMs)
        assertEquals(listOf("D-", "Day", "trip"), lines[1].words.map { it.text.trim() })
        assertEquals(lines[0].groupId, lines[1].groupId)
    }

    @Test fun leadLineCoversItsBackgroundVocals() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word"><body><div>
            <p begin="2.000" end="3.000"><span begin="2.000" end="3.000">A</span><span ttm:role="x-bg"><span begin="1.500" end="3.500">(oh)</span></span></p>
        </div></body></tt>"""

        val lines = TtmlLyricsParser.parse(ttml.byteInputStream()).lines

        assertEquals(listOf(LineRole.LEAD, LineRole.BACKGROUND), lines.map { it.role })
        assertEquals(1_500L, lines[0].startMs)
        assertEquals(3_500L, lines[0].endMs)
    }

    @Test fun preservesSyllableAttachments() {
        val body = JsonParser.parseString("""{"Content":[{"Lead":{"Syllables":[
            {"Text":"Hel","StartTime":1,"EndTime":1.4,"IsPartOfWord":true},
            {"Text":"lo","StartTime":1.4,"EndTime":1.8,"IsPartOfWord":false},
            {"Text":"world","StartTime":2,"EndTime":2.4,"IsPartOfWord":false}
        ]}}]}""").asJsonObject
        val ttml = requireNotNull(SpicyLyricsTtmlConverter.convert(body, body.getAsJsonArray("Content")))

        val words = TtmlLyricsParser.parse(ttml.byteInputStream()).lines.single().words

        assertEquals(listOf("Hel", "lo", "world"), words.map { it.text.trim() })
        assertEquals(listOf(false, true, false), words.map { it.isPartOfWord })
    }

    @Test fun convertsLineTypeResponses() {
        val body = JsonParser.parseString("""{"Type":"Line","Content":[
            {"Type":"Vocal","OppositeAligned":false,"Text":"first line","StartTime":6.74,"EndTime":8.21},
            {"Type":"Vocal","OppositeAligned":true,"Text":"second","StartTime":8.21,"EndTime":9.72}
        ]}""").asJsonObject
        val ttml = requireNotNull(SpicyLyricsTtmlConverter.convert(body, body.getAsJsonArray("Content")))

        val parsed = TtmlLyricsParser.parse(ttml.byteInputStream())

        assertEquals(LyricsType.Line, parsed.type)
        assertEquals(listOf(6_740L, 8_210L), parsed.lines.map { it.startMs })
        assertEquals("first line", parsed.lines[0].words.joinToString(" ") { it.text.trim() })
        assertEquals(true, parsed.lines[1].oppositeAligned)
    }

    @Test fun staticResponsesBecomePlainLyrics() {
        val okhttp = OkHttpClient()
        val gson = Gson()
        val provider = SpicyLyricsProvider(okhttp, gson, SpotifyTrackResolver(AnonymousSpotifyCatalogSearch(okhttp, gson)), "key")

        val hit = provider.parseHit("""{"Status":200,"Body":{"Type":"Static","Lines":[{"Text":"one"},{"Text":"two"}]}}""")

        val payload = (hit as ProviderResult.Hit).payload
        assertEquals("one\ntwo", payload.plainLyrics)
        assertEquals(RemoteLyricsQuality.PLAIN, payload.measuredQuality())
    }

    @Test fun carriesSpicyTransliterationsPerSyllable() {
        val body = JsonParser.parseString("""{"Content":[
            {"Lead":{"Syllables":[
                {"Text":"なん","TransliteratedText":"nan","StartTime":1,"EndTime":1.2,"IsPartOfWord":true},
                {"Text":"で","TransliteratedText":"de","StartTime":1.2,"EndTime":1.4,"IsPartOfWord":false}
            ]}},
            {"Lead":{"Syllables":[{"Text":"no","StartTime":2,"EndTime":2.4}]}}
        ]}""").asJsonObject
        val ttml = requireNotNull(SpicyLyricsTtmlConverter.convert(body, body.getAsJsonArray("Content")))

        val lines = TtmlLyricsParser.parse(ttml.byteInputStream()).lines

        assertEquals(listOf("nan", "de"), lines[0].words.map { it.romanizedText })
        assertEquals(listOf<String?>(null), lines[1].words.map { it.romanizedText })
    }

    @Test fun keepsEveryBackgroundVocal() {
        val body = JsonParser.parseString("""{"Content":[
            {"Lead":{"Syllables":[{"Text":"A","StartTime":1,"EndTime":2}]},"Background":[
                {"Syllables":[{"Text":"oh","StartTime":1,"EndTime":2}]},
                {"Syllables":[{"Text":"yeah","StartTime":2,"EndTime":3}]}
            ]}
        ]}""").asJsonObject
        val ttml = requireNotNull(SpicyLyricsTtmlConverter.convert(body, body.getAsJsonArray("Content")))

        val lines = TtmlLyricsParser.parse(ttml.byteInputStream()).lines

        assertEquals(listOf("A", "oh", "yeah"), lines.map { it.words.single().text })
        assertEquals(listOf(LineRole.LEAD, LineRole.BACKGROUND, LineRole.BACKGROUND), lines.map { it.role })
    }
}
