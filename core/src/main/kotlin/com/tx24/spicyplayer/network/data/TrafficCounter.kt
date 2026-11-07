package com.tx24.spicyplayer.network.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient

/**
 * What the app has downloaded since it started, per host, and how much of it while the lyrics
 * screen was in the background. Bytes are as they came over the wire (compressed), bodies only.
 * The debug report shows it, so a data or battery complaint comes with numbers.
 */
object TrafficCounter : EventListener() {
    private class Tally {
        val bytes = AtomicLong()
        val calls = AtomicLong()
        val backgroundBytes = AtomicLong()
    }

    private val hosts = ConcurrentHashMap<String, Tally>()
    private val startedAt = System.currentTimeMillis()

    /** Whether the lyrics screen is stopped; set by the screen. */
    @Volatile var background = false

    override fun callStart(call: Call) {
        tally(call.request().url.host).calls.incrementAndGet()
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) = add(call.request().url.host, byteCount)

    /** Counts [byteCount] bytes downloaded from [host] outside OkHttp (the cover video player). */
    fun add(host: String?, byteCount: Long) {
        if (host == null || byteCount <= 0) return
        val tally = tally(host)
        tally.bytes.addAndGet(byteCount)
        if (background) tally.backgroundBytes.addAndGet(byteCount)
    }

    /** One line per host, largest first, after a total line. Empty before anything was fetched. */
    fun lines(limit: Int = 10): List<String> {
        val all = hosts.entries.map { it.key to it.value }.sortedByDescending { it.second.bytes.get() }
        if (all.isEmpty()) return emptyList()
        val total = all.sumOf { it.second.bytes.get() }
        val background = all.sumOf { it.second.backgroundBytes.get() }
        val minutes = (System.currentTimeMillis() - startedAt) / 60_000L
        return listOf("${size(total)} in $minutes min, ${size(background)} in the background") +
            all.take(limit).map { (host, t) ->
                "$host: ${size(t.bytes.get())}, ${t.calls.get()} requests" +
                    t.backgroundBytes.get().takeIf { it > 0 }?.let { " (${size(it)} in the background)" }.orEmpty()
            }
    }

    private fun tally(host: String) = hosts.getOrPut(host) { Tally() }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_048_576.0)
        bytes >= 1L shl 10 -> "${bytes shr 10} KB"
        else -> "$bytes B"
    }
}

/** Has the client's downloads counted by [TrafficCounter]. */
fun OkHttpClient.Builder.counted(): OkHttpClient.Builder = eventListener(TrafficCounter)
