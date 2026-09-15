package dev.foldprobe.fold

import dev.foldprobe.animation.CoverPerspective
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CoverPerspectiveTest {
    @Test fun restingTouchableAndUnselectedSurfacesRetainTheirGeometry() {
        for (degrees in listOf(0f, 1f, 5f, 165f, 180f))
            assertIdentity(CoverPerspective.projection(degrees / 180f, true, true))
        for (step in 0..180) {
            assertIdentity(CoverPerspective.projection(step / 180f, false, true))
            assertIdentity(CoverPerspective.projection(step / 180f, true, false))
        }
        for (p in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 2f))
            assertIdentity(CoverPerspective.projection(p, true, true))
    }

    @Test fun projectionHasNoHolesFoldoverOrHingeDriftAcrossTheFullCycle() {
        for (degrees in 0..180) {
            val projection = CoverPerspective.projection(degrees / 180f, true, true)
            for (row in 0..20) {
                val v = row / 20f
                var lastX = -1f
                for (column in 0..40) {
                    val (x, y) = projection.sample(column / 40f, v)
                    assertTrue(x.isFinite() && y.isFinite())
                    assertTrue(x in 0f..1f && y in 0f..1f)
                    assertTrue(x > lastX)
                    lastX = x
                    if (column == 0) { assertEquals(0f, x, 0f); assertEquals(v, y, .00001f) }
                }
            }
        }
    }

    @Test fun pausedAndReversedMotionRetracesAContinuousBoundedProjection() {
        val points = (0..1800).map {
            CoverPerspective.projection(it / 1800f, true, true).sample(.9f, .1f)
        }
        points.zipWithNext().forEach { (a, b) ->
            assertTrue(abs(a.first - b.first) < .002f)
            assertTrue(abs(a.second - b.second) < .002f)
        }
        for (i in 1800 downTo 0)
            assertEquals(points[i], CoverPerspective.projection(i / 1800f, true, true).sample(.9f, .1f))
        val peak = CoverPerspective.projection(70f / 180f, true, true).sample(1f, 0f)
        assertTrue(peak.first in .8f.. .9f) // Deliberately partial compensation, not inverse-cosine stretching.
        assertTrue(peak.second in 0f.. .05f)
    }

    private fun assertIdentity(projection: CoverPerspective.Projection) {
        assertEquals(.2f, projection.sample(.2f, .7f).first, .00001f)
        assertEquals(.7f, projection.sample(.2f, .7f).second, .00001f)
    }
}
