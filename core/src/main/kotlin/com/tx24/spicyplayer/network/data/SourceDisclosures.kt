package com.tx24.spicyplayer.network.data

/**
 * What switching a source on shares, said once before it's switched on. Out of the box only
 * Spicy Lyrics and the lyrics APIs that say any app may use them are on; every other source asks
 * first.
 */
data class SourceDisclosure(
    /** The source or setting it's for. */
    val id: String,
    val name: String,
    /** Who gets asked, in plain words. */
    val recipient: String,
    /** What they get. */
    val sends: String = TITLE_ARTIST,
    /** Anything else worth knowing first. */
    val note: String? = null,
    val descriptionOverride: String? = null,
) {
    val description: String
        get() = descriptionOverride ?: buildString {
            append("Turning this on sends $sends to $recipient each time it looks for lyrics. ")
            append("No account or device details are sent. ")
            append("This source isn't run by Spicy Lyrics, so its own terms apply.")
            if (note != null) append(" ").append(note)
        }
}

private const val TITLE_ARTIST = "the song's title and artist"
private const val WITH_LENGTH = "the song's title, artist and length"
/** The Chinese catalogues' fallback for names they don't find ([com.tx24.spicyplayer.network.data.providers.NativeNames]). */
private const val ITUNES_NAMES = "When it doesn't find a song by its name, the title and artist also go to Apple's iTunes search, " +
    "to look up the name the song has in Japan, Korea, Taiwan or Hong Kong."

object SourceDisclosures {
    /** [service] is the host an OpenAI-compatible translator is at. */
    fun translation(provider: com.tx24.spicyplayer.translation.TranslationProvider, human: Boolean = true, service: String? = null): SourceDisclosure {
        val recipient = when (provider) {
            com.tx24.spicyplayer.translation.TranslationProvider.Google -> "Google Translate"
            com.tx24.spicyplayer.translation.TranslationProvider.DeepL -> "DeepL"
            com.tx24.spicyplayer.translation.TranslationProvider.OpenAi -> "the AI service at ${service ?: "the address you set"}"
            com.tx24.spicyplayer.translation.TranslationProvider.Gemini -> "Google's Gemini API"
            com.tx24.spicyplayer.translation.TranslationProvider.GeminiNano -> "Gemini Nano on this phone"
        }
        val how = when (provider) {
            com.tx24.spicyplayer.translation.TranslationProvider.Google -> "This uses Google's free web service, not a paid API; no API key is needed. "
            com.tx24.spicyplayer.translation.TranslationProvider.DeepL -> "Your DeepL API key authenticates requests to DeepL. "
            com.tx24.spicyplayer.translation.TranslationProvider.OpenAi -> "Your API key, if you set one, authenticates the requests; your account with that service is billed for them. "
            com.tx24.spicyplayer.translation.TranslationProvider.Gemini -> "Your Gemini API key authenticates the requests; Google may use them as its terms for your key's tier say. "
            com.tx24.spicyplayer.translation.TranslationProvider.GeminiNano -> "The lines never leave this phone. "
        }
        return SourceDisclosure(
            id = com.tx24.spicyplayer.translation.TranslationConsent.id(provider, human, service),
            name = "translation", recipient = recipient + if (human) " and Genius" else "",
            descriptionOverride = "Translation sends the song's original lyric lines, including background vocals, to $recipient. " +
                "Your target and lyrics languages are sent too. " + how +
                (if (human) "Human translations are checked first: the song's title and artist are sent to Genius, and its original and translated lyric pages are fetched. Only songs with gaps in the human translation need $recipient. " else "") +
                "Their own terms and privacy policies apply. You can turn translation off at any time.",
        )
    }
    /** The setting that lays human-written romanizations from Genius over the lyrics. */
    const val GENIUS_ROMANIZATION_ID = "genius_romanization"
    /** Stage art set to Spotify Canvas, which asks Spicy Lyrics' Canvas service. */
    const val SPOTIFY_CANVAS_ID = "spotify_canvas"

    private val byId = listOf(
        SourceDisclosure("rmm_revival", "RMM Revival", "Apple's iTunes search and RMM Revival (rmmreviv.al)"),
        SourceDisclosure("kugou", "Kugou", "Kugou's servers in China", WITH_LENGTH, note = ITUNES_NAMES),
        SourceDisclosure("qq_music", "QQ Music", "Tencent's QQ Music servers in China", note = ITUNES_NAMES),
        SourceDisclosure("kuwo", "Kuwo", "Kuwo's servers in China", note = ITUNES_NAMES),
        SourceDisclosure("netease", "NetEase", "NetEase Cloud Music's servers in China", note = ITUNES_NAMES),
        SourceDisclosure("genius", "Genius", "Genius (genius.com)"),
        SourceDisclosure("youtube_transcript", "YouTube transcripts", "YouTube's search (youtube.com)"),
        SourceDisclosure(GENIUS_ROMANIZATION_ID, "Human romanizations", "Genius (genius.com)",
            note = "It's only asked for songs in a script that gets romanized."),
        SourceDisclosure(SPOTIFY_CANVAS_ID, "Spotify Canvas", "Spicy Lyrics' Canvas service (spicylyrics.org)",
            descriptionOverride = "While the cover stage shows, Spotify Canvas sends each song's Spotify track ID to " +
                "Spicy Lyrics' Canvas service (spicylyrics.org), which asks Spotify for the song's Canvas, and plays " +
                "the video it points to from Spotify's servers. No account or device details are sent. The service " +
                "is unofficial and may stop working."),
    ).associateBy(SourceDisclosure::id)

    /**
     * The disclosure for provider [descriptor], or null when it needs none: providers on by
     * default, blends (built from other answers, never asked), and the user's own sources, whose
     * address they typed. Agreed to once, it covers the provider in every source it serves.
     */
    fun forSource(descriptor: LyricsSourceDescriptor): SourceDisclosure? = when {
        descriptor.defaultEnabled || descriptor.upstreamFamily == "blend" || descriptor.upstreamFamily == "custom" -> null
        else -> byId[descriptor.id] ?: SourceDisclosure(descriptor.id, descriptor.displayName, descriptor.displayName)
    }

    fun forId(id: String): SourceDisclosure? = byId[id]

    /**
     * The title and text telling a user what an update switched off ([ids]: sources, the Genius
     * romanization switch and Musixmatch, which is gone), or null when it switched off nothing.
     */
    fun switchedOffNotice(ids: Set<String>): Pair<String, String>? {
        val names = ids.filter { it != MUSIXMATCH_ID }.map { byId[it]?.name ?: it }.sorted()
        val gone = MUSIXMATCH_ID in ids
        if (names.isEmpty() && !gone) return null
        val text = buildString {
            if (names.isNotEmpty()) {
                append("This update switched off ${names.joinWords()}. ")
                append(if (names.size == 1) "It's" else "They're")
                append(" run by services that haven't said apps may use them, so each now says what it sends ")
                append("before you switch it back on in Settings → Sources. Your other sources and their order are as you left them.")
            }
            if (gone) {
                if (isNotEmpty()) append(" ")
                append("Musixmatch is gone: the app could only reach it by posing as Musixmatch's own app.")
            }
        }
        return (if (names.isEmpty()) "Musixmatch is gone" else "Some lyrics sources were switched off") to text
    }

    private fun List<String>.joinWords() = if (size <= 1) joinToString() else dropLast(1).joinToString() + " and " + last()

    private const val MUSIXMATCH_ID = "musixmatch"
}
