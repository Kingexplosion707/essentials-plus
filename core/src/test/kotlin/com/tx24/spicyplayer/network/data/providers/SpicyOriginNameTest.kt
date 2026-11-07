package com.tx24.spicyplayer.network.data.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class SpicyOriginNameTest {
    @Test fun `names the catalogues Spicy Lyrics syndicates`() {
        assertEquals("Apple Music", spicyOriginName("aml"))
        assertEquals("Unison", spicyOriginName("uns"))
        assertEquals("Spicy Lyrics Community", spicyOriginName("spl"))
        assertEquals("Spicy Lyrics", spicyOriginName(null))
    }
}
