package com.tx24.spicyplayer.network.canvas

import com.tx24.spicyplayer.network.canvas.SpotifyCanvasFinder.Answer
import com.tx24.spicyplayer.network.canvas.SpotifyCanvasFinder.Companion.parse
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SpotifyCanvasFinderTest {
    private val id = "4Dvkj6JhhA12EX05fT7y2e"
    private val mp4 = "https://canvaz.scdn.co/upload/licensor/x/video/abc.cnvs.mp4"
    private val other = "0VjIjW4GlUZAMYd2vXMi3b"
    private val found = """{"object":"canvaslist.entries","request_id":"req_1_us",
        "lookup":{"type":"track_id","value":["$id"]},
        "data":{"$id":{"url":"$mp4","type":"VIDEO_LOOPING_RANDOM","fileId":"f","uri":"spotify:canvas:1"}}}"""
    /** No canvas, for either track the tests ask about. */
    private val none = """{"object":"canvaslist.entries","data":{"$id":null,"$other":null}}"""

    private fun entry(value: String) = """{"data":{"$id":$value}}"""

    @Test
    fun `an entry with a canvas gives its MP4`() {
        assertEquals(Answer.Found(mp4), parse(found, id))
    }

    @Test
    fun `a null entry is no canvas`() {
        assertEquals(Answer.None, parse(none, id))
    }

    @Test
    fun `an entry without an HTTPS MP4 is no canvas`() {
        assertEquals(Answer.None, parse(entry("""{"url":"https://canvaz.scdn.co/still.jpg","type":"IMAGE"}"""), id))
        assertEquals(Answer.None, parse(entry("""{"url":"http://canvaz.scdn.co/plain.mp4"}"""), id))
        assertEquals(Answer.None, parse(entry("""{"type":"VIDEO"}"""), id))
        assertEquals(Answer.Found("$mp4?x=1"), parse(entry("""{"url":"$mp4?x=1"}"""), id))
    }

    @Test
    fun `an answer for another track, a bare map or an error entry is a failure, not a miss`() {
        assertNull(parse("""{"data":{"$other":{"url":"$mp4"}}}""", id))
        assertNull(parse("""{"$id":{"url":"$mp4"}}""", id))
        assertNull(parse(entry("""{"error":{"status":502,"code":"upstream_failed","message":"m"}}"""), id))
    }

    @Test
    fun `a malformed body is a failure, not a miss`() {
        assertNull(parse("not json", id))
        assertNull(parse("""{"object":"error","error":{"status":500,"code":"internal_error","message":"m"}}""", id))
        assertNull(parse("""["data"]""", id))
    }

    @Test
    fun `only real track IDs are asked about`() {
        var calls = 0
        val finder = newFinder(fake { calls++; 200 to found })
        assertNull(runBlocking { finder.find("not-an-id") })
        assertNull(runBlocking { finder.find("") })
        assertEquals(0, calls)
    }

    @Test
    fun `answers are remembered per track, misses included`() {
        var calls = 0
        var body = found
        val finder = newFinder(fake { calls++; 200 to body })
        assertEquals(mp4, runBlocking { finder.find(id) })
        assertEquals(mp4, runBlocking { finder.find(id) })
        assertEquals(1, calls)

        body = none
        assertNull(runBlocking { finder.find(other) })
        assertNull(runBlocking { finder.find(other) })
        assertEquals(2, calls)
    }

    @Test
    fun `a miss is asked again once it is old`() {
        var calls = 0
        var clock = 0L
        val finder = newFinder(fake { calls++; 200 to none }, now = { clock })
        runBlocking { finder.find(id) }
        clock += SpotifyCanvasFinder.MISS_TTL_MS - 1
        runBlocking { finder.find(id) }
        assertEquals(1, calls)
        clock += 1
        runBlocking { finder.find(id) }
        assertEquals(2, calls)
    }

    @Test
    fun `a bad request is no canvas and is remembered`() {
        var calls = 0
        val finder = newFinder(fake { calls++; 400 to """{"object":"error","error":{"status":400,"code":"invalid_track_id","message":"m"}}""" })
        assertNull(runBlocking { finder.find(id) })
        assertNull(runBlocking { finder.find(id) })
        assertEquals(1, calls)
    }

    @Test
    fun `server errors and network failures are retried once, not remembered, and pause the lookups a while`() {
        for (failure in listOf<() -> Pair<Int, String>>({ 500 to "oops" }, { 429 to "" }, { throw IOException("offline") }, { 200 to "<html>" })) {
            var calls = 0
            var clock = 0L
            var fail = true
            val finder = newFinder(fake { calls++; if (fail) failure() else 200 to found }, now = { clock })
            assertNull(runBlocking { finder.find(id) })
            assertEquals(2, calls)
            // Paused: not asked again straight away, even for another track.
            assertNull(runBlocking { finder.find(other) })
            assertEquals(2, calls)
            clock += SpotifyCanvasFinder.FAILURE_PAUSE_MS
            fail = false
            assertEquals(mp4, runBlocking { finder.find(id) })
            assertEquals(3, calls)
        }
    }

    @Test
    fun `a blip is ridden out by the retry`() {
        var calls = 0
        val finder = newFinder(fake { calls++; if (calls == 1) 500 to "oops" else 200 to found })
        assertEquals(mp4, runBlocking { finder.find(id) })
        assertEquals(2, calls)
    }

    @Test
    fun `the pause doubles with each failure in a row and resets on success`() {
        var clock = 0L
        var fail = true
        var calls = 0
        val finder = newFinder(fake { calls++; if (fail) 500 to "oops" else 200 to none }, now = { clock })
        runBlocking { finder.find(id) }
        clock += SpotifyCanvasFinder.FAILURE_PAUSE_MS
        runBlocking { finder.find(id) }
        assertEquals(4, calls)
        // Second failure in a row: twice the pause.
        clock += SpotifyCanvasFinder.FAILURE_PAUSE_MS
        runBlocking { finder.find(id) }
        assertEquals(4, calls)
        clock += SpotifyCanvasFinder.FAILURE_PAUSE_MS
        fail = false
        runBlocking { finder.find(id) }
        assertEquals(5, calls)
        // Back to the short pause after a success.
        fail = true
        runBlocking { finder.find(other) }
        clock += SpotifyCanvasFinder.FAILURE_PAUSE_MS
        fail = false
        runBlocking { finder.find(other) }
        assertEquals(8, calls)
    }

    @Test
    fun `the pause stops growing at its cap`() {
        var clock = 0L
        var calls = 0
        val finder = newFinder(fake { calls++; 500 to "oops" }, now = { clock })
        repeat(12) {
            runBlocking { finder.find(id) }
            clock += SpotifyCanvasFinder.MAX_FAILURE_PAUSE_MS
        }
        assertEquals(24, calls)
    }

    @Test
    fun `a Retry-After is waited out without a second try`() {
        var calls = 0
        var clock = 0L
        var limited = true
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("")
            if (limited) {
                builder.code(429).header("Retry-After", "600")
                    .body("""{"object":"error","error":{"status":429,"code":"rate_limited","message":"m"}}""".toResponseBody())
            } else {
                builder.code(200).body(found.toResponseBody())
            }
            builder.build()
        }.build()
        val finder = newFinder(client, now = { clock })
        assertNull(runBlocking { finder.find(id) })
        assertEquals(1, calls)
        // Longer than the first pause, as the service asked.
        clock += SpotifyCanvasFinder.FAILURE_PAUSE_MS
        limited = false
        assertNull(runBlocking { finder.find(id) })
        assertEquals(1, calls)
        clock = 600_000L
        assertEquals(mp4, runBlocking { finder.find(id) })
        assertEquals(2, calls)
    }

    @Test
    fun `the memo survives on disk and a forgotten canvas is asked again`() {
        val dir = Files.createTempDirectory("canvas").toFile()
        try {
            val file = File(dir, "canvas/known.json")
            var calls = 0
            val client = fake { calls++; 200 to found }
            assertEquals(mp4, runBlocking { newFinder(client, memoFile = file).find(id) })
            val reopened = newFinder(client, memoFile = file)
            assertEquals(mp4, runBlocking { reopened.find(id) })
            assertEquals(1, calls)
            runBlocking { reopened.forget(id) }
            assertEquals(mp4, runBlocking { reopened.find(id) })
            assertEquals(2, calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the track ID goes in the path`() {
        var asked: String? = null
        val finder = newFinder(fakeUrl { asked = it; 200 to found })
        runBlocking { finder.find(id) }
        assertEquals("${SpotifyCanvasFinder.BASE_URL}/provider/canvaslist/$id", asked)
    }

    @Test
    fun `live lookup finds a known canvas`() {
        assumeTrue(System.getenv("RUN_SPOTIFY_CANVAS_TEST") == "1")
        val finder = SpotifyCanvasFinder(OkHttpClient(), memoFile = null)
        // Harry Styles, "As It Was".
        val url = runBlocking { finder.find(id) }
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://") && url.endsWith(".mp4"))
    }

    /** A finder with no wait before its retry. */
    private fun newFinder(
        client: OkHttpClient,
        memoFile: File? = null,
        now: () -> Long = System::currentTimeMillis,
    ) = SpotifyCanvasFinder(client, memoFile, now, retryDelayMs = 0)

    /** A client whose every call is answered by [answer] (status, body), given the URL asked. */
    private fun fakeUrl(answer: (String) -> Pair<Int, String>): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val (code, body) = answer(chain.request().url.toString())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun fake(answer: () -> Pair<Int, String>): OkHttpClient = fakeUrl { answer() }
}
