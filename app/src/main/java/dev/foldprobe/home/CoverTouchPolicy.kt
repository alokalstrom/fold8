package dev.foldprobe.home

/** The cover stays read-only until a fresh closed reading and overlap release agree. */
class CoverTouchPolicy(initiallyBlocked: Boolean = false) {
    var blocked = initiallyBlocked; private set
    fun update(angle: Int?, overlapActive: Boolean): Boolean {
        if (overlapActive || (angle != null && angle > 5)) blocked = true
        else if (angle != null && angle in 0..5) blocked = false
        // Losing the signal must not re-enable the back panel while the phone is open.
        return blocked
    }
}

/** A gesture crossing the read-only boundary is cancelled, never resumed on finger-up. */
class TouchSequenceGuard {
    enum class Action { DOWN, OTHER, UP, CANCEL }
    private var blocked = false
    private var forwarding = false
    private var suppressed = false

    /** True means the receiver needs an immediate CANCEL, even if the finger is still. */
    fun setBlocked(value: Boolean): Boolean {
        val cancel = value && forwarding
        blocked = value
        if (value) { forwarding = false; suppressed = true }
        return cancel
    }

    fun allows(action: Action): Boolean {
        if (action == Action.DOWN) {
            suppressed = blocked
            forwarding = !blocked
            return forwarding
        }
        val allowed = !blocked && !suppressed && forwarding
        if (action == Action.UP || action == Action.CANCEL) { forwarding = false; suppressed = false }
        return allowed
    }
}
