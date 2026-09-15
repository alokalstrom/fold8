package dev.foldprobe.fold

import dev.foldprobe.animation.CoverPerspective
import dev.foldprobe.animation.DuoLayout
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

    @Test fun raysAndClampedEdgeSamplesStayFiniteWithoutFoldoverOrHingeDrift() {
        for (degrees in 0..180) {
            val projection = CoverPerspective.projection(degrees / 180f, true, true)
            for (row in 0..20) {
                val v = row / 20f
                var lastX = -1f
                for (column in 0..40) {
                    val (x, y) = projection.sample(column / 40f, v)
                    assertTrue(x.isFinite() && y.isFinite())
                    assertTrue(x in 0f..1.03f && y in -.23f..1f)
                    // Above-image rays form the black wedge; remaining source coordinates clamp.
                    assertTrue(x.coerceIn(0f, 1f).isFinite() && y.coerceIn(0f, 1f).isFinite())
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
        assertTrue(peak.first in .2f.. .5f)
        assertTrue(peak.second < 0f) // Top edge samples above the image: on-screen content contracts.
    }

    @Test fun samplerMatchesIndependentRayPlaneIntersectionAtComparisonAngles() {
        val width = DuoLayout.COVER_WIDTH.toDouble()
        val height = DuoLayout.HEIGHT.toDouble()
        val eye = doubleArrayOf(width / 2, height, CoverPerspective.VIEW_DISTANCE.toDouble())
        for (angle in listOf(30, 45, 60)) {
            val radians = Math.toRadians(angle.toDouble())
            val projection = CoverPerspective.projection(angle / 180f, true, true)
            for (u in listOf(0f, .25f, .5f, .75f, 1f)) for (v in listOf(0f, .25f, .5f, 1f)) {
                val panelPoint = doubleArrayOf(u * width * kotlin.math.cos(radians),
                    v * height, u * width * kotlin.math.sin(radians))
                val ray = DoubleArray(3) { panelPoint[it] - eye[it] }
                val distance = -eye[2] / ray[2]
                val hit = DoubleArray(3) { eye[it] + distance * ray[it] }
                val sample = projection.sample(u, v)
                assertEquals((hit[0] / width).toFloat(), sample.first, .00001f)
                assertEquals((hit[1] / height).toFloat(), sample.second, .00001f)
            }
            assertTrue(projection.skew < 0f)
        }
    }

    @Test fun upperAndLowerRowsBothSlopeDownAndRevealATopWedge() {
        for (angle in listOf(15, 30, 45, 60, 90, 120, 150)) {
            val projection = CoverPerspective.projection(angle / 180f, true, true)
            for (sourceRow in listOf(.1f, .3f, .7f, .9f)) {
                val samples = (0..20).map { column ->
                    val u = column / 20f
                    // Solve the inverse sampler for the drawn position of this source row.
                    var lo = 0f; var hi = 1f
                    repeat(24) {
                        val mid = (lo + hi) / 2f
                        if (projection.sample(u, mid).second < sourceRow) lo = mid else hi = mid
                    }
                    (lo + hi) / 2f
                }
                samples.zipWithNext().forEach { (left, right) -> assertTrue(right > left) }
                assertEquals(sourceRow, samples.first(), .00001f)
            }
            for (u in listOf(.25f, .5f, .75f, 1f)) {
                val topEdge = -projection.skew * u
                assertEquals(0f, projection.sample(u, topEdge).second, .00001f)
                assertTrue(projection.sample(u, topEdge - .001f).second < 0f)
                assertTrue(projection.sample(u, topEdge + .001f).second > 0f)
                assertEquals(1f, projection.sample(u, 1f).second, 0f)
            }
        }
    }

    private fun assertIdentity(projection: CoverPerspective.Projection) {
        assertEquals(.2f, projection.sample(.2f, .7f).first, .00001f)
        assertEquals(.7f, projection.sample(.2f, .7f).second, .00001f)
    }
}
