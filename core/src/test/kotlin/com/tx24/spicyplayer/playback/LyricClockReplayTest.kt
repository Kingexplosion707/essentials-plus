package com.tx24.spicyplayer.playback

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Replays `SpicySync` logcat captures through [LyricClock] and prints how far off the lyrics were,
 * next to what the build that wrote the log did. Skipped unless `SYNC_REPLAY_LOGS` lists log files
 * (separated by the path separator); run with `--info` to see the table.
 *
 * Off is measured two ways, weighted by time: against each report as it came, and against
 * hindsight (the median of the reports in the few seconds after, which outvotes a stray one).
 */
class LyricClockReplayTest {
    private val reportLine = Regex(
        """(\d\d):(\d\d):(\d\d)\.(\d{3}) D/SpicySync\(\s*(\d+)\): report (\S+) state=(\d+) pos=(\d+) age=(-?\d+)ms speed=([\d.]+) drift=(-?\d+)ms applied=(-?\d+)ms(?: bias=(-?\d+)ms)?""",
    )
    private val trackLine = Regex("""(\d\d):(\d\d):(\d\d)\.(\d{3}) D/SpicySync\(\s*(\d+)\): track changed before its report""")
    private val resyncLine = Regex("""(\d\d):(\d\d):(\d\d)\.(\d{3}) D/SpicySync\(\s*(\d+)\): auto resync at""")

    private sealed interface Event { val atMs: Long; val pid: String }
    private data class Logged(
        override val atMs: Long, override val pid: String, val app: String, val state: Int, val pos: Long,
        val age: Long, val speed: Float, val drift: Long, val applied: Long, val bias: Long,
    ) : Event {
        val raw get() = if (state == 3) pos + (age * speed).toLong() else pos
        /** Where the logging build's clock was after this report. */
        val oldClock get() = raw + bias - (drift - applied)
    }
    private data class TrackChange(override val atMs: Long, override val pid: String) : Event
    private data class Resync(override val atMs: Long, override val pid: String) : Event

    private fun time(m: MatchResult) = m.groupValues.let { g ->
        ((g[1].toLong() * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1_000 + g[4].toLong()
    }

    private fun parse(file: File): List<Event> = file.readLines().mapNotNull { line ->
        reportLine.find(line)?.let { m ->
            val g = m.groupValues
            Logged(
                time(m), g[5], when { "kde" in g[6] -> "kde"; "youtube" in g[6] -> "ytm"; "spotify" in g[6] -> "spotify"; else -> g[6] }, g[7].toInt(),
                g[8].toLong(), g[9].toLong(), g[10].toFloat(), g[11].toLong(), g[12].toLong(), g[13].toLongOrNull() ?: 0L,
            )
        } ?: trackLine.find(line)?.let { TrackChange(time(it), it.groupValues[5]) }
            ?: resyncLine.find(line)?.let { Resync(time(it), it.groupValues[5]) }
    }

    private class Tally {
        val seconds = LinkedHashMap<String, Double>()
        var total = 0.0
        val samples = ArrayList<Pair<Long, Double>>()
        fun add(offMs: Long, weightS: Double) {
            val bucket = when {
                abs(offMs) < 150 -> "<150"
                abs(offMs) < 300 -> "150-300"
                abs(offMs) < 600 -> "300-600"
                else -> ">600"
            }
            seconds[bucket] = (seconds[bucket] ?: 0.0) + weightS
            samples += abs(offMs) to weightS
            total += weightS
        }
        /** The error that [share] of the time was within. */
        fun percentile(share: Double): Long {
            var sum = 0.0
            for ((off, w) in samples.sortedBy { it.first }) {
                sum += w
                if (sum >= share * total) return off
            }
            return samples.maxOfOrNull { it.first } ?: 0L
        }
        fun row() = "p50 %4dms  p90 %4dms  p99 %4dms  ".format(percentile(.5), percentile(.9), percentile(.99)) + listOf("<150", "150-300", "300-600", ">600").joinToString("  ") {
            "%s %5.1f%%".format(it, 100 * (seconds[it] ?: 0.0) / total.coerceAtLeast(1e-9))
        }
    }

    @Test fun replay() {
        val files = System.getenv("SYNC_REPLAY_LOGS")?.split(File.pathSeparator)?.filter { it.isNotBlank() }
        assumeTrue(!files.isNullOrEmpty())
        val tallies = sortedMapOf<String, Tally>()
        for (file in files!!.map(::File)) {
            for ((pid, events) in parse(file).groupBy { it.pid }) {
                replayProcess(events, tallies, "${file.name}#$pid")
            }
        }
        for ((key, tally) in tallies) println("%-40s %6.0fs  %s".format(key, tally.total, tally.row()))
    }

    private fun replayProcess(events: List<Event>, tallies: MutableMap<String, Tally>, label: String) {
        val reports = events.filterIsInstance<Logged>()
        if (reports.isEmpty()) return
        var clock = LyricClock(events.first().atMs)
        var lastApp: String? = null
        var snapNext = true
        val newClock = HashMap<Logged, Long>()
        for (event in events) when (event) {
            is TrackChange -> { clock.startTrack(event.atMs, 1f, clock.isPlaying); snapNext = true }
            is Resync -> clock.seekTo(clock.positionAt(event.atMs), event.atMs)
            is Logged -> {
                if (event.app != lastApp) { clock = LyricClock(event.atMs); snapNext = true; lastApp = event.app }
                val report = LyricClock.Report(event.state, event.state == 3, event.pos, event.atMs - event.age, event.speed, relayed = event.app == "kde")
                if (!clock.isFresh(report) && !snapNext) continue
                val outcome = clock.onReport(report, event.atMs, snap = snapNext)
                snapNext = false
                newClock[event] = clock.positionAt(event.atMs)
            }
        }
        for ((i, r) in reports.withIndex()) {
            val next = reports.getOrNull(i + 1) ?: break
            val key = "$label ${r.app}"
            val old = tallies.getOrPut("$key old vs reports") { Tally() }
            val new = tallies.getOrPut("$key new vs reports") { Tally() }
            val oldH = tallies.getOrPut("$key old vs hindsight") { Tally() }
            val newH = tallies.getOrPut("$key new vs hindsight") { Tally() }
            val nc = newClock[r]
            if (r.state != 3 || next.app != r.app) continue
            val weight = ((next.atMs - r.atMs).coerceIn(0L, 2_500L)) / 1_000.0
            val truthNow = r.raw
            old.add(truthNow - r.oldClock, weight)
            nc?.let { new.add(truthNow - it, weight) }
            if (nc != null && abs(truthNow - nc) >= 150 && System.getenv("SYNC_REPLAY_DUMP") != null) {
                println("OFF $key at ${r.atMs / 1000.0}s state=${r.state} pos=${r.pos} new=${truthNow - nc} old=${truthNow - r.oldClock}")
            }
            // Hindsight: the median of the playing reports 0.5-3 s later, brought back to now.
            val later = reports.drop(i + 1).takeWhile { it.atMs - r.atMs <= 3_000L && it.state == 3 && it.app == r.app }
                .filter { it.atMs - r.atMs >= 500L }
                .map { it.raw + it.bias - (it.atMs - r.atMs) }
            // A seek or a new song in the window leaves nothing to judge by.
            if (later.size >= 3 && later.max() - later.min() < LyricClock.JUMP_MS) {
                val truth = later.sorted()[later.size / 2]
                oldH.add(truth - r.oldClock, weight)
                nc?.let { newH.add(truth - it, weight) }
            }
        }
    }
}
