package com.tx24.spicyplayer.lyrics.spicy.models

import com.tx24.spicyplayer.lyrics.spicy.models.Line
import com.tx24.spicyplayer.lyrics.spicy.models.LineRole
import com.tx24.spicyplayer.lyrics.spicy.models.Word
import com.tx24.spicyplayer.lyrics.spicy.models.buildDisplayTimeline
import com.tx24.spicyplayer.lyrics.spicy.models.interludeDotTimes
import org.junit.Assert.assertEquals
import org.junit.Test

class RendererTimelineTest {
    @Test fun insertsInterludeDotsForVocalGaps() {
        val lines = listOf(
            Line(listOf(Word("one", 0L, 1_000L)), 0L, 1_000L),
            Line(listOf(Word("two", 5_000L, 6_000L)), 5_000L, 6_000L),
        )

        val timeline = buildDisplayTimeline(lines, minimalMode = false)
        assertEquals(3, timeline.size)
        assertEquals(LineRole.INTERLUDE, timeline[1].role)
        assertEquals(1_000L, timeline[1].startMs)
        assertEquals(5_000L, timeline[1].endMs)
    }

    @Test fun aBackgroundLeadInStaysUnderItsLineNotUnderTheDots() {
        val lines = listOf(
            Line(listOf(Word("one", 0L, 1_000L)), 0L, 1_000L),
            Line(listOf(Word("two", 6_000L, 7_000L)), 6_000L, 7_000L),
            // "(oh)" leading into line two: starts in the gap, before its own lead.
            Line(listOf(Word("oh", 5_000L, 6_500L)), 5_000L, 6_500L, role = LineRole.BACKGROUND),
        )

        val roles = buildDisplayTimeline(lines, minimalMode = false).map { it.role to it.startMs }
        assertEquals(
            listOf(
                LineRole.LEAD to 0L,
                LineRole.INTERLUDE to 1_000L,
                LineRole.LEAD to 6_000L,
                LineRole.BACKGROUND to 5_000L,
            ),
            roles,
        )
    }

    @Test fun introDotsRunUntilTheFirstLeadSyllable() {
        // Bologna 2: the first line starts at 3.1s for a background "Pluh", but its lead vocal
        // (and the API's song StartTime) is at 12.4s. The line keeps its own start.
        val lines = listOf(
            Line(listOf(Word("I", 12_423L, 13_914L)), 3_115L, 13_914L),
            Line(listOf(Word("Pluh", 3_115L, 3_730L)), 3_115L, 3_730L, role = LineRole.BACKGROUND),
            Line(listOf(Word("again", 13_880L, 15_430L)), 13_880L, 15_430L),
        )

        val timeline = buildDisplayTimeline(lines, minimalMode = false)
        assertEquals(LineRole.INTERLUDE, timeline[0].role)
        assertEquals(0L to 12_423L, timeline[0].startMs to timeline[0].endMs)
        assertEquals(3_115L, timeline[1].startMs)
        assertEquals(LineRole.BACKGROUND, timeline[2].role)
        assertEquals(3_115L, timeline[2].startMs)
        assertEquals(4, timeline.size)
    }

    @Test fun interludeDotsFollowSpicyLyricsTiming() {
        // 9s gap from 10s: thirds of 3000ms, each shifted by -550/3, last ending 550ms early.
        assertEquals(
            listOf(10_000L to 12_817L, 12_817L to 15_633L, 15_633L to 18_450L),
            interludeDotTimes(10_000L, 19_000L),
        )
    }

    @Test fun minimalModeWaitsFiveSecondsForDotsAndHoldsLinesThroughShorterGaps() {
        val lines = listOf(
            Line(listOf(Word("one", 0L, 1_000L)), 0L, 1_000L),
            Line(listOf(Word("two", 5_000L, 6_000L)), 5_000L, 6_000L),
            Line(listOf(Word("three", 12_000L, 13_000L)), 12_000L, 13_000L),
        )

        val timeline = buildDisplayTimeline(lines, minimalMode = true, holdThroughShortGaps = true)
        // 4s is under Minimal's 5s: no dots, and "one" stays Active until "two" starts.
        assertEquals(listOf(LineRole.LEAD, LineRole.LEAD, LineRole.INTERLUDE, LineRole.LEAD), timeline.map { it.role })
        assertEquals(5_000L, timeline[0].endMs)
        // 6s is an interlude: "two" keeps its own end and the dots start there.
        assertEquals(6_000L, timeline[1].endMs)
        assertEquals(6_000L, timeline[2].startMs)
    }
}
