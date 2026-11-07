package com.tx24.spicyplayer.network.data.blend

import com.tx24.spicyplayer.network.data.RemoteLyricsPayload
import com.tx24.spicyplayer.network.data.blend.BlendText.key
import com.tx24.spicyplayer.network.data.blend.BlendText.lineStart
import com.tx24.spicyplayer.network.data.blend.BlendText.lineText

/**
 * The lines QQ Music, Kugou, Kuwo and NetEase write into a lyric that nobody sings: a
 * "title - artist" card at the top, credit and copyright runs at either end, section headers, and
 * a Chinese translation filed as a line of its own after each sung line.
 */
object LyricsFurniture {
    /** How far in from either end a credit run is looked for. */
    private const val EDGE_REACH = 8
    /** Faster than anybody sings: seconds a syllable. */
    private const val SQUEEZED = 0.06

    private val SECTION_HEAD = Regex(
        "^\\s*[\\[［【(（<]\\s*(?:verse|chorus|pre-?chorus|post-?chorus|bridge|intro" +
            "|outro|hook|drop|refrain|interlude|break(?:down)?|build-?up|instrumental" +
            "|solo|rap|skit|副歌|主歌|间奏|前奏|尾奏|桥段|说唱|念白)\\b[^\\]］】)）>]{0,40}" +
            "[\\]］】)）>]\\s*$" +
            "|^\\s*(?:verse|chorus|pre-?chorus|post-?chorus|bridge|intro|outro|hook" +
            "|refrain|interlude|副歌|主歌|间奏|前奏|尾奏)(?:\\s*\\d+)?\\s*[:：]?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Credits and rights notices: "role：name" in Chinese, OP/SP/ISRC, "no use without permission"… */
    private val EDGE_CREDIT = Regex(
        "^\\s*[一-鿿]{1,6}\\s*[:：]\\s*\\S|^\\s*(?:OP|SP|ISRC)\\s*[:：]" +
            "|未经.{0,24}许可|著作权|版权所有|版权声明|不得.{0,12}(?:翻唱|翻录|使用)" +
            "|TME享有|本歌曲|本作品",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A credit by its role, or by its shape: these catalogues write every credit "Role : Name",
     * with a space before the colon, and the roles are as many as there are instruments. And the
     * studio line that closes such a run, which has no colon.
     */
    private val ROLE_CREDIT = Regex(
        "^\\s*(?:" +
            "[一-鿿]{0,6}?(?:作词|作曲|编曲|制作|出品|监制|录音|混音|母带|吉他|贝斯" +
            "|鼓|键盘|和声|弦乐|人声|策划|统筹|发行|工程师|演奏|词|曲)[一-鿿]{0,2}?" +
            "|lyric(?:s|ist)?|compos(?:ed|er|ition)|writ(?:ten|er)|music" +
            "|arrang(?:ed|er|ement)|produc(?:ed|er|tion)|mix(?:ed|ing)?|talkbox|rap" +
            "|master(?:ed|ing)?|record(?:ed|ing)?|vocals?|performed|engineer(?:ed)?" +
            "|backing vocals?|guitars?|bass|drums|keyboards?|strings?|piano" +
            ")\\s*(?:by)?\\s*[:：]" +
            "|^\\s*\\p{L}[\\p{L}\\p{N}_&/'’.\\- ]{0,38}?\\s[:：]\\s*\\S" +
            // A full-width colon after a Latin role is the catalogue's credit too ("Percussion：David Stewart").
            "|^\\s*[A-Za-z][A-Za-z&/'’.\\- ]{0,38}：\\s*\\S" +
            "|^\\s*(?:recorded|mixed|mastered|engineered|published|administered)\\s+(?:at|by|in)\\b|^\\s*[℗©]",
        RegexOption.IGNORE_CASE,
    )

    private val SCRIPTS = listOf(
        "ko" to Regex("[가-힣ᄀ-ᇿ㄰-㆏]"),
        "ja" to Regex("[぀-ヿㇰ-ㇿｦ-ﾟ]"),
        "zh" to Regex("[㐀-䶿一-鿿豈-﫿]"),
        "latin" to Regex("\\p{L}"),
    )
    /** What counts as one sung unit: a CJK character, or a Latin word. */
    private val SUNG_UNIT = Regex("[가-힣ᄀ-ᇿ㄰-㆏぀-ヿㇰ-ㇿｦ-ﾟ㐀-䶿一-鿿豈-﫿]|[A-Za-zÀ-ɏ']+")
    /** A bracketed "feat. …" ("ft.", "with", 合唱…) after a title. */
    private val FEATURING = Regex("\\s*[(（\\[【]\\s*(?:feat\\.?|ft\\.?|featuring|with|合唱)\\s[^)）\\]】]*[)）\\]】]", RegexOption.IGNORE_CASE)
    private val BRACKETED = Regex("\\s*[(（\\[【][^)）\\]】]*[)）\\]】]")

    /** The indexes of [lines] to keep, in order. */
    fun kept(lines: List<BlendLine>, title: String, artist: String): List<Int> {
        fun text(i: Int) = lineText(lines[i]).trim()
        // Both of its alternatives are anchored at either end, so this is a whole-line match.
        var items = lines.indices.filterNot { SECTION_HEAD.containsMatchIn(text(it)) }
        val dropped = translations(items.map { lines[it] })
        items = items.filterIndexed { k, _ -> k !in dropped }
        // A run of credits from either end may be longer than the reach (BTS's "Dynamite" has 21);
        // a run is unbroken from the end, and the reach only bounds how far in a title card may sit.
        var lo = 0
        while (lo < items.size && (credit(text(items[lo])) || (lo < EDGE_REACH && title.isNotBlank() && card(text(items[lo]), title, artist)))) lo++
        var hi = items.size
        while (hi > lo && credit(text(items[hi - 1]))) hi--
        return items.subList(lo, hi)
    }

    fun clean(doc: BlendDoc, title: String, artist: String): BlendDoc {
        val keep = kept(doc.lines, title, artist)
        return if (keep.size == doc.lines.size) doc else BlendDoc(keep.map(doc.lines::get), doc.songwriters)
    }

    /**
     * [payload] without its furniture, in the form it came: the TTML's `<p>`s, the LRC's rows or
     * the plain text's lines that hold it taken out. Unchanged where nothing is furniture, or where
     * its lines can't be told apart one for one.
     */
    fun clean(payload: RemoteLyricsPayload, title: String, artist: String): RemoteLyricsPayload {
        payload.ttmlLyrics?.takeIf(String::isNotBlank)?.let { ttml ->
            val doc = BlendDocuments.fromTtml(ttml) ?: return payload
            val paragraphs = PARAGRAPH.findAll(ttml).toList()
            if (paragraphs.size != doc.lines.size) return payload
            val keep = kept(doc.lines, title, artist).toSet()
            if (keep.size == doc.lines.size) return payload
            val out = StringBuilder(ttml)
            for ((i, p) in paragraphs.withIndex().reversed()) if (i !in keep) out.delete(p.range.first, p.range.last + 1)
            return payload.copy(ttmlLyrics = out.toString())
        }
        payload.syncedLyrics?.takeIf(String::isNotBlank)?.let { lrc ->
            val doc = BlendDocuments.fromLrc(lrc) ?: return payload
            if (doc.lines.any { it.start == null }) return payload
            val keep = kept(doc.lines, title, artist).toSet()
            if (keep.size == doc.lines.size) return payload
            val gone = doc.lines.filterIndexed { i, _ -> i !in keep }.map { lineStart(it) to lineText(it) }.toSet()
            val rows = lrc.lines().filterNot { raw ->
                val stamps = LRC_STAMP.findAll(raw).toList()
                if (stamps.isEmpty()) return@filterNot false
                val body = raw.substring(stamps.last().range.last + 1).trim()
                stamps.all { m ->
                    val at = m.groupValues[1].toInt() * 60 + (m.groupValues[2].replace(':', '.').toDoubleOrNull() ?: return@all false)
                    (at to body) in gone
                }
            }
            return payload.copy(syncedLyrics = rows.joinToString("\n"))
        }
        payload.plainLyrics?.takeIf(String::isNotBlank)?.let { plain ->
            val lines = plain.lines().map(String::trim).filter(String::isNotEmpty)
            val keep = kept(lines.map { BlendLine(it, null, null) }, title, artist)
            if (keep.size == lines.size) return payload
            return payload.copy(plainLyrics = keep.joinToString("\n") { lines[it] })
        }
        return payload
    }

    private val PARAGRAPH = Regex("<p\\b[^>]*>.*?</p>", RegexOption.DOT_MATCHES_ALL)
    private val LRC_STAMP = Regex("\\[(\\d+):(\\d+(?:[.:]\\d+)?)]")

    private fun credit(text: String) = ROLE_CREDIT.containsMatchIn(text) || EDGE_CREDIT.containsMatchIn(text)

    /** A "title - artist" card, either order, or the names run together. */
    private fun card(text: String, title: String, artist: String): Boolean {
        val k = key(text)
        val a = key(artist)
        for (name in setOf(title, BRACKETED.replace(title, ""))) {
            val t = key(name)
            if (t.isNotEmpty() && (k == a + t || k == t + a)) return true
        }
        val cut = text.lastIndexOf(" - ")
        if (cut < 0) return false
        val head = text.substring(0, cut)
        val tail = text.substring(cut + 3)
        // An exact title stands without the byline, which a catalogue may write in its own script
        // or spell its own way ("dØtcØmmie"); a featured artist it adds in brackets doesn't count.
        return listOf(head to tail, tail to head).any { (song, by) ->
            key(by).isNotEmpty() && sameSong(song, title) &&
                (sameArtist(by, artist) || key(song) == key(title) || key(FEATURING.replace(song, "")) == key(title))
        }
    }

    private fun sameSong(theirs: String, ours: String): Boolean {
        val a = key(theirs)
        val b = key(ours)
        return a.isNotEmpty() && (a == b || key(BRACKETED.replace(theirs, "")) == key(BRACKETED.replace(ours, "")))
    }

    private fun sameArtist(theirs: String, ours: String): Boolean {
        val a = key(theirs)
        val b = key(ours)
        return a.isNotEmpty() && b.isNotEmpty() && (a in b || b in a)
    }

    /** The writing a line is in: ko, ja, zh, latin, or "" for none. */
    private fun script(text: String): String = SCRIPTS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(text) }?.first.orEmpty()

    /**
     * Whether line [i] goes by faster than anybody sings, under [SQUEEZED] a syllable, or,
     * line-timed, shares its stamp with the line before it.
     */
    private fun squeezed(items: List<BlendLine>, i: Int): Boolean {
        val line = items[i]
        val syls = line.lead?.syllables.orEmpty()
        val at = line.start
        val span = when {
            syls.isNotEmpty() -> syls.maxOf(BlendSyllable::end) - syls.minOf(BlendSyllable::start)
            at != null -> {
                val was = if (i > 0) items[i - 1].start else null
                if (was != null && kotlin.math.abs(was - at) < 0.01) return true
                val end = items.drop(i + 1).firstNotNullOfOrNull { n -> n.start?.takeIf { it > at } } ?: line.end ?: return false
                end - at
            }
            else -> return false
        }
        val n = maxOf(syls.size, SUNG_UNIT.findAll(lineText(line)).count())
        return n >= 2 && span / n < SQUEEZED
    }

    /**
     * Indexes of translation lines filed between the sung ones, if [items] has them. Looks for the
     * pattern, not single lines: three or more squeezed lines in a second script right after a
     * line in the first, at least a quarter of all such turns. A real bilingual song has nothing
     * squeezed, and a lone squeezed ad-lib is only one.
     */
    fun translations(items: List<BlendLine>): Set<Int> {
        val said = items.map { script(lineText(it)) }
        val turns = (1 until items.size).filter { said[it].isNotEmpty() && said[it - 1].isNotEmpty() && said[it] != said[it - 1] }
        val fast = turns.filter { squeezed(items, it) }
        if (fast.size < 3) return emptySet()
        val into = fast.groupingBy { said[it] }.eachCount().maxBy { it.value }.key
        val sung = fast.groupingBy { said[it - 1] }.eachCount().maxBy { it.value }.key
        val pairs = turns.filter { said[it] == into && said[it - 1] == sung }.toSet()
        val squeezedPairs = pairs.count { it in fast }
        if (squeezedPairs < 3 || squeezedPairs * 4 < pairs.size) return emptySet()
        return pairs
    }
}
