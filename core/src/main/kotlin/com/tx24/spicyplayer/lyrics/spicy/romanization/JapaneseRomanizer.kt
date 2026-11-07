package com.tx24.spicyplayer.lyrics.spicy.romanization

import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Japanese through kuroshiro 1.2.0's rules (MIT) over kuromoji (IPADIC), converting
 * `{ to: "romaji", mode: "spaced" }`. Each token is read by its *pronunciation* (so the particle
 * は is "wa" and 東京 is "tōkyō"), through [KanaRomanizer]'s Hepburn, one token per word.
 *
 * The tokenizer loads a bundled dictionary lazily on first use (off the main thread).
 */
object JapaneseRomanizer : Romanizer {
    override val script = Script.JAPANESE

    private val tokenizer: Tokenizer? by lazy {
        try { Tokenizer() } catch (t: Throwable) { null }
    }

    override fun isAvailable(): Boolean = tokenizer != null

    override fun romanize(text: String): String {
        val tk = tokenizer ?: return KanaRomanizer.romanize(text)
        return try {
            tokens(tk, text).joinToString(" ") { token -> KanaRomanizer.romanize(token.spoken()) }
        } catch (t: Throwable) {
            KanaRomanizer.romanize(text)
        }
    }

    /**
     * Romanizes one line's [syllables] with the whole line as tokenizer context, returning one
     * entry per syllable. (Romanizing each syllable alone loses readings when a word is
     * split across syllables: 段|々 → "dan" + "々", 堕|ち → "堕" + "chi".) Here 段々 reads だんだん and the
     * reading is shared back out to the syllables it spans.
     *
     * A syllable that starts a new word (a token of its own) gets a leading space, so the romaji
     * reads and wraps by word ("anata no koto") although the syllables were written glued.
     *
     * A っ ending a syllable keeps the consonant it doubles there (黙っ|て → damat|te, 発|火 →
     * hak|ka), so each piece fills with what is sung in it; before a vowel, a pause or the line's
     * end it is a stop and unwritten (まっ|いっ|か → ma|ik|ka, あっ → a). A kana reading in brackets
     * after kanji (傾(かぶ)けば) is the kanji's, said once.
     */
    fun romanizeLine(syllables: List<String>): List<String> {
        val tk = tokenizer ?: return syllables.map(KanaRomanizer::romanize)
        val text = syllables.joinToString("")
        val owner = syllables.flatMapIndexed { index, s -> List(s.length) { index } }
        val kana = List(syllables.size) { mutableListOf<String>() }
        val startsAt = syllables.runningFold(0) { at, s -> at + s.length }
        val startsWord = BooleanArray(syllables.size)
        return try {
            val tokens = tokens(tk, text)
            val ruby = rubyReadings(text, tokens)
            var lastReading: String? = null
            for (token in tokens) {
                if (token.surface.isNotBlank()) {
                    val syllable = startsAt.indexOf(token.position)
                    if (syllable > 0 && syllable < syllables.size) startsWord[syllable] = true
                }
                val readings = when {
                    // 々 the dictionary didn't read repeats the kanji before it (燦々 → san san).
                    token.surface.all { it == '々' } && token.pronunciation.orEmpty().none(::isKanaChar) && lastReading != null ->
                        token.surface.map { lastReading!! }
                    hasJapanese(token.surface) -> charReadings(token.surface, token.pronunciation ?: token.reading)
                    else -> token.surface.map(Char::toString)
                }.mapIndexed { i, r -> ruby[token.position + i] ?: r }
                readings.lastOrNull { it.isNotEmpty() }?.let { lastReading = it }
                val bySyllable = readings.indices.groupBy { owner[token.position + it] }.toSortedMap()
                for ((syllable, chars) in bySyllable) {
                    val piece = chars.joinToString("") { readings[it] }
                    if (piece.isNotBlank()) kana[syllable] += piece
                }
            }
            val pieces = kana.flatMapIndexed { i, list -> list.map { i to it } }
            val romaji = List(syllables.size) { mutableListOf<String>() }
            pieces.forEachIndexed { k, (syllable, piece) ->
                romaji[syllable] += if (piece.endsWith('ッ')) {
                    // The consonant the stop doubles, from the first sound after it.
                    val next = pieces.getOrNull(k + 1)?.second?.let { KanaRomanizer.romanize(it).trim() }.orEmpty()
                    val doubled = when {
                        next.startsWith("ch") -> "t"
                        next.firstOrNull()?.let { it.isLetter() && it.lowercaseChar() !in "aeiouāēīōūn" } == true -> next.first().toString()
                        else -> ""
                    }
                    KanaRomanizer.romanize(piece.trimEnd('ッ')).trim() + doubled
                } else {
                    KanaRomanizer.romanize(piece).trim()
                }
            }
            romaji.mapIndexed { i, list ->
                val joined = list.filter(String::isNotEmpty).joinToString(" ").trim()
                if (startsWord[i] && joined.isNotEmpty()) " $joined" else joined
            }
        } catch (t: Throwable) {
            syllables.map(KanaRomanizer::romanize)
        }
    }

    private val RUBY = Regex("[(（]([\u3041-\u3096\u30a1-\u30fcー]+)[)）]")

    /**
     * Character index -> reading for kanji read by the kana in brackets after them: the kanji of
     * the word right before the bracket and the brackets read nothing, the kana read themselves.
     */
    private fun rubyReadings(text: String, tokens: List<KToken>): Map<Int, String> = buildMap {
        for (m in RUBY.findAll(text)) {
            val word = tokens.lastOrNull { it.position + it.surface.length == m.range.first } ?: continue
            val kanji = word.surface.takeLastWhile(::isKanji).length
            if (kanji == 0) continue
            for (i in m.range.first - kanji until m.range.first) put(i, "")
            for (i in m.range) put(i, if (i in m.groups[1]!!.range) toKatakana(text[i]).toString() else "")
        }
    }

    /** A kuromoji token after kuroshiro's `patchTokens`. */
    private class KToken(
        var surface: String,
        var reading: String,
        var pronunciation: String?,
        val pos: String,
        val position: Int,
    ) {
        /** What kuroshiro romanizes: the pronunciation of Japanese text, anything else as written. */
        fun spoken(): String = if (hasJapanese(surface)) pronunciation ?: reading else surface
    }

    private fun tokens(tk: Tokenizer, text: String): List<KToken> {
        val tokens = tk.tokenize(text).mapTo(mutableListOf()) { t ->
            val reading = t.reading?.takeIf { it != "*" }
            val pronunciation = t.pronunciation?.takeIf { it != "*" }
            KToken(
                surface = t.surface,
                reading = when {
                    !hasJapanese(t.surface) -> t.surface
                    reading == null -> if (t.surface.all(::isKanaChar)) toKatakana(t.surface) else t.surface
                    else -> toKatakana(reading)
                },
                pronunciation = pronunciation,
                pos = t.partOfSpeechLevel1,
                position = t.position,
            )
        }
        // 助動詞 "う" after a 動詞 joins it as a long vowel (行こう → ikō).
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.pos == "助動詞" && (t.surface == "う" || t.surface == "ウ") && i > 0 && tokens[i - 1].pos == "動詞") {
                val prev = tokens[i - 1]
                prev.surface += "う"
                prev.pronunciation = (prev.pronunciation ?: prev.reading) + "ー"
                prev.reading += "ウ"
                tokens.removeAt(i)
                continue
            }
            i++
        }
        // A 動詞 or 形容詞 ending in っ takes the next token in (言っ|て → itte).
        var j = 0
        while (j < tokens.size) {
            val t = tokens[j]
            if ((t.pos == "動詞" || t.pos == "形容詞") && t.surface.length > 1 &&
                (t.surface.endsWith('っ') || t.surface.endsWith('ッ')) && j + 1 < tokens.size
            ) {
                val next = tokens[j + 1]
                t.surface += next.surface
                t.pronunciation = (t.pronunciation ?: t.reading) + (next.pronunciation ?: next.reading)
                t.reading += next.reading
                tokens.removeAt(j + 1)
                continue
            }
            j++
        }
        return tokens
    }

    /** One kana reading per surface character: kana at the ends read as themselves, the kanji middle shares the rest by mora. */
    fun charReadings(surface: String, reading: String?): List<String> {
        if (reading == null) return surface.map(Char::toString)
        val kata = surface.map(::toKatakana)
        var prefix = 0
        while (prefix < surface.length && prefix < reading.length && isKana(kata[prefix]) && kata[prefix] == reading[prefix]) prefix++
        var suffix = 0
        while (suffix < surface.length - prefix && suffix < reading.length - prefix &&
            isKana(kata[surface.length - 1 - suffix]) && kata[surface.length - 1 - suffix] == reading[reading.length - 1 - suffix]
        ) suffix++
        val middleChars = surface.length - prefix - suffix
        if (middleChars == 0) return kata.map(Char::toString)
        val morae = morae(reading.substring(prefix, reading.length - suffix))
        val middle = List(middleChars) { i ->
            // ponytail: even mora split across kanji, per-kanji readings need a dictionary we don't bundle
            morae.subList(i * morae.size / middleChars, (i + 1) * morae.size / middleChars).joinToString("")
        }
            // A long vowel split off from its mora (と|う read トー) reads as the kana it's written with.
            .mapIndexed { i, r -> if (r.startsWith('ー')) kata[prefix + i] + r.drop(1) else r }
            .toMutableList()
        // A っ is the end of the kanji before it, never the start of the next (発火 → ハッ|カ).
        for (i in 1 until middle.size) {
            while (middle[i].startsWith('ッ')) {
                middle[i - 1] = middle[i - 1] + "ッ"
                middle[i] = middle[i].drop(1)
            }
        }
        return kata.take(prefix).map(Char::toString) + middle + kata.takeLast(suffix).map(Char::toString)
    }

    private fun morae(reading: String): List<String> = buildList {
        for (c in reading) {
            if (isEmpty() || c !in "ャュョァィゥェォヮー") add(c.toString()) else this[lastIndex] += c
        }
    }

    // kuroshiro's hasJapanese: any kanji or kana.
    private fun hasJapanese(s: String) = s.any { isKanaChar(it) || isKanji(it) }
    private fun isKanaChar(c: Char) = c in '぀'..'ゟ' || c in '゠'..'ヿ'
    private fun isKanji(c: Char) = c in '一'..'鿏' || c in '豈'..'﫿' || c in '㐀'..'䶿'
    private fun isKana(c: Char) = c.code in 0x30A1..0x30FC
    private fun toKatakana(c: Char) = if (c.code in 0x3041..0x3096) (c.code + 0x60).toChar() else c
    private fun toKatakana(s: String) = String(CharArray(s.length) { toKatakana(s[it]) })
}
