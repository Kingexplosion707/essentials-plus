package com.tx24.spicyplayer.network.data.blend

import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParser
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values are the original blend's output for the same syllables. */
class BlendDonorsTest {

    /** One syllable every half second, each 0.4s long, like the Python run that made the expectations. */
    private fun syls(vararg spec: Pair<String, Boolean>) =
        spec.mapIndexed { i, (text, pow) -> BlendSyllable(text, i * 0.5, i * 0.5 + 0.4, pow) }

    private fun assertSyls(expected: List<Triple<String, Double, Double>>, got: List<BlendSyllable>) {
        assertEquals(expected.map { it.first }, got.map { it.text })
        for ((e, g) in expected.zip(got)) {
            assertTrue("${g.text} ${g.start}", abs(e.second - g.start) < 1e-9)
            assertTrue("${g.text} ${g.end}", abs(e.third - g.end) < 1e-9)
        }
    }

    @Test
    fun `an inline bracket becomes a capitalised backing group`() {
        val (lead, bg) = BlendDonors.splitBackground(syls("Do" to false, "(hey)" to false, "anybody" to false, "make" to false, "it" to false))
        assertEquals(listOf("Do", "anybody", "make", "it"), lead.map { it.text })
        assertEquals(1, bg.size)
        assertSyls(listOf(Triple("Hey", 0.58, 0.82)), bg[0].syllables)
    }

    @Test
    fun `fullwidth brackets and a line that opens with one`() {
        val (lead, bg) = BlendDonors.splitBackground(syls("best" to false, "（Hahahaha）" to false))
        assertEquals(listOf("best"), lead.map { it.text })
        assertSyls(listOf(Triple("Hahahaha", 0.54, 0.86)), bg.single().syllables)

        val (lead2, bg2) = BlendDonors.splitBackground(syls("（Why）" to false, "Why" to false, "was" to false, "it" to false, "easy" to false))
        assertEquals(listOf("Why", "was", "it", "easy"), lead2.map { it.text })
        assertSyls(listOf(Triple("Why", 0.08, 0.32)), bg2.single().syllables)
    }

    @Test
    fun `a bracket spread over several syllables and one closed inside a syllable`() {
        val (lead, bg) = BlendDonors.splitBackground(syls("I" to false, "said" to false, "(oh" to false, "my" to false, "god)" to false, "again" to false))
        assertEquals(listOf("I", "said", "again"), lead.map { it.text })
        assertSyls(listOf(Triple("Oh", 1.0 + 0.4 / 3, 1.4), Triple("my", 1.5, 1.9), Triple("god", 2.0, 2.3)), bg.single().syllables)

        val (lead2, bg2) = BlendDonors.splitBackground(syls("said" to false, "(hey)to" to true, "you" to false))
        assertSyls(listOf(Triple("said", 0.0, 0.4), Triple("to", 0.5 + 0.4 * 5 / 7, 0.9), Triple("you", 1.0, 1.4)), lead2)
        assertTrue(lead2[1].partOfWord)
        assertSyls(listOf(Triple("Hey", 0.5 + 0.4 / 7, 0.5 + 0.4 * 4 / 7)), bg2.single().syllables)
    }

    @Test
    fun `a bracket that never closes is not a group`() {
        val (lead, bg) = BlendDonors.splitBackground(syls("Phone" to false, "(" to false))
        assertEquals(listOf("Phone"), lead.map { it.text })
        assertTrue(bg.isEmpty())
    }

    @Test
    fun `credits and the title card come off, the lyric stays`() {
        val doc = BlendDoc(listOf(
            BlendLine("Made Up - Nobody", 0.0, 3.0),
            BlendLine("作词 : Somebody", 3.0, 4.0),
            BlendLine("Composed by: Somebody", 4.0, 5.0),
            BlendLine("Walking down the river road", 10.0, 13.0),
        ))
        val shaped = BlendDonors.shape(doc, "Made Up", "Nobody")!!
        assertEquals(listOf("Walking down the river road"), shaped.lines.map { BlendText.lineText(it) })
    }

    @Test
    fun `a blend's TTML reads back as the same lines, words and backing vocals`() {
        val lead = listOf(
            BlendSyllable("Walk", 10.0, 10.3, partOfWord = true),
            BlendSyllable("ing", 10.3, 10.6, partOfWord = false),
            BlendSyllable("home", 10.6, 11.2, partOfWord = false),
        )
        val bg = BlendGroup(listOf(BlendSyllable("Hey", 11.3, 11.6, false)), 11.3, 11.6)
        val doc = BlendDoc(listOf(
            BlendLine("Walking home", 10.0, 11.2, BlendGroup(lead, 10.0, 11.2), listOf(bg), oppositeAligned = true),
            BlendLine("Only a line", 12.0, 14.0),
            BlendLine("Nobody placed this one", null, null),
            BlendLine("Last & <least>", 20.0, 21.0, BlendGroup(listOf(BlendSyllable("Last & <least>", 20.0, 21.0, false)), 20.0, 21.0)),
        ))
        val parsed = TtmlLyricsParser.parse(BlendDocuments.toTtml(doc, BlendQuality.SYLLABLE).byteInputStream())
        val leads = parsed.lines.filter { it.role == LineRole.LEAD }
        assertEquals(listOf("Walk", "ing", "home"), leads[0].words.map { it.text })
        assertEquals(listOf(false, true, false), leads[0].words.map { it.isPartOfWord })
        assertTrue(leads[0].oppositeAligned)
        val backing = parsed.lines.single { it.role == LineRole.BACKGROUND }
        assertEquals("Hey", backing.words.single().text)
        assertEquals(11_300L, backing.startMs)
        assertEquals(listOf("Only a line"), leads[1].words.map { it.text })
        assertEquals(12_000L to 14_000L, leads[1].startMs to leads[1].endMs)
        // An untimed line sits in the gap its neighbours leave rather than at 0:00.
        assertEquals(14_000L to 20_000L, leads[2].startMs to leads[2].endMs)
        assertEquals("Last & <least>", leads[3].words.single().text)
    }
}
