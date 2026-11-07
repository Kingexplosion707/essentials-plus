package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackMatcher

/**
 * Which songs a lyrics folder's files are for, and which file a song gets. Android-free: the
 * folder itself is read by [LyricsFolder].
 *
 * A file names its song with its own tags (LRC `[ti:]`/`[ar:]`, an AMLL TTML's `musicName` and
 * `artists` meta) or, failing those, its file name: "Artist - Title", or the other way round,
 * since both are common. A song matches a file on the same title (as the sources are asked for
 * it, cleaned up) and an artist that one of them lists.
 */
object LyricsFolderIndex {
    data class Song(val title: String, val artist: String)

    /** One lyrics file: [id] is how the folder finds it again. */
    data class File(val id: String, val name: String, val modified: Long, val songs: List<Song>)

    val EXTENSIONS = setOf("ttml", "lrc")

    fun isLyricsFile(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** The songs file [name] with [content] could be for, best guess first. */
    fun songsOf(name: String, content: String): List<Song> {
        val tagged = when (name.substringAfterLast('.', "").lowercase()) {
            "lrc" -> LrcConverter.tags(content).let { tags ->
                if (tags.title != null && tags.artist != null) listOf(Song(tags.title, tags.artist)) else emptyList()
            }
            else -> amllSongs(content)
        }
        val base = name.substringBeforeLast('.').replace(TRACK_NUMBER, "").trim()
        val parts = base.split(SEPARATOR, limit = 2).map(String::trim)
        val named = if (parts.size == 2 && parts.all(String::isNotBlank)) {
            listOf(Song(parts[1], parts[0]), Song(parts[0], parts[1]))
        } else emptyList()
        return (tagged + named).distinct()
    }

    /** The file for [title] by [artist] in [files], if one names that song. */
    fun find(files: Collection<File>, title: String, artist: String): File? {
        val titles = titleKeys(title)
        val artists = artistKeys(artist)
        if (artists.isEmpty()) return null
        return files.firstOrNull { file ->
            file.songs.any { song -> titleKey(song.title) in titles && artistKeys(song.artist).any { it in artists } }
        }
    }

    /** The request's title, also without a trailing " - Remastered 2011" or "(feat. X)". */
    private fun titleKeys(title: String): Set<String> = setOf(
        titleKey(title),
        titleKey(title.split(SEPARATOR, limit = 2).first()),
        titleKey(title.replace(TRAILING_BRACKETS, "")),
    ).filterTo(HashSet(), String::isNotBlank)

    private fun titleKey(title: String) = SpotifyTrackMatcher.normalize(title)

    /** Each artist the byline lists: "A, B & C feat. D" is four. */
    private fun artistKeys(artist: String): Set<String> =
        (artist.split(ARTIST_SPLIT) + artist).map(SpotifyTrackMatcher::normalize).filterTo(HashSet(), String::isNotBlank)

    /** An AMLL TTML DB file's own names: `<amll:meta key="musicName" value="…"/>`, one per artist. */
    private fun amllSongs(ttml: String): List<Song> {
        val meta = AMLL_META.findAll(ttml.take(AMLL_HEAD_CHARS)).map { it.groupValues[1] to RemoteLyricsAdapter.decodeEntities(it.groupValues[2]) }.toList()
        val titles = meta.filter { it.first == "musicName" }.map { it.second }
        val artists = meta.filter { it.first == "artists" }.map { it.second }
        return titles.flatMap { title -> artists.map { Song(title, it) } }
    }

    private val SEPARATOR = Regex("\\s+[-–—]\\s+")
    private val TRACK_NUMBER = Regex("^(?:\\d{1,3}(?:-\\d{1,3})?\\s*[-.)_]\\s*|0\\d\\s+)(?=\\S)")
    private val TRAILING_BRACKETS = Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]\\s*$")
    private val ARTIST_SPLIT = Regex("\\s*(?:,|&|/|;|\\bfeat\\.?|\\bft\\.?|\\bx\\b|、)\\s*", RegexOption.IGNORE_CASE)
    private val AMLL_META = Regex("""<amll:meta\s+key="([^"]+)"\s+value="([^"]*)"""")
    // The meta sits in the head; reading past it would only find lyrics.
    private const val AMLL_HEAD_CHARS = 16_000
}
