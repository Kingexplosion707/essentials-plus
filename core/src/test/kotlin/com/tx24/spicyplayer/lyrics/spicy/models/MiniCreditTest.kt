package com.tx24.spicyplayer.lyrics.spicy.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniCreditTest {
    @Test fun `names the source on one short line`() {
        assertNull(LyricsFooter().miniCredit())
        assertEquals(MiniCredit("Provided by Apple Music"), LyricsFooter(provenance = LyricsProvenance("Apple Music")).miniCredit())
        assertEquals(
            "Community lyrics · Made by @teto",
            LyricsFooter(provenance = LyricsProvenance("Spicy Lyrics"), maker = LyricsCredit("teto")).miniCredit()?.text,
        )
    }
}
