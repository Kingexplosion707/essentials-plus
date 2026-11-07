package com.tx24.spicyplayer.network.model

import com.google.gson.annotations.SerializedName

data class SongLyricsNetwork(
    @SerializedName("id")
    val lyricsId: Int,

    @SerializedName("plainLyrics")
    val plainLyrics: String?,

    @SerializedName("syncedLyrics")
    val syncedLyrics: String?
)

data class LrclibSearchEntry(
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val duration: Double?,
    val plainLyrics: String?,
    val syncedLyrics: String?,
)
