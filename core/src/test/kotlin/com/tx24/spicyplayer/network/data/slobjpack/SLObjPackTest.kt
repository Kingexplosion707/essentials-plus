package com.tx24.spicyplayer.network.data.slobjpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SLObjPackTest {
    private val packer = SLObjPack()

    @Test
    fun `matches the reference encoder on the golden documents`() {
        var docs = 0
        DumpReader(javaClass.getResourceAsStream("/slobjpack/golden.bin")!!).use { reader ->
            while (reader.hasMore()) {
                val problems = checkRecord(packer, reader.record())
                assertTrue("golden doc $docs: $problems", problems.isEmpty())
                docs++
            }
        }
        assertTrue(docs > 40)
    }

    @Test
    fun `rejects bad envelopes`() {
        for ((payload, message) in listOf(
            listOf(2) to "Invalid payload structure",
            listOf(3, "AAAA") to "Unsupported version 3",
            listOf(2, "ÿÿ") to "Invalid base64 character",
        )) {
            val error = assertThrows(SLObjPackException::class.java) { packer.unpack(payload) }
            assertEquals("SLObjPack unpack: $message", error.message)
        }
    }

    @Test
    fun `handles the depth limit without overflowing the stack`() {
        var deepest: Any? = 1.0
        repeat(DEFAULT_LIMITS.depth) { i -> deepest = if (i % 2 == 0) listOf(deepest) else linkedMapOf("k" to deepest) }
        assertNull(difference(deepest, packer.unpackV2(packer.pack(deepest)[1] as String)))
    }

    @Test
    fun `unpacks version 1`() {
        val v1 = listOf(listOf("a", 1, "b", true), listOf(-1, 2, 0, 2, 1, -2, 2, 3, 3))
        assertEquals(linkedMapOf("a" to 1.0, "b" to listOf(true, true)), packer.unpack(v1))
    }
}
