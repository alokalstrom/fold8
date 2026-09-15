package dev.foldprobe.fold

/** Legacy cycles expire at 45 seconds; leased cycles are bounded by their foreground owner. */
class AutomaticDisplayPolicy(private val keepOpen: Boolean = false, private var initialOpenEntry: Boolean = false,
    private val foregroundLeased: Boolean = false) {
    enum class Phase { WAIT_ENDPOINT, CLOSED, OPEN, OPENING, CLOSING }
    enum class Action { NONE, REQUEST_OUTER_FIRST, REQUEST_INNER_FIRST, RELEASE }
    var phase = Phase.WAIT_ENDPOINT; private set
    private var extreme = 0
    private var deadline = 0L
    private var openSince: Long? = null
    private var leftClosedBand = false
    private var outerPrimary = false
    val active get() = phase == Phase.OPENING || phase == Phase.CLOSING

    fun reset() { phase = Phase.WAIT_ENDPOINT; openSince = null; initialOpenEntry = false }
    fun update(angle: Int?, now: Long, baseState: Int? = null): Action {
        if (angle == null || angle !in 0..180) {
            val release = active; reset()
            return if (release) Action.RELEASE else Action.NONE
        }
        if (active && !foregroundLeased && now >= deadline) {
            reset() // A timeout halfway through a fold cannot re-arm itself there.
            return Action.RELEASE
        }
        when (phase) {
            Phase.WAIT_ENDPOINT -> when {
                angle <= 5 -> { phase = Phase.CLOSED; extreme = angle; initialOpenEntry = false }
                angle >= 178 && keepOpen && initialOpenEntry -> {
                    // Prepare once on a fresh foreground entry, e.g. Home after Calculator.
                    // A timeout/stale reset consumes this entry; it cannot refresh itself.
                    initialOpenEntry = false
                    phase = Phase.OPENING; deadline = now + 45_000
                    outerPrimary = true; leftClosedBand = true; openSince = null
                    return Action.REQUEST_OUTER_FIRST
                }
                angle >= 178 && !keepOpen -> { phase = Phase.OPEN; extreme = angle }
            }
            Phase.CLOSED -> {
                extreme = minOf(extreme, angle)
                if (angle >= 3 && angle - extreme >= 3) {
                    phase = Phase.OPENING; deadline = now + 45_000
                    outerPrimary = true
                    leftClosedBand = angle > 5; openSince = null
                    return Action.REQUEST_OUTER_FIRST
                }
            }
            Phase.OPEN -> {
                extreme = maxOf(extreme, angle)
                if (extreme - angle >= 3) {
                    phase = Phase.CLOSING; deadline = now + 45_000
                    outerPrimary = false
                    leftClosedBand = true; openSince = null
                    return Action.REQUEST_INNER_FIRST
                }
            }
            Phase.OPENING, Phase.CLOSING -> {
                if (angle > 5) leftClosedBand = true
                // Do not cancel a new 3-degree opening while the physical base still says
                // CLOSED. A real reversal to zero, or a return after leaving that band, can end it.
                val closed = (angle <= 1 || (leftClosedBand && angle <= 5)) &&
                    (baseState == 0 || (baseState == null && angle <= 1))
                if (closed) {
                    phase = Phase.CLOSED; extreme = angle; openSince = null
                    return Action.RELEASE
                }
                // Mode 4 reaches Samsung's device_folded sleep path at closure.
                // Move ownership to the cover before that physical transition;
                // retain the original deadline even if the user reverses again.
                if (!outerPrimary && angle <= 30) {
                    outerPrimary = true
                    return Action.REQUEST_OUTER_FIRST
                }
                if (angle >= 178 && !keepOpen) {
                    if (openSince == null) openSince = now
                    if (now - checkNotNull(openSince) >= 350) {
                        phase = Phase.OPEN; extreme = angle; openSince = null
                        return Action.RELEASE
                    }
                } else openSince = null
            }
        }
        return Action.NONE
    }
}
