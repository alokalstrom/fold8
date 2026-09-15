package dev.foldprobe.fold

import org.junit.Assert.*
import org.junit.Test

class AngleMathTest {
    @Test fun physicalEndpointsAndReversalArePreserved() {
        assertEquals(0f, AngleMath.normalize(0f)!!, 0f)
        assertEquals(1f, AngleMath.normalize(180f)!!, 0f)
        val sweep = listOf(0f, 45f, 90f, 60f, 180f).map { AngleMath.normalize(it)!! }
        assertTrue(sweep[3] < sweep[2])
        assertEquals(1f / 3f, sweep[3], 0.0001f)
    }
    @Test fun invalidReadingsCannotBecomeProgress() {
        assertNull(AngleMath.normalize(Float.NaN))
        assertNull(AngleMath.normalize(Float.POSITIVE_INFINITY))
        assertNull(AngleMath.normalize(10f, 4f, 4f))
        assertNull(AngleMath.normalize(10f, 0f, Float.NaN))
    }
    @Test fun outOfRangeValuesClampAndReverseCalibrationIsExplicit() {
        assertEquals(0f, AngleMath.normalize(-2f)!!, 0f)
        assertEquals(1f, AngleMath.normalize(185f)!!, 0f)
        assertEquals(0.5f, AngleMath.normalize(50f, 100f, 0f)!!, 0f)
    }
    @Test fun timingUsesSensorTimestampsAndCountsActualDistinctValues() {
        val stats = SampleStats()
        listOf(0f, 10f, 10f, 5f, 15f).forEachIndexed { i, value -> stats.add(value, 1_000_000_000L + i * 10_000_000L) }
        assertEquals(5L, stats.count)
        assertEquals(4, stats.distinctCount)
        assertEquals(100.0, stats.hz, 0.001)
        assertEquals(10_000_000L, stats.lastDeltaNs)
        assertEquals(2, stats.reversals)
    }
    @Test fun singleAndInvalidSamplesDoNotPretendToHaveGranularity() {
        val stats = SampleStats()
        stats.add(Float.NaN, 100L)
        assertEquals(0, stats.distinctCount)
        assertEquals(0.0, stats.hz, 0.0)
        repeat(50) { stats.add(90f, 100L) }
        assertEquals(1, stats.distinctCount)
        assertEquals(0.0, stats.hz, 0.0)
    }
}
