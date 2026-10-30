package com.autoseries.maker

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesPlannerTest {
    @Test fun twentyMinutesAt50s_gives24Parts() {
        val p = SeriesPlanner.plan(1_200_000, 50, 10)
        assertEquals(24, p.size)
        assertEquals(3, p.last().seriesIndex)
        assertEquals(4, p.last().partInSeries)
        assertEquals(1_200_000L, p.last().endMs)
    }

    @Test fun remainderBecomesFinalShortPart() {
        val p = SeriesPlanner.plan(18 * 60_000L + 20_000L, 50, 10)
        assertEquals(23, p.size)
        assertEquals(20_000L, p.last().durationMs)
    }

    @Test fun noGapsOrOverlaps() {
        val p = SeriesPlanner.plan(777_777, 40, 5)
        for (i in 1 until p.size) assertEquals(p[i - 1].endMs, p[i].startMs)
        assertEquals(0L, p.first().startMs)
        assertEquals(777_777L, p.last().endMs)
    }

    @Test fun tinyTailIsMerged() {
        val p = SeriesPlanner.plan(100_500, 50, 10)
        assertEquals(2, p.size)
        assertEquals(100_500L, p.last().endMs)
    }
}
