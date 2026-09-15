package dev.foldprobe.fold

import dev.foldprobe.animation.DuoLayout
import dev.foldprobe.animation.RevealSweep
import org.junit.Assert.*
import org.junit.Test

class RevealSweepTest {
    @Test fun restingSurfacesAreSharpAndExistingInnerGroupIsAlwaysProtected() {
        for (i in 0..100) {
            val x = i / 100f
            assertEquals(0f, RevealSweep.mask(0f, true).weight(x), .00001f)
            assertEquals(0f, RevealSweep.mask(1f, false).weight(x), .00001f)
            for (slot in 0..15) {
                val icon = DuoLayout.icon(slot, x, false)
                assertEquals(0f, RevealSweep.mask(x, false).weight(icon.x), 0f)
            }
        }
    }
    @Test fun coverSoftensFromRightAndInnerResolvesFromRight() {
        val cover = RevealSweep.mask(.30f, true)
        assertTrue(cover.weight(.95f) > .9f)
        assertTrue(cover.weight(.56f) < .1f)
        val inner = RevealSweep.mask(.60f, false)
        assertTrue(inner.weight(.05f) > .9f)
        assertTrue(inner.weight(.44f) < .1f)
    }
    @Test fun oppositeTransitionsRemainContinuousAndReversibleAtEveryPosition() {
        for (outer in listOf(false, true)) for (column in 0..20) {
            val x = column / 20f
            val forward = (0..1000).map { RevealSweep.mask(it / 1000f, outer).weight(x) }
            forward.zipWithNext().forEach { (a, b) ->
                assertTrue(kotlin.math.abs(a - b) < .02f)
                assertTrue(if (outer) b >= a - .00001f else b <= a + .00001f)
                assertEquals(1f, b + (1f - b), .000001f)
            }
            assertEquals(forward, (1000 downTo 0).map { RevealSweep.mask(it / 1000f, outer).weight(x) }.reversed())
        }
    }
    @Test fun invalidInputsDoNotCreateInvalidMasks() {
        for (outer in listOf(false, true)) for (p in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 2f)) {
            val mask = RevealSweep.mask(p, outer)
            assertTrue(mask.low.isFinite() && mask.high > mask.low)
            for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, .5f, 2f)) {
                assertTrue(mask.weight(x).isFinite() && mask.weight(x) in 0f..1f)
            }
        }
    }
}
