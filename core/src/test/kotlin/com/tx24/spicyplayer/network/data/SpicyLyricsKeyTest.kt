package com.tx24.spicyplayer.network.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpicyLyricsKeyTest {
    @Test
    fun `client keys pass trimmed, secret and junk keys don't`() {
        assertEquals(SpicyLyricsKey.Check.Ok("sl_pk_AbC123_-xyz"), SpicyLyricsKey.check("  sl_pk_AbC123_-xyz\n"))
        assertEquals(SpicyLyricsKey.Check.Empty, SpicyLyricsKey.check("   "))
        assertEquals(SpicyLyricsKey.Check.Secret, SpicyLyricsKey.check("sl_sk_AbC123xyz"))
        assertEquals(SpicyLyricsKey.Check.Invalid, SpicyLyricsKey.check("sl_pk_short"))
        assertEquals(SpicyLyricsKey.Check.Invalid, SpicyLyricsKey.check("sl_pk_AbC123xyz UMAMI=1"))
        assertEquals(SpicyLyricsKey.Check.Invalid, SpicyLyricsKey.check("hello"))
    }

    @Test
    fun `hint shows only the last four characters`() {
        assertEquals("sl_pk_…wxyz", SpicyLyricsKey.hint("sl_pk_abcdefghijklmnopqrstuvwxyz"))
    }
}
