package dev.foldprobe.fold

import org.junit.Assert.*
import org.junit.Test
import dev.foldprobe.fold.AutomaticDisplayPolicy.Action.*

class AutomaticDisplayPolicyTest {
    @Test fun startsOnSmallMotionAndDoesNotCancelAgainstStaleClosedBase() {
        val p = AutomaticDisplayPolicy()
        assertEquals(NONE, p.update(0, 0, 0))
        assertEquals(NONE, p.update(1, 100, 0))
        assertEquals(NONE, p.update(2, 200, 0))
        assertEquals(REQUEST_OUTER_FIRST, p.update(3, 300, 0))
        assertEquals(NONE, p.update(4, 400, 0))
        assertEquals(NONE, p.update(6, 500, 1))
        assertEquals(NONE, p.update(90, 1000, 2))
    }
    @Test fun fullOpenReleasesAfterSettlingThenClosingKeepsInnerPrimary() {
        val p = AutomaticDisplayPolicy(); p.update(0, 0); p.update(3, 100)
        assertEquals(NONE, p.update(180, 1000))
        assertEquals(NONE, p.update(179, 1349))
        assertEquals(RELEASE, p.update(180, 1350))
        assertEquals(NONE, p.update(178, 1400))
        assertEquals(REQUEST_INNER_FIRST, p.update(177, 1500))
        assertEquals(REQUEST_OUTER_FIRST, p.update(30, 1900, 1))
        assertEquals(NONE, p.update(5, 2000, 1))
        assertEquals(RELEASE, p.update(3, 2100, 0))
    }
    @Test fun reversalsKeepRequestAndResetFullOpenDwell() {
        val p = AutomaticDisplayPolicy(); p.update(180, 0); p.update(177, 100)
        for (a in listOf(90, 110, 75, 170)) assertEquals(NONE, p.update(a, 200))
        p.update(180, 1000); p.update(175, 1200); p.update(180, 1300)
        assertEquals(NONE, p.update(180, 1600)); assertEquals(RELEASE, p.update(180, 1650))
    }
    @Test fun lostSignalAndDeadlineReleaseAndCannotRearmHalfOpen() {
        val p = AutomaticDisplayPolicy(); p.update(180, 0); p.update(177, 10)
        assertEquals(NONE, p.update(90, 45009)); assertEquals(RELEASE, p.update(90, 45010))
        assertEquals(NONE, p.update(80, 45011)); assertFalse(p.active)
        p.update(0, 45020); assertEquals(REQUEST_OUTER_FIRST, p.update(3, 45030))
        assertEquals(RELEASE, p.update(null, 45040)); assertEquals(NONE, p.update(40, 45050))
    }
    @Test fun tinyReversalEndsAndUnknownBaseOnlyFallsBackAtZero() {
        val p = AutomaticDisplayPolicy(); p.update(0, 0); p.update(3, 10)
        assertEquals(NONE, p.update(2, 20, 0)); assertEquals(RELEASE, p.update(0, 30, 0))
        p.update(3, 40); p.update(30, 50)
        assertEquals(NONE, p.update(4, 60)); assertEquals(RELEASE, p.update(1, 70))
    }
    @Test fun attachingHalfwayWaitsForAnEndpointAndOpenNoiseIsIgnored() {
        val p = AutomaticDisplayPolicy()
        for (a in listOf(90, 60, 120, 177)) assertEquals(NONE, p.update(a, 0))
        assertFalse(p.active)
        p.update(178, 10); p.update(180, 20)
        assertEquals(NONE, p.update(179, 30)); assertEquals(NONE, p.update(178, 40))
        assertEquals(REQUEST_INNER_FIRST, p.update(177, 50))
    }
    @Test fun closingTransfersToCoverOnceAndRetainsDeadlineThroughReversal() {
        val p = AutomaticDisplayPolicy(); p.update(180, 0); p.update(177, 100)
        assertEquals(NONE, p.update(31, 40000, 1))
        assertEquals(REQUEST_OUTER_FIRST, p.update(30, 40100, 1))
        for (a in listOf(15, 50, 25, 100)) assertEquals(NONE, p.update(a, 41000, 1))
        assertEquals(RELEASE, p.update(90, 45100, 2))
        assertFalse(p.active)
    }
    @Test fun alreadyClosedReleasesInsteadOfStartingAReplacementRequest() {
        val p = AutomaticDisplayPolicy(); p.update(180, 0); p.update(177, 100)
        assertEquals(RELEASE, p.update(0, 200, 0))
        assertFalse(p.active)
    }
    @Test fun steadyTrialKeepsOneModeThroughOpenHoldAndClosing() {
        val p = AutomaticDisplayPolicy(keepOpen = true)
        p.update(0, 0); assertEquals(REQUEST_OUTER_FIRST, p.update(3, 100, 0))
        for (a in listOf(90, 180, 179, 180, 150, 30, 5)) assertEquals(NONE, p.update(a, 2000, 1))
        assertEquals(RELEASE, p.update(1, 3000, 0))
        assertEquals(REQUEST_OUTER_FIRST, p.update(4, 4000, 0))
    }
    @Test fun steadyTrialRequiresClosedEntryAndStillExpiresWhileFullyOpen() {
        val p = AutomaticDisplayPolicy(keepOpen = true)
        for (a in listOf(180, 177, 90)) assertEquals(NONE, p.update(a, 0))
        assertFalse(p.active)
        p.update(0, 100); p.update(3, 200)
        assertEquals(NONE, p.update(180, 45199))
        assertEquals(RELEASE, p.update(180, 45200))
        for (a in listOf(180, 177, 90)) assertEquals(NONE, p.update(a, 45300))
        assertFalse(p.active)
    }
    @Test fun returningHomeOpenPreparesOnceAndContinuesUntilPhysicalClosure() {
        val p = AutomaticDisplayPolicy(keepOpen = true, initialOpenEntry = true)
        assertEquals(REQUEST_OUTER_FIRST, p.update(180, 100))
        for (a in listOf(180, 177, 90, 30, 5)) assertEquals(NONE, p.update(a, 2000, 1))
        assertEquals(RELEASE, p.update(2, 3000, 0))
        assertEquals(REQUEST_OUTER_FIRST, p.update(5, 4000, 0))
    }
    @Test fun foregroundEntryCannotRenewItselfAfterTimeoutOrLostSignal() {
        val p = AutomaticDisplayPolicy(keepOpen = true, initialOpenEntry = true)
        p.update(179, 100)
        assertEquals(RELEASE, p.update(180, 45100))
        for (a in listOf(180, 177, 90, 180)) assertEquals(NONE, p.update(a, 45200))
        assertFalse(p.active)
        val stale = AutomaticDisplayPolicy(keepOpen = true, initialOpenEntry = true)
        stale.update(180, 0)
        assertEquals(RELEASE, stale.update(null, 100))
        assertEquals(NONE, stale.update(180, 200))
        assertFalse(stale.active)
    }
}
