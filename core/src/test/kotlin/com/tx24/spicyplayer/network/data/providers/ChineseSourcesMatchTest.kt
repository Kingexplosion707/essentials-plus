package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.LyricsLookupRequest
import com.tx24.spicyplayer.network.data.ProviderResult
import com.tx24.spicyplayer.network.data.RemoteLyricsProvider
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Misses at Kugou, QQ Music and NetEase for songs they have, which were then cached as misses.
 * A request without a length (a queue entry fetched ahead, or a player that sends the length after
 * the title) used to rule out every song, and a throttled QQ search read as "no such song". The
 * responses below are trimmed from what the services answer for "39" by d0tc0mmie (113 s on
 * Spotify), with the lyrics replaced.
 */
class ChineseSourcesMatchTest {
    private val withoutLength = LyricsLookupRequest("d0tc0mmie", "39", "", 0)
    private val withLength = LyricsLookupRequest("d0tc0mmie", "39", "39", 113)
    private val otherLength = LyricsLookupRequest("d0tc0mmie", "39", "39", 200)

    @Test fun anUnknownLengthRulesNothingOut() {
        assertTrue(withoutLength.lengthMatches(113))
        assertTrue(withLength.lengthMatches(0))
        assertTrue(withLength.lengthMatches(110))
        assertTrue(withLength.lengthMatches(121))
        assertFalse(withLength.lengthMatches(122))
        assertFalse(withLength.lengthMatches(220))
    }

    @Test fun kugouMatchesWithoutALength() = runBlocking {
        val provider = KugouLyricsProvider(client(::kugou), Gson())
        assertTrue(provider.fetch(withoutLength) is ProviderResult.Hit)
        assertTrue(provider.fetch(withLength) is ProviderResult.Hit)
        assertEquals(ProviderResult.Miss, provider.fetch(otherLength))
    }

    @Test fun qqMusicMatchesWithoutALength() = runBlocking {
        val provider = QqMusicLyricsProvider(client(::qq), Gson())
        assertTrue(provider.fetch(withoutLength) is ProviderResult.Hit)
        assertTrue(provider.fetch(withLength) is ProviderResult.Hit)
        assertEquals(ProviderResult.Miss, provider.fetch(otherLength))
    }

    @Test fun aThrottledQqSearchIsNoAnswerRatherThanAMiss() = runBlocking {
        // QQ answers some searches, in bursts, with code 2001 and an empty list. As a miss it was
        // cached and never asked again; unanswered, it is asked on the next lookup.
        val throttled = """{"code":0,"req_1":{"code":2001,"data":{"body":{"song":{"list":[]}},"code":0}}}"""
        val result = QqMusicLyricsProvider(client { throttled }, Gson()).fetch(withLength)
        assertTrue(result is ProviderResult.Unavailable && result.retryable)
        val nothing = """{"code":0,"req_1":{"code":0,"data":{"body":{"song":{"list":[]}},"code":0}}}"""
        assertEquals(ProviderResult.Miss, QqMusicLyricsProvider(client { nothing }, Gson()).fetch(withLength))
    }

    @Test fun netEaseMatchesWithoutALength() = runBlocking {
        val provider: RemoteLyricsProvider = NetEaseLyricsProvider(client(::netEase), Gson())
        assertTrue(provider.fetch(withoutLength) is ProviderResult.Hit)
        assertTrue(provider.fetch(withLength) is ProviderResult.Hit)
        assertEquals(ProviderResult.Miss, provider.fetch(otherLength))
    }

    private fun kugou(request: Request): String = when (request.url.host) {
        "mobileservice.kugou.com" -> """{"status":1,"data":{"total":2,"info":[
            {"songname":"39","singername":"d0tc0mmie","duration":113,"hash":"535E5156D643612C4496FF66277704C0"},
            {"songname":"39","singername":"sasakure.UK、DECO*27、初音ミク","duration":220,"hash":"758AB68461901A8B67210C519E338FDC"}]}}"""
        "krcs.kugou.com" -> """{"status":200,"candidates":[{"id":"551063440","accesskey":"A8B4992B9281384E252BA93279701BDE",
            "singer":"d0tc0mmie","song":"39","duration":113000}]}"""
        "lyrics.kugou.com" -> """{"status":200,"fmt":"krc","content":"${krc("[1434,2172]<0,554,0>39 <554,654,0>- <1208,964,0>d0tc0mmie\n[13930,3233]<0,1600,0>la <1600,1633,0>la")}"}"""
        else -> error("unexpected ${request.url}")
    }

    private fun qq(request: Request): String {
        val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
        return when {
            "SearchCgiService" in body -> """{"code":0,"req_1":{"code":0,"data":{"body":{"song":{"list":[
                {"name":"39","singer":[{"name":"d0tc0mmie"}],"interval":110,"mid":"000rBGIu4IYC95"},
                {"name":"39","singer":[{"name":"sasakure.UK"},{"name":"DECO*27"},{"name":"初音未来"}],"interval":220,"mid":"003s3sPu1lAGn2"}]}}}}}"""
            "PlayLyricInfo" in body -> """{"code":0,"req":{"code":0,"data":{"lyric":"[1177,2739]39 (1177,766)- (1943,300)d0tc0mmie(2243,1673)\n[14170,3000]la (14170,1500)la(15670,1500)"}}}"""
            else -> error("unexpected ${request.url} $body")
        }
    }

    private fun netEase(request: Request): String = when (request.url.encodedPath) {
        "/api/cloudsearch/pc" -> """{"code":200,"result":{"songs":[
            {"id":2718281828,"name":"39","ar":[{"name":"d0tc0mmie"}],"dt":113218}]}}"""
        "/api/song/lyric" -> """{"code":200,"lrc":{"lyric":"[00:01.43]39 - d0tc0mmie\n[00:13.93]la la\n[00:17.46]la la la"}}"""
        else -> error("unexpected ${request.url}")
    }

    private fun client(answer: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(answer(request).toResponseBody("application/json".toMediaType())).build()
    }.build()

    /** [text] the way Kugou's download serves a KRC: "krc1", then the deflated text XORed with its key. */
    private fun krc(text: String): String {
        val key = byteArrayOf(0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69)
        val deflated = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
        val masked = ByteArray(deflated.size) { i -> (deflated[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return Base64.getEncoder().encodeToString("krc1".toByteArray() + masked)
    }
}
