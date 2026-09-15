package dev.foldprobe.home

import org.junit.Assert.*
import org.junit.Test
import dev.foldprobe.home.TouchSequenceGuard.Action.*

class CoverTouchPolicyTest {
    @Test fun locksBeforeOverlapStartsAndStaysLockedThroughClosingOverlap() {
        val p = CoverTouchPolicy()
        assertFalse(p.update(0, false)); assertFalse(p.update(5, false))
        assertTrue(p.update(6, false)); assertTrue(p.update(12, true))
        assertTrue(p.update(180, true)); assertTrue(p.update(180, false))
        assertTrue(p.update(0, true)); assertFalse(p.update(0, false))
    }
    @Test fun lostSignalAndRecreationRetainLockUntilFreshClosedReading() {
        val p = CoverTouchPolicy(); p.update(60, true)
        assertTrue(p.update(null, false))
        val recreated = CoverTouchPolicy(p.blocked)
        assertTrue(recreated.update(null, false)); assertFalse(recreated.update(0, false))
    }
    @Test fun ordinaryColdStartWithoutASensorDoesNotDisableTheOnlyScreen() {
        assertFalse(CoverTouchPolicy().update(null, false))
    }
    @Test fun fingerAlreadyDownIsCancelledImmediatelyAndCannotClickAfterReclosing() {
        val guard = TouchSequenceGuard()
        assertTrue(guard.allows(DOWN)); assertTrue(guard.setBlocked(true))
        assertFalse(guard.setBlocked(true)); assertFalse(guard.allows(OTHER))
        guard.setBlocked(false)
        assertFalse(guard.allows(UP))
        assertTrue(guard.allows(DOWN)); assertTrue(guard.allows(UP))
    }
    @Test fun gestureStartedOnBlockedCoverCannotResumeOnInnerOrOnClosure() {
        val guard = TouchSequenceGuard(); guard.setBlocked(true)
        assertFalse(guard.allows(DOWN)); guard.setBlocked(false)
        assertFalse(guard.allows(OTHER)); assertFalse(guard.allows(UP))
        assertTrue(guard.allows(DOWN)); assertTrue(guard.allows(OTHER)); assertTrue(guard.allows(CANCEL))
        assertFalse(guard.allows(UP))
    }
}
