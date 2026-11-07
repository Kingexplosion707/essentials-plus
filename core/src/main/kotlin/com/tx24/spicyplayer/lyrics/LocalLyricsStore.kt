package com.tx24.spicyplayer.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/**
 * The Lyrics Manager's saved songs: TTML files the user imported for a song, kept until they
 * delete them. A saved song's lyrics beat every online source and the lyrics cache.
 *
 * Songs are keyed on title + artist as the sources are asked for them (cleaned up, lower case),
 * like the lyrics cache, since players fill the rest of a song's details in over several updates.
 * Each song is `<key>.ttml` beside an optional `<key>.jpg` cover, listed in `index.json`.
 */
class LocalLyricsStore(private val dir: File) {
    data class Entry(
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val savedAt: Long,
    )

    private val lock = Any()

    /** Every saved song, newest first. */
    fun entries(): List<Entry> = synchronized(lock) { readIndex() }.sortedByDescending { it.savedAt }

    /** The TTML saved for [title] by [artist], or null. */
    fun get(title: String, artist: String): String? = raw(keyOf(title, artist))

    fun raw(key: String): String? = runCatching { ttmlFile(key).readText() }.getOrNull()

    fun contains(title: String, artist: String): Boolean = ttmlFile(keyOf(title, artist)).isFile

    /**
     * Saves [ttml] for the song looked up as [lookupTitle] by [lookupArtist], replacing what it
     * had. [title], [artist] and [album] are what the list shows: the player's own names.
     */
    fun put(
        lookupTitle: String,
        lookupArtist: String,
        title: String,
        artist: String,
        album: String,
        ttml: String,
        now: Long = System.currentTimeMillis(),
    ): Entry =
        synchronized(lock) {
            dir.mkdirs()
            val entry = Entry(keyOf(lookupTitle, lookupArtist), title, artist, album, now)
            ttmlFile(entry.key).writeText(ttml)
            writeIndex(readIndex().filter { it.key != entry.key } + entry)
            entry
        }

    fun remove(key: String) = synchronized(lock) {
        ttmlFile(key).delete()
        coverFile(key).delete()
        writeIndex(readIndex().filter { it.key != key })
    }

    /** Where the song's cover thumbnail goes; written by whoever has the picture. */
    fun coverFile(key: String) = File(dir, "$key.jpg")

    private fun ttmlFile(key: String) = File(dir, "$key.ttml")

    // Read and written as a JSON tree, not by reflection: the release build renames fields, and an
    // index it couldn't read back would break the list for good.
    private fun readIndex(): List<Entry> {
        val array = runCatching { JsonParser.parseString(File(dir, INDEX).readText()).asJsonObject.getAsJsonArray("entries") }
            .getOrNull()
        val listed = (array ?: JsonArray()).mapNotNull { element ->
            runCatching {
                val o = element.asJsonObject
                Entry(
                    key = o.get("key").asString,
                    title = o.get("title")?.asString.orEmpty(),
                    artist = o.get("artist")?.asString.orEmpty(),
                    album = o.get("album")?.asString.orEmpty(),
                    savedAt = o.get("savedAt")?.asLong ?: 0L,
                )
            }.getOrNull()
        }
            // An entry whose file went missing is gone.
            .filter { ttmlFile(it.key).isFile }
        return listed + orphans(listed.mapTo(HashSet()) { it.key })
    }

    /** Saved files the index lost track of: still listed, so they can be found and deleted. */
    private fun orphans(listed: Set<String>): List<Entry> =
        dir.listFiles { file -> file.extension == "ttml" && file.nameWithoutExtension !in listed }.orEmpty()
            .map { Entry(it.nameWithoutExtension, "Unknown song", "", "", it.lastModified()) }

    private fun writeIndex(entries: List<Entry>) {
        val file = File(dir, INDEX)
        val temp = File(dir, "$INDEX.tmp")
        val array = JsonArray()
        entries.forEach { entry ->
            array.add(JsonObject().apply {
                addProperty("key", entry.key)
                addProperty("title", entry.title)
                addProperty("artist", entry.artist)
                addProperty("album", entry.album)
                addProperty("savedAt", entry.savedAt)
            })
        }
        temp.writeText(JsonObject().apply { add("entries", array) }.toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        private const val INDEX = "index.json"

        fun keyOf(title: String, artist: String): String {
            val identity = listOf(title, artist).joinToString("\u001f") { it.trim().lowercase() }
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
