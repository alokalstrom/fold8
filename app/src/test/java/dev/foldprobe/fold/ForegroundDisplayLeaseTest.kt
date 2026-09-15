package dev.foldprobe.fold

import org.junit.Assert.*
import org.junit.Test
import dev.foldprobe.fold.AutomaticDisplayPolicy.Action.*

class ForegroundDisplayLeaseTest {
    @Test fun longStationaryHoldAndReversalKeepTheSameRequest() {
        val lease = ForegroundDisplayLease()
        val policy = AutomaticDisplayPolicy(keepOpen = true, initialOpenEntry = true, foregroundLeased = true)
        assertEquals(REQUEST_OUTER_FIRST, policy.update(180, 0))
        lease.begin(0)
        for (now in 250L..180_000L step 250) {
            assertTrue(lease.valid(now))
            lease.renew(now)
            assertEquals(NONE, policy.update(180, now))
        }
        for (angle in listOf(150, 90, 100, 30, 5)) assertEquals(NONE, policy.update(angle, 180_100, 1))
        assertEquals(RELEASE, policy.update(1, 180_200, 0))
        assertEquals(REQUEST_OUTER_FIRST, policy.update(4, 180_300, 0))
    }
    @Test fun expiredHeartbeatCannotBeRevivedByALateCall() {
        val lease = ForegroundDisplayLease()
        assertFalse(lease.valid(0))
        lease.renew(10)
        assertFalse(lease.valid(10))
        lease.begin(100)
        assertTrue(lease.valid(3099))
        assertFalse(lease.valid(3100))
        lease.renew(3100)
        assertFalse(lease.valid(3100))
        lease.begin(3200)
        assertTrue(lease.valid(3200))
        lease.clear()
        lease.renew(3300)
        assertFalse(lease.valid(3300))
    }
    @Test fun freshForegroundCannotKeepLeasedPolicyAliveWithoutAngle() {
        val policy = AutomaticDisplayPolicy(keepOpen = true, initialOpenEntry = true, foregroundLeased = true)
        policy.update(180, 0)
        assertEquals(RELEASE, policy.update(null, 200))
        assertFalse(policy.active)
        assertEquals(NONE, policy.update(180, 300))
    }
    @Test fun halfOpenHoldAlsoSurvivesOldDeadline() {
        val policy = AutomaticDisplayPolicy(keepOpen = true, foregroundLeased = true)
        policy.update(0, 0)
        assertEquals(REQUEST_OUTER_FIRST, policy.update(3, 100, 0))
        assertEquals(NONE, policy.update(90, 90_000, 2))
        assertEquals(NONE, policy.update(180, 100_000, 3))
        assertTrue(policy.active)
    }
}
