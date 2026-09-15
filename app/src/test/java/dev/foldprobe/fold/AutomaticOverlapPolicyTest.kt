package dev.foldprobe.fold

import org.junit.Assert.*
import org.junit.Test

class AutomaticOverlapPolicyTest {
    @Test fun cannotPrepareAndCancelTheRequestWhilePhysicalClosingIsStillPending() {
        val p = AutomaticOverlapPolicy(); assertTrue(p.shouldPrepare(0, 0))
        assertFalse(p.shouldPrepare(12, 1, requestActive = true))
        assertFalse(p.shouldPrepare(5, 2, requestActive = true))
        assertFalse(p.shouldPrepare(0, 3, requestActive = true))
        assertTrue(p.shouldPrepare(0, 4, requestActive = false))
        assertFalse(p.shouldPrepare(0, 5, requestActive = false))
    }
    @Test fun waitsForClosedThenPreparesOnlyOnceDuringOpeningAndHolds() {
        val p = AutomaticOverlapPolicy()
        assertFalse(p.shouldPrepare(180, 0)); assertFalse(p.shouldPrepare(30, 1))
        assertTrue(p.shouldPrepare(0, 2)); assertFalse(p.shouldPrepare(0, 3))
        for (angle in listOf(6, 11, 12, 50, 180, 90, 180)) assertFalse(p.shouldPrepare(angle, 10))
        assertFalse(p.shouldPrepare(180, 200_000))
        assertTrue(p.shouldPrepare(5, 200_001)); assertFalse(p.shouldPrepare(0, 200_002))
    }
    @Test fun closedJitterDoesNotRepeatAndArmingRefreshRequiresClosedPhone() {
        val p = AutomaticOverlapPolicy(); assertTrue(p.shouldPrepare(0, 0))
        for (angle in listOf(5, 6, 11, 4, 0)) assertFalse(p.shouldPrepare(angle, 100))
        assertFalse(p.shouldPrepare(0, 120_999)); assertTrue(p.shouldPrepare(0, 121_000))
        assertFalse(p.shouldPrepare(12, 250_000)); assertFalse(p.shouldPrepare(180, 400_000))
    }
    @Test fun signalLossOrForegroundRestartRequiresFreshClosedObservation() {
        val p = AutomaticOverlapPolicy(); assertTrue(p.shouldPrepare(0, 0))
        assertFalse(p.shouldPrepare(null, 1)); assertFalse(p.shouldPrepare(30, 2))
        assertTrue(p.shouldPrepare(0, 3)); p.reset()
        assertFalse(p.shouldPrepare(180, 4)); assertTrue(p.shouldPrepare(0, 5))
    }
}
