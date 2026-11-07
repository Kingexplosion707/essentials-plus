package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcRedSyncTimeoutTest {
    @Test fun modelSyncOutlastsTheLookupDeadline() {
        val lookup = OkHttpClient.Builder().readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        val sync = LrcRedLyricsProvider(lookup, Gson()).syncClient
        assertEquals(TimeUnit.SECONDS.toMillis(LrcRedLyricsProvider.SYNC_CALL_TIMEOUT_S).toInt(), sync.callTimeoutMillis)
        assertTrue(sync.callTimeoutMillis > sync.readTimeoutMillis)
    }
}
