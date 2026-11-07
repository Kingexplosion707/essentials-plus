package com.tx24.spicyplayer.lyrics.spicy.models

/** Non-timed attribution displayed after the vocal timeline. */
data class LyricsFooter(
    val songwriters: List<String> = emptyList(),
    val provenance: LyricsProvenance? = null,
    /** Sync credits: who made the timing and who uploaded/submitted it. */
    val maker: LyricsCredit? = null,
    val uploader: LyricsCredit? = null,
    /** Who translated the lines on screen ("Genius + Google"), and where their romanization comes from. */
    val translationCredit: String? = null,
    val romanizationCredit: String? = null,
) {
    /**
     * One short line for where the lyrics come from, for the controls and the floating window:
     * "Community lyrics · Made by @name", or "Provided by Apple Music". Null with no provenance.
     */
    fun miniCredit(): MiniCredit? {
        val p = provenance ?: return null
        val person = maker ?: uploader
        if (p.provider == "Spicy Lyrics" && person != null) {
            return MiniCredit("Community lyrics · Made by", person.name, person.avatarUrl?.takeIf { it.startsWith("https://") })
        }
        val origin = p.contributor?.takeIf(String::isNotBlank)
            ?.let { if (it == "Spicy Lyrics Community") "Spicy Lyrics" else it } ?: p.provider
        return MiniCredit("Provided by $origin")
    }

    /**
     * The lines shown after the lyrics: writers, then the Spicy Lyrics community block, or for other sources
     * the lyric source followed by its own credits.
     */
    fun lines(): List<FooterLine> = buildList {
        // Reference order (Syllable/Line/Static applyers): Credits, LyricsProvider, SongInfo.
        if (songwriters.isNotEmpty()) add(FooterLine("Written by: ${songwriters.joinToString(", ")}", FooterLine.Kind.WRITERS))
        provenance?.let { p ->
            // The catalogue that answered, as in "Provided by: Apple Music"; a
            // Spicy Lyrics community sync is "Spicy Lyrics" there.
            val origin = p.contributor?.takeIf(String::isNotBlank)
                ?.let { if (it == "Spicy Lyrics Community") "Spicy Lyrics" else it } ?: p.provider
            add(FooterLine("Provided by: $origin", FooterLine.Kind.PROVIDER))
        }
        translationCredit?.let { add(FooterLine("Translation from: $it", FooterLine.Kind.TEXTS)) }
        romanizationCredit?.let { add(FooterLine("Romanization from: $it", FooterLine.Kind.TEXTS)) }
        val spicyCommunity = provenance?.provider == "Spicy Lyrics" && (maker != null || uploader != null)
        if (spicyCommunity) add(FooterLine("These lyrics have been provided by our community", FooterLine.Kind.NOTE))
        maker?.let { add(it.line("Made by")) }
        uploader?.let { add(it.line(if (maker != null) "Uploaded by" else if (spicyCommunity) "Made by" else "Submitted by")) }
    }
}

/** The one-line credit: [prefix], then a credited person's avatar and @[name] when there is one. */
data class MiniCredit(val prefix: String, val name: String? = null, val avatarUrl: String? = null) {
    val text: String get() = if (name == null) prefix else "$prefix @$name"
}

/** A person credited for a sync, with the links the source gave for them. */
data class LyricsCredit(val name: String, val profileUrl: String? = null, val avatarUrl: String? = null) {
    fun line(role: String) = FooterLine(
        "$role @$name",
        FooterLine.Kind.CONTRIBUTOR,
        label = role,
        name = name,
        profileUrl = profileUrl?.takeIf(::isTrustedProfile),
        avatarUrl = avatarUrl?.takeIf { it.startsWith("https://") },
    )

    private companion object {
        val TRUSTED_HOSTS = setOf("spicylyrics.org", "www.spicylyrics.org", "github.com", "unison.boidu.dev")

        /** Only https profiles on hosts we know are opened from a tap. */
        fun isTrustedProfile(url: String): Boolean =
            url.startsWith("https://") && url.removePrefix("https://").substringBefore('/').lowercase() in TRUSTED_HOSTS
    }
}

data class FooterLine(
    val text: String,
    val kind: Kind,
    val profileUrl: String? = null,
    val avatarUrl: String? = null,
    /** For [Kind.CONTRIBUTOR]: drawn as "label " then a bold, underlined "@name". */
    val label: String? = null,
    val name: String? = null,
    /** For [Kind.COMMUNITY]: the people credited, drawn after [text] on one flowing line. */
    val parts: List<FooterLine> = emptyList(),
) {
    /** [TEXTS]: where the translation and romanization on screen come from. */
    enum class Kind { WRITERS, PROVIDER, NOTE, CONTRIBUTOR, COMMUNITY, TEXTS }
}

data class LyricsProvenance(
    val provider: String,
    val contributor: String? = null,
)

/** Normalized lyric input shared by static, line-synced, and syllable-synced renderers. */
data class LyricsDocument(
    val lines: List<Line>,
    val footer: LyricsFooter = LyricsFooter(),
    /** Synchronization granularity; drives which render path is used. */
    val type: LyricsType = LyricsType.Syllable,
    /** Song URI + source + SHA-256 of the raw lyric payload. */
    val documentId: String = "",
    /** The language of the lines' own translations, when the document says. */
    val translationLanguage: String? = null,
) {
    val songwriters: List<String> get() = footer.songwriters

    /** True if any word carries romanized text (from TTML/API metadata or on-device romanization). */
    val hasTransliteration: Boolean
        get() = lines.any { line -> line.words.any { it.romanizedText != null } }
}

typealias ParsedLyrics = LyricsDocument
