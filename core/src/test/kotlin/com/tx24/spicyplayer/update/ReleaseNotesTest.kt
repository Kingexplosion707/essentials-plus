package com.tx24.spicyplayer.update

import com.tx24.spicyplayer.update.Inline.Span
import com.tx24.spicyplayer.update.ReleaseNotes.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private fun fixture(version: String) =
        ReleaseNotes.parse(javaClass.getResourceAsStream("/release-notes/v$version.md")!!.bufferedReader().readText())

    @Test
    fun `a release page reads as its sections, in order`() {
        val notes = fixture("0.16.0")
        assertEquals(listOf("New", "Changed", "Fixed", "Known limitations"), notes.sections.map { it.title })
        assertEquals(listOf(Kind.New, Kind.Changed, Kind.Fixed, Kind.Other), notes.sections.map { it.kind })
        assertEquals(listOf(1, 3, 16, 1), notes.sections.map { it.entries.size })
    }

    @Test
    fun `a bullet splits into its bold lead and the rest`() {
        val changed = fixture("0.16.1").sections.first()
        assertEquals("Uses less battery.", changed.entries[0].lead)
        assertTrue(changed.entries[0].body.startsWith("The lyrics screen draws"))
        // A lead that runs on into the sentence keeps no full stop.
        assertEquals("The source picker says \"Still checking…\"", changed.entries[2].lead)
        assertTrue(changed.entries[2].body.startsWith("next to sources"))
    }

    @Test
    fun `a bullet without a lead keeps all of its text`() {
        val fixed = fixture("0.16.1").sections.last()
        val plain = fixed.entries.first { it.lead == null }
        assertEquals("The widget no longer looks up lyrics in the background with the screen off", plain.body)
    }

    @Test
    fun `the full changelog line and bare text are left out`() {
        val notes = ReleaseNotes.parse("Intro text\n## Fixed\n- One\n\n**Full Changelog**: https://x/compare/a...b\n- Full Changelog: y")
        assertEquals(listOf(ReleaseNotes.Entry(null, "One")), notes.sections.single().entries)
    }

    @Test
    fun `bullets before any heading form an untitled section`() {
        val section = ReleaseNotes.parse("- First\n* Second").sections.single()
        assertNull(section.title)
        assertEquals(2, section.entries.size)
    }

    @Test
    fun `an empty body is empty`() {
        assertTrue(ReleaseNotes.parse("").isEmpty)
        assertTrue(ReleaseNotes.parse("## New\n\n**Full Changelog**: x").isEmpty)
    }

    @Test
    fun `inline marks become spans`() {
        assertEquals(
            listOf(
                Span("Widget lyrics squeezed ("),
                Span("#44", url = "https://github.com/spicylyrics/mobile/issues/44"),
                Span("). Stamps like "),
                Span("<00:01.50>", code = true),
                Span(" and "),
                Span("bold", bold = true),
                Span(" text"),
            ),
            Inline.spans("Widget lyrics squeezed ([#44](https://github.com/spicylyrics/mobile/issues/44)). Stamps like `<00:01.50>` and **bold** text"),
        )
    }

    @Test
    fun `unclosed marks and non-links stay as written`() {
        assertEquals(listOf(Span("a ** b ` c [d](e)")), Inline.spans("a ** b ` c [d](e)"))
        assertEquals(listOf(Span("x", url = "https://a.b")), Inline.spans("[x](<https://a.b>)"))
    }
}
