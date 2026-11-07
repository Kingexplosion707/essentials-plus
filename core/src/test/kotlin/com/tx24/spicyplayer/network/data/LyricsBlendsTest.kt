package com.tx24.spicyplayer.network.data

import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsBlendsTest {
    private val request = LyricsLookupRequest("Nobody", "Made Up", "", 60)

    // Invented lyrics. The donor spells some words its own way and runs on a clock 0.12s later.
    private val lines = listOf(
        "Walking down the river road",
        "Counting every stone I know",
        "Hey, hey, the water's cold",
        "I'll carry you, I'll carry you home",
        "Walking down the river road",
        "Counting every stone I know",
        "Somewhere the lanterns glow",
        "I'll carry you, I'll carry you home",
    )

    private val baseLrc = lines.mapIndexed { i, text -> "[00:${"%02d".format(Locale.ROOT, 10 + i * 5)}.00]$text" }.joinToString("\n")

    /** Word-timed TTML as our NetEase converter writes it: one span per word, spaces between. */
    private val donorTtml = buildString {
        append("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>""")
        lines.forEachIndexed { i, text ->
            val start = 10.12 + i * 5
            val spelled = text.replace("I'll", "Ill").replace("water's", "waters")
            val words = spelled.split(' ')
            val step = 3.6 / words.size
            append("<p begin=\"${t(start)}\" end=\"${t(start + 3.6)}\">")
            words.forEachIndexed { w, word ->
                append("<span begin=\"${t(start + w * step)}\" end=\"${t(start + (w + 1) * step)}\">$word</span>")
                if (w < words.lastIndex) append(' ')
            }
            append("</p>")
        }
        append("</div></body></tt>")
    }

    @Test
    fun `a line-synced base gets the donor's word timing in its own words`() = runBlocking {
        val result = source().resolveLyrics(request, blendOn)
            as RemoteLyricsResolution.Found
        assertEquals("blend_netease", result.selection.source.id)
        assertEquals(RemoteLyricsQuality.WORD_SYNCED, result.selection.quality)
        assertEquals("Spicy + NetEase", result.selection.payload.attribution?.originName)

        val parsed = TtmlLyricsParser.parse(result.selection.payload.ttmlLyrics!!.byteInputStream())
        val leads = parsed.lines.filter { it.role == LineRole.LEAD }
        assertEquals(lines.size, leads.size)
        // The base's spelling, the donor's clock.
        val carry = leads[3]
        assertEquals("I'll carry you, I'll carry you home", carry.words.joinToString(" ") { it.text })
        assertEquals(25_120L, carry.startMs)
        assertEquals(25_120L, carry.words.first().startMs)
        assertTrue(carry.words.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
    }

    @Test
    fun `without the blend the donor wins on timing alone`() = runBlocking {
        val result = source().resolveLyrics(request, RemoteLyricsPolicy()) as RemoteLyricsResolution.Found
        assertEquals("netease", result.selection.source.id)
    }

    @Test
    fun `a word-synced answer ranked above stands the blend down`() = runBlocking {
        val result = source(baseWordSynced = true)
            .resolveLyrics(request, blendOn) as RemoteLyricsResolution.Found
        assertEquals("spicy", result.selection.source.id)
        assertEquals(ProviderAttemptOutcome.SKIPPED, result.attempts.first { it.sourceId == "blend_netease" }.outcome)
    }

    /** lrc.red's model sync of the song: Apple's words, the model's word timing, ends running long. */
    private val modelTtml = donorTtml.replace("Ill", "I'll").replace("waters", "water's")
        .let { ttml -> Regex("""end="([\d.]+)s"""").replace(ttml) { m -> "end=\"${t(m.groupValues[1].toDouble() + 2.0)}\"" } }

    @Test
    fun `lrc dot red's model sync stands in as Apple's lines where nothing above is Apple's`() {
        val model = RemoteLyricsPayload(ttmlLyrics = modelTtml, attribution = LyricsAttribution("lrc.red", originName = LyricsOrigins.LRC_RED_AI_NAME))
        val lrcRed = LyricsSourceDescriptor(RemoteLyricsSource.LRC_RED_ID, "lrc.red", 45, setOf(LyricsCapability.WORD_SYNC))
        val result = LyricsBlends.build(
            LyricsBlends.byId("blend_netease")!!, request, above = emptyList(), fallbackBase = null,
            donors = mapOf("netease" to RemoteLyricsPayload(ttmlLyrics = donorTtml)), modelBase = lrcRed to model,
        ) { if (it == "netease") "NetEase" else it } as ProviderResult.Hit
        assertEquals("Apple Music + NetEase", result.payload.attribution?.originName)
        val leads = TtmlLyricsParser.parse(result.payload.ttmlLyrics!!.byteInputStream()).lines.filter { it.role == LineRole.LEAD }
        assertEquals(lines.size, leads.size)
        assertEquals("I'll carry you, I'll carry you home", leads[3].words.joinToString(" ") { it.text })

        // An Apple Music answer above keeps its own lines: the model isn't asked to stand in.
        val apple = RemoteLyricsPayload(syncedLyrics = baseLrc.replace("Somewhere the lanterns glow", "Somewhere a lantern glows"), attribution = LyricsAttribution("Spicy Lyrics", originName = "Apple Music"))
        val spicy = LyricsSourceDescriptor("spicy", "Spicy", 1, setOf(LyricsCapability.LINE_SYNC))
        val kept = LyricsBlends.build(
            LyricsBlends.byId("blend_netease")!!, request, above = listOf(spicy to apple), fallbackBase = null,
            donors = mapOf("netease" to RemoteLyricsPayload(ttmlLyrics = donorTtml)), modelBase = lrcRed to model,
        ) { if (it == "netease") "NetEase" else it } as ProviderResult.Hit
        val keptLines = TtmlLyricsParser.parse(kept.payload.ttmlLyrics!!.byteInputStream()).lines.map { l -> l.words.joinToString(" ") { it.text } }
        assertTrue(keptLines.toString(), "Somewhere a lantern glows" in keptLines)
    }

    @Test
    fun `a model sync as a base keeps its line starts and ends each at the next`() {
        val doc = com.tx24.spicyplayer.network.data.blend.BlendDocuments.fromTtml(modelTtml)!!
        val starts = com.tx24.spicyplayer.network.data.blend.BlendDocuments.startsOnly(doc)!!
        assertEquals(lines, starts.lines.map { it.text })
        assertTrue(starts.lines.all { it.lead == null })
        assertEquals(15.12, starts.lines[0].end!!, 1e-9)
        assertEquals(starts.lines.last().start!! + 6.0, starts.lines.last().end!!, 1e-9)
    }

    @Test
    fun `a much steadier blend goes first within its run of blends only`() {
        // (id, is a blend, wander)
        fun pick(vararg tied: Triple<String, Boolean, Double?>) =
            LyricsBlends.steadiestFirst(tied.toList(), { it.second }, { it.third }).map { it.first }
        // Margin 0.015 at small figures: 0.051 vs 0.030 moves, 0.051 vs 0.040 doesn't.
        assertEquals(listOf("qq", "ne"), pick(Triple("ne", true, 0.051), Triple("qq", true, 0.030)))
        assertEquals(listOf("ne", "qq"), pick(Triple("ne", true, 0.051), Triple("qq", true, 0.040)))
        // The margin scales: at 0.14 it is 0.028, so 0.02 steadier is left to the order (あの夢をなぞって).
        assertEquals(listOf("ne", "kw"), pick(Triple("ne", true, 0.14), Triple("kw", true, 0.12)))
        // Never past an answer that isn't a blend.
        assertEquals(listOf("ne", "netease", "kw"), pick(Triple("ne", true, 0.14), Triple("netease", false, null), Triple("kw", true, 0.01)))
        // Unmeasured blends stay where they are.
        assertEquals(listOf("a", "b"), pick(Triple("a", true, null), Triple("b", true, 0.01)))
    }

    /** The NetEase blend switched on in NetEase, below the base. */
    private val blendOn = RemoteLyricsPolicy(
        sourceOrder = listOf("spicy", LyricsOrigins.NETEASE, LyricsOrigins.LRCLIB),
        pairs = mapOf(SourcePair(LyricsOrigins.NETEASE, "blend_netease").key to true),
    )

    @Test
    fun `each blend stands in the source its word timing comes from, the filling ones first`() {
        fun providers(id: String) = LyricsOrigins.BUILT_IN.single { it.id == id }.providerIds
        assertEquals(listOf("blend_kugou", "kugou", "lrcmux"), providers(LyricsOrigins.KUGOU))
        assertEquals(listOf("blend_netease_kugou", "blend_netease_qq", "blend_netease_kuwo", "blend_netease", "netease"), providers(LyricsOrigins.NETEASE))
        assertEquals(listOf("blend_qq", "qq_music"), providers(LyricsOrigins.QQ_MUSIC))
        assertEquals(listOf("blend_kuwo", "kuwo"), providers(LyricsOrigins.KUWO))
    }

    @Test
    fun `a blend is only live while every donor is asked`() {
        val providers = listOf("kugou", "netease").map { LyricsSourceDescriptor(it, it, 1, setOf(LyricsCapability.WORD_SYNC)) }
        val blend = SourcePair(LyricsOrigins.NETEASE, "blend_netease_kugou").key to true
        val on = SourcePair(LyricsOrigins.KUGOU, "kugou").key to true
        assertEquals(listOf("blend_netease_kugou"), SourceRanking(providers, RemoteLyricsPolicy(pairs = mapOf(blend, on))).liveBlends.map { it.id })
        val kugouOff = SourcePair(LyricsOrigins.KUGOU, "kugou").key to false
        assertEquals(emptyList<String>(), SourceRanking(providers, RemoteLyricsPolicy(pairs = mapOf(blend, kugouOff))).liveBlends.map { it.id })
    }

    private fun source(baseWordSynced: Boolean = false) = RemoteLyricsSource(
        setOf(
            provider("spicy", 1, "Spicy", ProviderResult.Hit(
                if (baseWordSynced) RemoteLyricsPayload(ttmlLyrics = donorTtml) else RemoteLyricsPayload(syncedLyrics = baseLrc),
            )),
            provider("netease", 60, "NetEase", ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = donorTtml))),
            provider("lrclib", 100, "LRCLIB", ProviderResult.Miss),
        ),
        ProviderCooldownTracker(),
    )

    private fun provider(id: String, priority: Int, name: String, result: ProviderResult) = object : RemoteLyricsProvider {
        override val descriptor = LyricsSourceDescriptor(id, name, priority, setOf(LyricsCapability.WORD_SYNC))
        override suspend fun fetch(request: LyricsLookupRequest) = result
    }

    private fun t(seconds: Double) = "%.3fs".format(Locale.ROOT, seconds)
}
