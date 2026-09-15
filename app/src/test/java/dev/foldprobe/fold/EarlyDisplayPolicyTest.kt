package dev.foldprobe.fold

import org.junit.Assert.*
import org.junit.Test
import dev.foldprobe.fold.EarlyDisplayPolicy.Action.*

class EarlyDisplayPolicyTest {
    @Test fun outerFirstWaitsForPhysicalClosedInsteadOfSwitchingPrimaryAtFiveDegrees() {
        val p = EarlyDisplayPolicy(); p.arm(0, keepOpen = true); p.update(12, 1)
        assertEquals(NONE, p.update(5, 2, baseState = 3))
        assertEquals(NONE, p.update(3, 3, baseState = 1))
        assertEquals(NONE, p.update(0, 4, baseState = 1))
        assertEquals(RELEASE, p.update(0, 5, baseState = 0))
    }
    @Test fun physicalCloseWaitAllowsReversalAndPreservesDeadlineAndFallback() {
        val p = EarlyDisplayPolicy(); p.arm(0, keepOpen = true); p.update(12, 1)
        assertEquals(NONE, p.update(4, 2, baseState = 1))
        assertEquals(NONE, p.update(30, 3, baseState = 1))
        assertEquals(NONE, p.update(180, 4, baseState = 3))
        assertEquals(RELEASE, p.update(0, 45_001, baseState = 1))
        val fallback = EarlyDisplayPolicy(); fallback.arm(0, keepOpen = true); fallback.update(12, 1)
        assertEquals(RELEASE, fallback.update(4, 2, baseState = null))
        val ordinary = EarlyDisplayPolicy(); ordinary.arm(0); ordinary.update(12, 1)
        assertEquals(RELEASE, ordinary.update(5, 2, baseState = 3))
    }
    @Test fun outerFirstStaysActiveThroughFullOpeningAndReversalUntilExplicitExit() {
        val p = EarlyDisplayPolicy(); p.arm(0, keepOpen = true)
        assertEquals(NONE, p.update(0, 1)); assertEquals(REQUEST, p.update(12, 2))
        for (angle in listOf(99, 100, 150, 180, 170, 90, 180)) {
            assertEquals(NONE, p.update(angle, 1000))
            assertEquals(EarlyDisplayPolicy.Phase.REQUESTED, p.phase)
        }
        assertEquals(RELEASE, p.cancel()); assertEquals(NONE, p.update(180, 1001))
    }
    @Test fun outerFirstStillReleasesOnDeadlineClosureAndLostSignal() {
        val timed = EarlyDisplayPolicy(); timed.arm(0, keepOpen = true); timed.update(12, 10)
        assertEquals(NONE, timed.update(180, 45_009)); assertEquals(RELEASE, timed.update(180, 45_010))
        val closed = EarlyDisplayPolicy(); closed.arm(0, keepOpen = true); closed.update(12, 1)
        assertEquals(RELEASE, closed.update(5, 2)); assertEquals(NONE, closed.update(12, 3))
        val stale = EarlyDisplayPolicy(); stale.arm(0, keepOpen = true); stale.update(12, 1)
        assertEquals(RELEASE, stale.update(null, 2))
    }
    @Test fun outerFirstDoesNotArmLateAndItsOptionDoesNotLeakIntoNextTrial() {
        val p = EarlyDisplayPolicy(); p.arm(0, keepOpen = true)
        assertEquals(NONE, p.update(180, 1)); assertEquals(NONE, p.update(30, 2))
        p.arm(10); assertEquals(REQUEST, p.update(30, 11)); assertEquals(RELEASE, p.update(100, 12))
    }
    @Test fun closedThenSmallOpeningRequestsOnceAndReversalReleases() {
        val p = EarlyDisplayPolicy(); p.arm(0)
        assertEquals(NONE, p.update(0, 1)); assertEquals(NONE, p.update(11, 2))
        assertEquals(REQUEST, p.update(12, 3)); assertEquals(NONE, p.update(60, 4))
        assertEquals(RELEASE, p.update(5, 5)); assertEquals(NONE, p.update(30, 6))
    }
    @Test fun ordinaryHandoffDoesNotReleaseBeforeNinetyDegrees() {
        val p = EarlyDisplayPolicy(); p.arm(0); p.update(20, 1)
        assertEquals(NONE, p.update(85, 2)); assertEquals(NONE, p.update(95, 3))
        assertEquals(RELEASE, p.update(100, 4))
    }
    @Test fun activeDeadlineAndLostSignalReleaseWithoutFurtherMotion() {
        val timed = EarlyDisplayPolicy(); timed.arm(0); timed.update(30, 10)
        assertEquals(NONE, timed.update(30, 45_009)); assertEquals(RELEASE, timed.update(30, 45_010))
        val stale = EarlyDisplayPolicy(); stale.arm(0); stale.update(30, 1)
        assertEquals(RELEASE, stale.update(null, 2))
    }
    @Test fun expiredArmingCannotTriggerLateAndCancelIsIdempotent() {
        val p = EarlyDisplayPolicy(); p.arm(0)
        assertEquals(NONE, p.update(30, 120_000)); assertEquals(NONE, p.update(30, 120_001))
        p.arm(130_000); assertEquals(REQUEST, p.update(30, 130_001))
        assertEquals(RELEASE, p.cancel()); assertEquals(NONE, p.cancel())
    }
}
