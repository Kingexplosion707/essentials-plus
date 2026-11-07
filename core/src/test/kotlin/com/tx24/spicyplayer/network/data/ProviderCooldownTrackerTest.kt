package com.tx24.spicyplayer.network.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderCooldownTrackerTest {
    private val now = Instant.parse("2026-09-21T12:00:00Z")

    @Test
    fun `delta seconds creates deadline`() {
        assertEquals(now.plusSeconds(120), RetryAfterParser.deadline("120", now))
    }

    @Test
    fun `http date creates deadline`() {
        assertEquals(
            Instant.parse("2026-09-21T12:02:00Z"),
            RetryAfterParser.deadline("Mon, 21 Sep 2026 12:02:00 GMT", now),
        )
    }

    @Test
    fun `invalid header uses safe default`() {
        assertEquals(now.plusSeconds(60), RetryAfterParser.deadline("nonsense", now))
        assertEquals(now.plusSeconds(60), RetryAfterParser.deadline(null, now))
    }

    @Test
    fun `zero and past values clamp to one second`() {
        assertEquals(now.plusSeconds(1), RetryAfterParser.deadline("0", now))
        assertEquals(now.plusSeconds(1), RetryAfterParser.deadline("-9", now))
        assertEquals(
            now.plusSeconds(1),
            RetryAfterParser.deadline("Mon, 21 Sep 2026 11:00:00 GMT", now),
        )
    }

    @Test
    fun `excessive delay clamps to one day`() {
        assertEquals(now.plusSeconds(86_400), RetryAfterParser.deadline("999999", now))
    }

    @Test
    fun `tracker keeps later deadline`() {
        val tracker = ProviderCooldownTracker()
        tracker.record("source", now.plusSeconds(120))
        tracker.record("source", now.plusSeconds(30))

        assertEquals(now.plusSeconds(120), tracker.retryAt("source", now))
    }

    @Test
    fun `tracker keeps the reason of the deadline it keeps`() {
        val tracker = ProviderCooldownTracker()
        tracker.record("source", now.plusSeconds(120), "HTTP 429")
        tracker.record("source", now.plusSeconds(30), "timeout")

        assertEquals("HTTP 429", tracker.reason("source"))
    }

    @Test
    fun `tracker removes expired deadline`() {
        val tracker = ProviderCooldownTracker()
        tracker.record("source", now.plusSeconds(10))

        assertNull(tracker.retryAt("source", now.plusSeconds(10)))
        assertNull(tracker.retryAt("source", now.plusSeconds(11)))
    }

    @Test
    fun `clear affects only requested source`() {
        val tracker = ProviderCooldownTracker()
        tracker.record("one", now.plusSeconds(10))
        tracker.record("two", now.plusSeconds(20))

        tracker.clear("one")

        assertNull(tracker.retryAt("one", now))
        assertEquals(now.plusSeconds(20), tracker.retryAt("two", now))
    }

    @Test
    fun `clear all removes every deadline`() {
        val tracker = ProviderCooldownTracker()
        tracker.record("one", now.plusSeconds(10))
        tracker.record("two", now.plusSeconds(20))

        tracker.clearAll()

        assertNull(tracker.retryAt("one", now))
        assertNull(tracker.retryAt("two", now))
    }
}
