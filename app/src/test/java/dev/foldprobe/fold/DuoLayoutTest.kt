package dev.foldprobe.fold

import dev.foldprobe.animation.DuoLayout
import org.junit.Assert.*
import org.junit.Test

class DuoLayoutTest {
    @Test fun unfoldingRetainsTheOriginalGroupOnTheRightWithoutIconReflow() {
        val closed = (0..15).map { DuoLayout.icon(it, 0f, false) }
        for (step in 0..100) {
            val current = (0..15).map { DuoLayout.icon(it, step / 100f, false) }
            assertEquals(closed, current)
            assertEquals(4, current.map { it.x }.toSet().size)
            assertEquals(4, current.map { it.y }.toSet().size)
            current.forEach { assertTrue(it.x * DuoLayout.WIDTH >= DuoLayout.COVER_LEFT + 5f) }
        }
    }
    @Test fun endpointsAreUndeformedAndIconsRemainDistinct() {
        for (p in listOf(0f, 1f)) {
            val layout = (0..15).map { DuoLayout.icon(it, p, false) }
            assertEquals(16, layout.map { it.x to it.y }.toSet().size)
            layout.forEach { assertEquals(1f, it.scale, .0001f); assertEquals(0f, it.rotationY, .0001f) }
        }
    }
    @Test fun bothPanelsProjectToTheSameScenePosition() {
        for (p in listOf(0f, .25f, .5f, 1f)) for (i in 0..15) {
            val inner = DuoLayout.icon(i, p, false); val outer = DuoLayout.icon(i, p, true)
            assertEquals(inner.x * DuoLayout.WIDTH,
                outer.x * DuoLayout.COVER_WIDTH + DuoLayout.COVER_LEFT, .0001f)
            assertEquals(inner.y, outer.y, .0001f)
        }
    }
    @Test fun closedIconsFitInsideTheCoverWithRoomForLabels() {
        (0..15).forEach {
            val x = DuoLayout.icon(it, 0f, true).x * DuoLayout.COVER_WIDTH
            assertTrue(x >= 5f && x <= DuoLayout.COVER_WIDTH - 5f)
        }
    }
    @Test fun reportedPanelsKeepApproximatelyTheSamePhysicalIconSize() {
        val inner = DuoLayout.viewport(2448f, 1848f, false)
        val cover = DuoLayout.viewport(1248f, 1972f, true)
        val innerIconMm = 7.2f * inner.scale / 403.76105f * 25.4f
        val coverIconMm = 7.2f * cover.scale / 428.36755f * 25.4f
        assertEquals(innerIconMm, coverIconMm, .15f)
        assertTrue(inner.top >= 0f && cover.top >= 0f)
    }
    @Test fun reversingRetracesSamePositions() {
        val forwards = (0..10).map { DuoLayout.icon(7, it / 10f, false) }
        val backwards = (10 downTo 0).map { DuoLayout.icon(7, it / 10f, false) }
        assertEquals(forwards, backwards.reversed())
    }
    @Test fun invalidProgressNeverCreatesInvalidTransforms() {
        for (p in listOf(Float.NaN, Float.POSITIVE_INFINITY, -3f, 5f)) {
            val t = DuoLayout.icon(0, p, false)
            assertTrue(t.x.isFinite() && t.y.isFinite() && t.scale.isFinite())
        }
    }
}
