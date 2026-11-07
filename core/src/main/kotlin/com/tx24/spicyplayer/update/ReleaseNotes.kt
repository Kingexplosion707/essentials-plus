package com.tx24.spicyplayer.update

/**
 * A release's notes as the release page writes them: `## New`, `## Changed`, `## Fixed` (or any
 * other heading) over bullets that open with a bold lead, "**Lead.** What it means". The inline
 * marks stay in [Entry.lead] and [Entry.body] as written; [Inline.spans] reads them.
 */
data class ReleaseNotes(val sections: List<Section>) {
    val isEmpty: Boolean get() = sections.none { it.entries.isNotEmpty() }

    data class Section(
        /** The heading as written; null for bullets before any heading. */
        val title: String?,
        val kind: Kind,
        val entries: List<Entry>,
    )

    enum class Kind { New, Changed, Fixed, Other }

    data class Entry(
        /** The bold opening, without its asterisks; null when the bullet has none. */
        val lead: String?,
        /** The rest of the bullet, still marked up; empty when the lead is all of it. */
        val body: String,
    )

    companion object {
        val EMPTY = ReleaseNotes(emptyList())

        /** Reads a release body. GitHub's "Full Changelog" line and lines that aren't bullets are left out. */
        fun parse(body: String): ReleaseNotes {
            val sections = mutableListOf<Section>()
            var title: String? = null
            var entries = mutableListOf<Entry>()
            fun close() {
                if (entries.isNotEmpty()) sections += Section(title, kindOf(title), entries)
                entries = mutableListOf()
            }
            for (raw in body.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("#") -> {
                        close()
                        title = line.trimStart('#').trim().takeIf { it.isNotEmpty() }
                    }
                    line.startsWith("- ") || line.startsWith("* ") -> {
                        val text = line.drop(2).trim()
                        if (text.isNotEmpty() && !text.contains("Full Changelog")) entries += entry(text)
                    }
                }
            }
            close()
            return ReleaseNotes(sections)
        }

        private fun kindOf(title: String?): Kind = when (title?.lowercase()) {
            "new", "added", "features" -> Kind.New
            "changed", "changes", "improved" -> Kind.Changed
            "fixed", "fixes", "bug fixes" -> Kind.Fixed
            else -> Kind.Other
        }

        /** "**Lead.** rest", "**Lead** rest" or plain text. */
        private fun entry(text: String): Entry {
            if (!text.startsWith("**")) return Entry(null, text)
            val end = text.indexOf("**", 2)
            if (end < 0) return Entry(null, text)
            val lead = text.substring(2, end).trim()
            return Entry(lead.takeIf { it.isNotEmpty() }, text.substring(end + 2).trim())
        }
    }
}

/** Inline marks in release notes: `**bold**`, `` `code` `` and `[text](url)`. */
object Inline {
    data class Span(val text: String, val bold: Boolean = false, val code: Boolean = false, val url: String? = null)

    /** [text] split into runs, with the marks taken off. Unclosed marks stay as written. */
    fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        val plain = StringBuilder()
        var bold = false
        var i = 0
        fun flush() {
            if (plain.isNotEmpty()) out += Span(plain.toString(), bold = bold)
            plain.clear()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("**", i) && (bold || text.indexOf("**", i + 2) > 0) -> {
                    flush(); bold = !bold; i += 2
                }
                c == '`' && text.indexOf('`', i + 1) > 0 -> {
                    flush()
                    val end = text.indexOf('`', i + 1)
                    out += Span(text.substring(i + 1, end), bold = bold, code = true)
                    i = end + 1
                }
                c == '[' && link(text, i) != null -> {
                    flush()
                    val (label, url, end) = link(text, i)!!
                    out += Span(label, bold = bold, url = url)
                    i = end
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return out
    }

    /** `[label](url)` from [start], with the index after it; `<url>` is unwrapped. */
    private fun link(text: String, start: Int): Triple<String, String, Int>? {
        val close = text.indexOf("](", start)
        if (close < 0 || text.indexOf('\n', start).let { it in 0 until close }) return null
        val end = text.indexOf(')', close + 2)
        if (end < 0) return null
        val url = text.substring(close + 2, end).trim().removePrefix("<").removeSuffix(">")
        if (!url.startsWith("http")) return null
        return Triple(text.substring(start + 1, close), url, end + 1)
    }
}
