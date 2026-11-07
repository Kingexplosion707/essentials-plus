package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import com.tx24.spicyplayer.network.data.slobjpack.SLObjPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnisonStoreListTest {
    private val gson = Gson()

    private fun entry(body: Any?): String = gson.toJson(mapOf(
        "object" to "storelist.entry",
        "data" to mapOf("id" to 1, "lyrics" to mapOf("type" to "Syllable", "content" to SLObjPack().pack(body))),
    ))

    private fun syllable(text: String, start: Double, end: Double, partOfWord: Boolean = false) =
        mapOf("Text" to text, "IsPartOfWord" to partOfWord, "StartTime" to start, "EndTime" to end)

    @Test fun `unpacks a word-synced body into TTML`() {
        val body = mapOf(
            "Type" to "Syllable", "StartTime" to 1.0, "EndTime" to 6.0,
            "Content" to listOf(
                mapOf("Type" to "Vocal", "OppositeAligned" to false,
                    "Lead" to mapOf("Syllables" to listOf(syllable("Hel", 1.0, 1.4, partOfWord = true), syllable("lo", 1.4, 2.0)), "StartTime" to 1.0, "EndTime" to 2.0),
                    "Background" to listOf(mapOf("Syllables" to listOf(syllable("oh", 1.5, 2.5)), "StartTime" to 1.5, "EndTime" to 2.5))),
                mapOf("Type" to "Vocal", "OppositeAligned" to true,
                    "Lead" to mapOf("Syllables" to listOf(syllable("there", 3.0, 6.0)), "StartTime" to 3.0, "EndTime" to 6.0)),
            ),
        )
        val payload = requireNotNull(storeListPayload(entry(body), gson))
        val lines = TtmlLyricsParser.parse(requireNotNull(payload.ttmlLyrics).byteInputStream()).lines

        assertEquals(3, lines.size)
        assertEquals("Hello", lines[0].words.joinToString("") { it.text }.trim())
        assertEquals(LineRole.BACKGROUND, lines[1].role)
        assertEquals(3_000L, lines[2].startMs)
    }

    @Test fun `reads a static body as plain lyrics`() {
        val payload = requireNotNull(storeListPayload(entry(mapOf("Type" to "Static", "Lines" to listOf(mapOf("Text" to "one"), mapOf("Text" to "two")))), gson))
        assertEquals("one\ntwo", payload.plainLyrics)
        assertNull(payload.ttmlLyrics)
    }
}
