package dev.foldprobe.fold

/** One explicit trial; no re-arming during a reversal or after a timeout. */
class EarlyDisplayPolicy {
    enum class Phase { OFF, ARMED, REQUESTED, FINISHED }
    enum class Action { NONE, REQUEST, RELEASE }
    var phase = Phase.OFF; private set
    private var deadline = 0L
    private var keepOpen = false
    fun arm(now: Long, keepOpen: Boolean = false) {
        phase = Phase.ARMED; deadline = now + 120_000; this.keepOpen = keepOpen
    }
    fun cancel(): Action {
        val action = if (phase == Phase.REQUESTED) Action.RELEASE else Action.NONE
        phase = Phase.OFF
        return action
    }
    fun update(angle: Int?, now: Long, baseState: Int? = null): Action {
        if (phase != Phase.ARMED && phase != Phase.REQUESTED) return Action.NONE
        // Outer-first keeps the primary panel unchanged throughout the animation.
        // An already-open phone must still never trigger a late request.
        val openingEndsTrial = angle != null && angle >= 100 && (!keepOpen || phase == Phase.ARMED)
        // Cancelling state 5 before physical CLOSED briefly makes the inner panel primary.
        // Unknown state falls back to the original angle guard instead of retaining a request.
        val closingEndsTrial = phase == Phase.REQUESTED && angle != null && angle <= 5 &&
            (!keepOpen || baseState == null || baseState == 0)
        if (angle == null || now >= deadline || openingEndsTrial || closingEndsTrial) {
            val action = if (phase == Phase.REQUESTED) Action.RELEASE else Action.NONE
            phase = Phase.FINISHED
            return action
        }
        if (phase == Phase.ARMED && angle in 12..75) {
            phase = Phase.REQUESTED
            deadline = minOf(deadline, now + 45_000)
            return Action.REQUEST
        }
        return Action.NONE
    }
}
