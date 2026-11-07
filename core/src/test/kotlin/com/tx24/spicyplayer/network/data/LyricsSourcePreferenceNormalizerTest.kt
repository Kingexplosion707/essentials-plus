package com.tx24.spicyplayer.network.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSourcePreferenceNormalizerTest {
    private val descriptors = listOf(
        descriptor("spicy", 10, enabled = true),
        descriptor("amll", 20, enabled = true),
        descriptor("unison", 30, enabled = false),
        descriptor("lrclib", 100, enabled = true),
    )

    @Test
    fun `fresh install uses defaults and disables default-off sources`() {
        val result = LyricsSourcePreferenceNormalizer.normalize(null, emptySet(), descriptors)

        assertEquals(listOf("spicy", "amll", "unison", "lrclib"), result.order)
        assertEquals(setOf("unison"), result.disabledSourceIds)
    }

    @Test
    fun `keeps explicit relative order`() {
        val result = LyricsSourcePreferenceNormalizer.normalize(
            storedOrder = listOf("lrclib", "spicy", "amll", "unison"),
            storedDisabledSourceIds = emptySet(),
            descriptors = descriptors,
        )

        assertEquals(listOf("lrclib", "spicy", "amll", "unison"), result.order)
    }

    @Test
    fun `inserts new sources beside default neighbors without resetting moves`() {
        val result = LyricsSourcePreferenceNormalizer.normalize(
            storedOrder = listOf("lrclib", "spicy"),
            storedDisabledSourceIds = emptySet(),
            descriptors = descriptors,
        )

        assertEquals(listOf("lrclib", "spicy", "amll", "unison"), result.order)
    }

    @Test
    fun `new default-off source starts disabled`() {
        val result = LyricsSourcePreferenceNormalizer.normalize(
            storedOrder = listOf("spicy", "amll", "lrclib"),
            storedDisabledSourceIds = emptySet(),
            descriptors = descriptors,
        )

        assertEquals(setOf("unison"), result.disabledSourceIds)
    }

    @Test
    fun `previously known default-off source is not forcibly disabled`() {
        val result = LyricsSourcePreferenceNormalizer.normalize(
            storedOrder = listOf("spicy", "unison", "lrclib"),
            storedDisabledSourceIds = emptySet(),
            descriptors = descriptors,
        )

        assertEquals(emptySet<String>(), result.disabledSourceIds)
    }

    @Test
    fun `preserves explicit disabled sources`() {
        val result = LyricsSourcePreferenceNormalizer.normalize(
            storedOrder = descriptors.map(LyricsSourceDescriptor::id),
            storedDisabledSourceIds = setOf("spicy", "unison"),
            descriptors = descriptors,
        )

        assertEquals(setOf("spicy", "unison"), result.disabledSourceIds)
    }

    @Test
    fun `drops duplicate and unknown order ids`() {
        val result = LyricsSourcePreferenceNormalizer.normalize(
            storedOrder = listOf("unknown", "spicy", "spicy", "lrclib"),
            storedDisabledSourceIds = setOf("unknown"),
            descriptors = descriptors,
        )

        assertEquals(listOf("spicy", "amll", "unison", "lrclib"), result.order)
        assertEquals(setOf("unison"), result.disabledSourceIds)
    }

    @Test
    fun `descriptor input order does not affect normalized defaults`() {
        val result = LyricsSourcePreferenceNormalizer.normalize(
            storedOrder = null,
            storedDisabledSourceIds = emptySet(),
            descriptors = descriptors.reversed(),
        )

        assertEquals(listOf("spicy", "amll", "unison", "lrclib"), result.order)
    }

    private fun descriptor(id: String, priority: Int, enabled: Boolean) =
        LyricsSourceDescriptor(
            id = id,
            displayName = id,
            defaultPriority = priority,
            capabilities = setOf(LyricsCapability.PLAIN_TEXT),
            defaultEnabled = enabled,
        )
}
