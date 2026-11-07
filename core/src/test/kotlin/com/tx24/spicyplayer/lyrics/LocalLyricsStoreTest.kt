package com.tx24.spicyplayer.lyrics

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricsStoreTest {
    private val dir = Files.createTempDirectory("local-lyrics").toFile().also { it.deleteOnExit() }

    @Test
    fun `saved song is found by its lookup names, case and spaces aside`() {
        val store = LocalLyricsStore(dir)
        store.put("Bologna", "WANDA", "Bologna (Remastered)", "WANDA", "Amore", "<tt/>", now = 1)
        assertEquals("<tt/>", store.get(" bologna ", "wanda"))
        assertTrue(store.contains("BOLOGNA", "Wanda"))
        assertEquals("Bologna (Remastered)", store.entries().single().title)
    }

    @Test
    fun `saving again replaces the song, newest first`() {
        val store = LocalLyricsStore(dir)
        store.put("A", "X", "A", "X", "", "<tt>1</tt>", now = 1)
        store.put("B", "Y", "B", "Y", "", "<tt>2</tt>", now = 2)
        store.put("A", "X", "A", "X", "", "<tt>3</tt>", now = 3)
        assertEquals(listOf("A", "B"), store.entries().map { it.title })
        assertEquals("<tt>3</tt>", store.get("A", "X"))
    }

    @Test
    fun `removed song and its cover are gone`() {
        val store = LocalLyricsStore(dir)
        val entry = store.put("A", "X", "A", "X", "", "<tt/>", now = 1)
        store.coverFile(entry.key).writeBytes(byteArrayOf(1))
        store.remove(entry.key)
        assertNull(store.get("A", "X"))
        assertFalse(store.coverFile(entry.key).exists())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `an unreadable index lists the saved files instead of failing`() {
        val store = LocalLyricsStore(dir)
        val entry = store.put("A", "X", "A", "X", "", "<tt/>", now = 1)
        // What a minified build wrote before: renamed fields.
        dir.resolve("index.json").writeText("""{"a":[{"a":"x","b":"A"}]}""")
        assertEquals(listOf(entry.key), store.entries().map { it.key })
        assertEquals("<tt/>", store.get("A", "X"))
        dir.resolve("index.json").writeText("not json")
        assertEquals(1, store.entries().size)
    }

    @Test
    fun `an entry whose file went missing isn't listed`() {
        val store = LocalLyricsStore(dir)
        val entry = store.put("A", "X", "A", "X", "", "<tt/>", now = 1)
        dir.resolve("${entry.key}.ttml").delete()
        assertTrue(store.entries().isEmpty())
    }
}
