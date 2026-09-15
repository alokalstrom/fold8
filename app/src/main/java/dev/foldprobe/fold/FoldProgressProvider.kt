package dev.foldprobe.fold

import kotlinx.coroutines.flow.StateFlow

enum class FoldProgressQuality { CONTINUOUS_HARDWARE, DISCRETE_HARDWARE, ESTIMATED, UNAVAILABLE }

interface FoldProgressProvider {
    val progress: StateFlow<Float>
    val quality: FoldProgressQuality
    val state: StateFlow<FoldReading>
}

data class FoldReading(
    val progress: Float = 0f,
    val quality: FoldProgressQuality = FoldProgressQuality.UNAVAILABLE,
    val rawAngle: Float? = null,
    val source: String = "No measured angle or posture",
    val note: String = "0 is a placeholder; it does not mean closed",
    val receivedNs: Long = 0
)

object AngleMath {
    fun normalize(value: Float, closed: Float = 0f, opened: Float = 180f): Float? {
        if (!value.isFinite() || !closed.isFinite() || !opened.isFinite() || kotlin.math.abs(opened - closed) < 1f) return null
        return ((value - closed) / (opened - closed)).coerceIn(0f, 1f)
    }
}

/** Rolling sample timing, measured from the sensor clock rather than requested rate. */
class SampleStats {
    var count = 0L; private set
    var firstNs = 0L; private set
    var lastNs = 0L; private set
    var lastDeltaNs = 0L; private set
    var reversals = 0; private set
    private var lastValue: Float? = null
    private var direction = 0
    private val distinct = mutableSetOf<Int>()
    val distinctCount get() = distinct.size
    val hz get() = if (lastNs > firstNs) (count - 1) * 1e9 / (lastNs - firstNs) else 0.0
    fun add(value: Float, ns: Long) {
        if (count == 0L) firstNs = ns else lastDeltaNs = ns - lastNs
        lastNs = ns; count++
        if (value.isFinite()) {
            if (distinct.size < 10000) distinct.add((value * 10).toInt())
            lastValue?.let { old ->
                val delta = value - old
                val next = if (delta > 0.5f) 1 else if (delta < -0.5f) -1 else 0
                if (next != 0) { if (direction != 0 && next != direction) reversals++; direction = next }
            }
            lastValue = value
        }
    }
}
