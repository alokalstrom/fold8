package dev.foldprobe.fold

import android.os.SystemClock
import dev.foldprobe.debug.EventLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FoldStateRepository(private val log: EventLog) : FoldProgressProvider {
    private val _state = MutableStateFlow(FoldReading())
    override val state = _state.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    override val progress = _progress.asStateFlow()
    override val quality get() = state.value.quality
    private var angle: FoldReading? = null
    private var posture: Float? = null
    private var foreground = false
    private var awaitingFreshSample = false
    private var estimateJob: Job? = null
    var estimatedMode = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun publish(value: FoldReading) {
        _state.value = value; _progress.value = value.progress
        log.record("progress", "reading", mapOf("progress" to value.progress, "quality" to value.quality.name,
            "source" to value.source, "rawAngle" to value.rawAngle, "note" to value.note), sample = true)
    }

    fun onAngle(raw: Float, normalized: Float, source: String, calibratedVendor: Boolean, distinct: Int) {
        estimateJob?.cancel()
        awaitingFreshSample = false
        val reading = FoldReading(normalized,
            if (calibratedVendor) FoldProgressQuality.ESTIMATED else if (distinct >= 12) FoldProgressQuality.CONTINUOUS_HARDWARE else FoldProgressQuality.DISCRETE_HARDWARE,
            if (calibratedVendor) null else raw, source,
            if (calibratedVendor) "Manual two-point mapping; vendor units/linearity unverified"
            else if (distinct >= 12) "Angle API; at least 12 distinct samples observed. Timing and reversibility still require physical validation."
            else "Angle API; collecting granularity evidence (12 distinct values required)", SystemClock.elapsedRealtimeNanos())
        angle = reading
        if (foreground) publish(reading)
    }

    fun onPosture(target: Float?, source: String) {
        posture = target
        if (angle != null && !awaitingFreshSample && foreground) return
        fallback(source)
    }

    fun setForeground(active: Boolean) {
        foreground = active
        estimateJob?.cancel()
        // On-change sensors may be quiet while still. Resume requires a fresh registration sample.
        awaitingFreshSample = true
        angle = null
        if (!active) {
            posture = null
            publish(FoldReading(source = "Activity stopped", note = "Monitoring suspended; previous angle is invalid"))
        } else fallback("Awaiting sensor / WindowManager callback")
    }

    fun invalidateAngle() { angle = null; awaitingFreshSample = true; fallback("Angle source changed") }

    fun setEstimator(enabled: Boolean) { estimatedMode = enabled; fallback("Estimator setting changed") }

    private fun fallback(source: String) {
        if (angle != null && !awaitingFreshSample && foreground) return
        estimateJob?.cancel()
        val target = posture
        if (target == null) { publish(FoldReading(source = source)); return }
        if (!estimatedMode) {
            publish(FoldReading(target, FoldProgressQuality.DISCRETE_HARDWARE, source = source,
                note = "Nominal posture marker, NOT measured angle", receivedNs = SystemClock.elapsedRealtimeNanos()))
        } else {
            val start = progress.value
            estimateJob = scope.launch {
                val startNs = SystemClock.elapsedRealtimeNanos()
                do {
                    val t = ((SystemClock.elapsedRealtimeNanos() - startNs) / 450_000_000f).coerceIn(0f, 1f)
                    publish(FoldReading(start + (target - start) * t, FoldProgressQuality.ESTIMATED,
                        source = source, note = "450 ms timed interpolation; does not track physical angle", receivedNs = SystemClock.elapsedRealtimeNanos()))
                    if (t >= 1f) break
                    delay(16)
                } while (isActive)
            }
        }
    }
}
