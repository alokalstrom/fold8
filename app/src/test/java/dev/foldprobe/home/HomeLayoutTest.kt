package dev.foldprobe.home

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
    @Test fun normalizationRetainsMissingAppsAndSlotIndicesWithoutFillingGaps() {
        val normalized = HomeLayout.normalize(listOf("app/a", null, "removed/b", "app/a"))
        assertEquals(20, normalized.size)
        assertEquals("app/a", normalized[0]); assertNull(normalized[1])
        assertEquals("removed/b", normalized[2]); assertNull(normalized[3])
        assertEquals(normalized, HomeLayout.normalize(normalized))
    }
    @Test fun movingAnExistingAppSwapsAcrossGridAndDock() {
        val start = List(20) { "app/$it" }
        val moved = HomeLayout.assign(start, 17, "app/2")
        assertEquals("app/2", moved[17]); assertEquals("app/17", moved[2])
        assertEquals(20, moved.toSet().size)
        assertEquals(start, HomeLayout.assign(moved, 2, "app/2"))
    }
    @Test fun replacingOrClearingDoesNotReorderUnrelatedSlots() {
        val start = List(20) { "app/$it" }
        val replacement = HomeLayout.assign(start, 4, "new/app")
        assertEquals("new/app", replacement[4]); assertEquals(start[5], replacement[5])
        val cleared = HomeLayout.assign(replacement, 4, null)
        assertNull(cleared[4]); assertEquals(start[19], cleared[19])
    }
}
