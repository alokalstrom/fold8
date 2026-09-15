package dev.foldprobe.fold

/** Foreground-only preparation, once per closed/open cycle. Never arms halfway open. */
class AutomaticOverlapPolicy {
    private enum class Phase { WAIT_CLOSED, PREPARED, OPENED }
    private var phase = Phase.WAIT_CLOSED
    private var refreshAt = 0L

    fun reset() { phase = Phase.WAIT_CLOSED }

    fun shouldPrepare(angle: Int?, now: Long, requestActive: Boolean = false): Boolean {
        if (angle == null) { reset(); return false }
        // Preparation itself cancels the previous request. Wait for its physical-close release.
        if (requestActive) { phase = Phase.OPENED; return false }
        if (phase == Phase.PREPARED && angle >= 12) phase = Phase.OPENED
        if (angle !in 0..5) return false
        if (phase == Phase.PREPARED && now < refreshAt) return false
        phase = Phase.PREPARED
        // Refresh only while closed, after the shell policy's two-minute arming expiry.
        // The active request's 45-second deadline is never extended while open.
        refreshAt = now + 121_000
        return true
    }
}
