package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.lyrics.LyricsFolderIndex.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsFolderIndexTest {
    private fun file(id: String, name: String, content: String = "") =
        LyricsFolderIndex.File(id, name, 0L, LyricsFolderIndex.songsOf(name, content))

    @Test fun songsFromTagsThenFileName() {
        assertEquals(
            listOf(Song("Lemon", "米津玄師"), Song("Lemon", "Kenshi Yonezu"), Song("Kenshi Yonezu", "Lemon")),
            LyricsFolderIndex.songsOf("Kenshi Yonezu - Lemon.lrc", "[ti:Lemon]\n[ar:米津玄師]\n[00:01.00]x"),
        )
        // Track numbers go.
        assertEquals(Song("Bohemian Rhapsody", "Queen"), LyricsFolderIndex.songsOf("01. Queen - Bohemian Rhapsody.ttml", "<tt/>").first())
        val amll = """<tt><head><metadata><amll:meta key="musicName" value="Idol"/><amll:meta key="artists" value="YOASOBI"/></metadata></head></tt>"""
        assertEquals(Song("Idol", "YOASOBI"), LyricsFolderIndex.songsOf("12345.ttml", amll).first())
        assertEquals(emptyList<Song>(), LyricsFolderIndex.songsOf("untitled.ttml", "<tt/>"))
    }

    @Test fun findsBySongNotByName() {
        val files = listOf(
            file("a", "Queen - Bohemian Rhapsody.lrc"),
            file("b", "Lemon - Kenshi Yonezu.ttml"),
            file("c", "whatever.lrc", "[ti:Shake It Off]\n[ar:Taylor Swift]\n[00:01.00]x"),
        )
        // Spotify's "- Remastered 2011" and an extra featured artist still find the file.
        assertEquals("a", LyricsFolderIndex.find(files, "Bohemian Rhapsody - Remastered 2011", "Queen")?.id)
        assertEquals("b", LyricsFolderIndex.find(files, "Lemon", "Kenshi Yonezu")?.id)
        assertEquals("c", LyricsFolderIndex.find(files, "Shake It Off", "Taylor Swift, Someone Else")?.id)
        // Another artist's song of the same name, or no artist at all, isn't a match.
        assertNull(LyricsFolderIndex.find(files, "Lemon", "Rihanna"))
        assertNull(LyricsFolderIndex.find(files, "Lemon", ""))
    }
}
