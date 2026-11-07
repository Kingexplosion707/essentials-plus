package com.tx24.spicyplayer.network.data.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YrcToTtmlTest {
    private fun begins(ttml: String) = Regex("""<span begin="([\d.]+)s" end="([\d.]+)s">""").findAll(ttml)
        .map { it.groupValues[1] to it.groupValues[2] }.toList()

    @Test
    fun `word times are absolute, not added to the line start`() {
        val ttml = YrcToTtml.convert("[28480,1000](28480,160,0)我(28640,420,0)带")!!

        assertEquals(listOf("28.480" to "28.640", "28.640" to "29.060"), begins(ttml))
        assertTrue("""<p begin="28.480s" end="29.480s">""" in ttml)
    }

    @Test
    fun `words timed from their line's start are placed after it`() {
        val ttml = YrcToTtml.convert("[28480,1000](0,160,0)我(160,420,0)带")!!

        assertEquals(listOf("28.480" to "28.640", "28.640" to "29.060"), begins(ttml))
    }

    @Test
    fun `credit and rights lines are dropped`() {
        val ttml = YrcToTtml.convert(
            """
            [1000,1000](1000,1000,0) 作曲 : 赵雷
            [1,4890](1,270,0)词(270,270,0)版(540,270,0)权(810,270,0)管(1080,270,0)理(1350,270,0)方(1620,270,0)：(1890,270,0)北(2160,270,0)京
            [11140,3260](11140,270,0)录(11410,270,0)音(11680,270,0)作(11950,270,0)品(12220,270,0)及(12490,270,0)MV(12760,270,0)版(13030,270,0)权(13300,270,0)：(13570,270,0)EAS
            [28480,1000](28480,160,0)我
            """.trimIndent(),
        )!!

        assertFalse("作曲" in ttml)
        assertFalse("版" in ttml)
        assertEquals(listOf("28.480" to "28.640"), begins(ttml))
    }

    @Test
    fun `only credits is nothing`() {
        assertNull(YrcToTtml.convert("[1000,1000](1000,1000,0) 作曲 : 赵雷"))
    }
}
