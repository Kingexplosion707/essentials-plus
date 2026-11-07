package com.tx24.spicyplayer.network.data.providers

import com.tx24.spicyplayer.network.data.ProviderFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderErrorsTest {
    @Test fun kuwoSearchParsesNestedSingleQuotedRecords() {
        val raw = "{'HIT':'1','abslist':[{'ARTIST':'Jay&nbsp;Chou','DURATION':'269','MUSICRID':'MUSIC_1'," +
            "'SONGNAME':'Qing&nbsp;Tian','audiobookpayinfo':{'download':'0'}}]}"
        assertEquals(listOf(KuwoSong("Qing Tian", "Jay Chou", 269, "MUSIC_1")), kuwoSearchResults(raw))
    }

    @Test fun kuwoLyricsAreGb18030() {
        val bytes = byteArrayOf(0x84.toByte(), 0x31, 0x95.toByte(), 0x33) + "[00:01.00]晴天\r\n".toByteArray(charset("GB18030"))
        assertEquals("[00:01.00]晴天\n", decodeKuwoLrc(bytes))
    }

    @Test fun kuwoLrcxBecomesKrcByItsOctalKey() {
        // [kuwo:34] is octal 28: a word starts |a+b|/4 ms into its line and lasts |a-b|/16 ms.
        val lrcx = "[kuwo:34]\n[00:01.5]<600,-200>Hi<1000,200>there\n[00:03.00]<0,0>翻译\n"
        assertEquals("[1500,350]<100,50,0>Hi<300,50,0>there", KuwoLyrics.krc(lrcx))
        // Plain LRC has no tokens; a key that is no octal number is no LRCX either.
        assertEquals(null, KuwoLyrics.krc("[00:01.00]Hi"))
        assertEquals(null, KuwoLyrics.krc("[kuwo:9]\n[00:01.00]<1,1>Hi"))
        assertEquals("[00:01.00]Hi", KuwoLyrics.lrc("[kuwo:34]\n[00:00.00]以下歌词翻译由文曲大模型提供\n[00:01.00]Hi"))
    }

    @Test fun httpFailuresKeepTheirActualCategory() {
        assertEquals(ProviderFailureCategory.SERVER, ProviderHttpException("QQ", 500).unavailable().category)
        assertEquals(ProviderFailureCategory.AUTHENTICATION, ProviderHttpException("Genius", 401).unavailable().category)
    }
}
