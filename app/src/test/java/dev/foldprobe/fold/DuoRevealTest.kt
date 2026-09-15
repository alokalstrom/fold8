package dev.foldprobe.fold

import dev.foldprobe.animation.DuoLayout
import dev.foldprobe.animation.DuoReveal
import org.junit.Assert.*
import org.junit.Test

class DuoRevealTest {
    @Test fun visibleRestingSurfacesAreSharpAndRightInnerGroupAlwaysStaysSharp() {
        assertEquals(0f, DuoReveal.appearance(.75f, 0f, true).blurUnits, 0f)
        for (x in listOf(.1f, .25f, .75f)) {
            assertEquals(0f, DuoReveal.appearance(x, 1f).blurUnits, 0f)
        }
        for (step in 0..100) for (index in 0..15) {
            val p = step / 100f
            val x = DuoLayout.icon(index, p, false).x
            assertEquals(0f, DuoReveal.appearance(x, p).blurUnits, 0f)
        }
    }
    @Test fun coverAndIncomingSurfaceHaveOppositeContinuousReversibleTransitions() {
        val cover = (0..100).map { DuoReveal.appearance(.75f, it / 100f, true) }
        val left = (0..100).map { DuoReveal.appearance(.25f, it / 100f) }
        assertTrue(cover[50].blurUnits > 1f)
        assertTrue(left[50].blurUnits > 1f)
        cover.zipWithNext().forEach { (a, b) ->
            assertTrue(b.blurUnits >= a.blurUnits)
            assertTrue(b.blurUnits - a.blurUnits < .15f)
        }
        left.zipWithNext().forEach { (a, b) ->
            assertTrue(b.blurUnits <= a.blurUnits)
            assertTrue(a.blurUnits - b.blurUnits < .15f)
        }
        assertEquals(cover, (100 downTo 0).map { DuoReveal.appearance(.75f, it / 100f, true) }.reversed())
        assertEquals(left, (100 downTo 0).map { DuoReveal.appearance(.25f, it / 100f) }.reversed())
        (cover + left).forEach { assertEquals(1f, it.opacity, 0f) }
    }
    @Test fun invalidInputsAreFiniteAndBounded() {
        for (outer in listOf(false, true))
            for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 2f))
                for (p in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 2f, .5f)) {
                    val a = DuoReveal.appearance(x, p, outer)
                    assertTrue(a.blurUnits.isFinite() && a.blurUnits in 0f..3f)
                    assertEquals(1f, a.opacity, 0f)
                }
    }
}
