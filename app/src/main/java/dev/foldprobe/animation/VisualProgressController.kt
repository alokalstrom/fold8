package dev.foldprobe.animation

import android.os.SystemClock
import dev.foldprobe.debug.EventLog
import dev.foldprobe.fold.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class PreviewMode { SENSOR, DIAGNOSTIC, ESTIMATED, MANUAL }
data class VisualReading(val progress: Float = 0f, val mode: PreviewMode = PreviewMode.SENSOR,
    val raw: FoldReading = FoldReading(), val diagnostic: DiagnosticReading = DiagnosticReading()) {
    val displayedAngle get() = if (mode == PreviewMode.DIAGNOSTIC) diagnostic.angle else raw.rawAngle
    val label get() = when (mode) {
        PreviewMode.DIAGNOSTIC -> "VIKVINKEL · ${diagnostic.source}"
        PreviewMode.MANUAL -> "MANUELL · ingen vinkelmätning"
        PreviewMode.ESTIMATED -> if (raw.quality == FoldProgressQuality.UNAVAILABLE)
            "UPPSKATTAD · väntar på mätning" else "UPPSKATTAD · 450 ms mellan mätpunkter"
        PreviewMode.SENSOR -> "SENSOR · ${raw.quality}"
    }
}

/** Rendering adapter; never overwrites the raw diagnostic provider. */
class VisualProgressController(private val scope: CoroutineScope, private val log: EventLog, initialProgress: Float = 0f) {
    private val mutable = MutableStateFlow(VisualReading(progress = DuoLayout.bounded(initialProgress)))
    val state = mutable.asStateFlow()
    private var transition: Job? = null
    private var observer: Job? = null
    private var diagnosticObserver: Job? = null
    private var snapNextSample = true
    fun observeDiagnostic(source: DiagnosticAngleSource) {
        diagnosticObserver?.cancel()
        diagnosticObserver = scope.launch { source.state.collect { reading ->
            mutable.value = mutable.value.copy(diagnostic = reading)
            if (mutable.value.mode == PreviewMode.DIAGNOSTIC) diagnosticTarget(reading)
        } }
    }
    fun observe(provider: FoldProgressProvider) {
        observer?.cancel()
        observer = scope.launch { provider.state.collect { raw ->
            mutable.value = mutable.value.copy(raw = raw)
            if (mutable.value.mode == PreviewMode.SENSOR || mutable.value.mode == PreviewMode.ESTIMATED) target(raw)
        } }
    }
    fun mode(mode: PreviewMode) {
        transition?.cancel(); mutable.value = mutable.value.copy(mode = mode)
        log.record("preview", "mode", mapOf("mode" to mode.name))
        if (mode == PreviewMode.DIAGNOSTIC) diagnosticTarget(mutable.value.diagnostic)
        else if (mode != PreviewMode.MANUAL) target(mutable.value.raw)
    }
    fun manual(p: Float) {
        transition?.cancel(); mutable.value = mutable.value.copy(progress = DuoLayout.bounded(p), mode = PreviewMode.MANUAL)
    }
    fun markManual() { log.record("preview", "manualPosition", mapOf("progress" to mutable.value.progress)) }
    private fun diagnosticTarget(reading: DiagnosticReading) {
        transition?.cancel()
        if (!reading.live || reading.angle == null) { snapNextSample = true; return }
        val end = reading.angle / 180f
        // A fresh foreground session has no measured path from the old position.
        if (snapNextSample) {
            snapNextSample = false
            mutable.value = mutable.value.copy(progress = end)
            log.record("preview", "alignedToFreshAngle", mapOf("angle" to reading.angle, "progress" to end))
            return
        }
        val start = mutable.value.progress
        // Short interpolation only toward the last measured point; no extrapolation.
        transition = scope.launch {
            val began = SystemClock.elapsedRealtimeNanos()
            do {
                val t = ((SystemClock.elapsedRealtimeNanos() - began) / 80_000_000f).coerceIn(0f, 1f)
                mutable.value = mutable.value.copy(progress = DuoLayout.blend(start, end, t))
                if (t == 1f) break
                delay(16)
            } while (isActive)
        }
    }
    private fun target(raw: FoldReading) {
        transition?.cancel()
        if (raw.quality == FoldProgressQuality.UNAVAILABLE) return
        val end = DuoLayout.bounded(raw.progress)
        if (mutable.value.mode == PreviewMode.SENSOR) { mutable.value = mutable.value.copy(progress = end); return }
        val start = mutable.value.progress
        log.record("preview", "estimatedTarget", mapOf("start" to start, "target" to end, "durationMs" to 450,
            "limitation" to "Cannot observe hidden holds or reversals"))
        transition = scope.launch {
            val began = SystemClock.elapsedRealtimeNanos()
            do {
                val t = ((SystemClock.elapsedRealtimeNanos() - began) / 450_000_000f).coerceIn(0f, 1f)
                mutable.value = mutable.value.copy(progress = DuoLayout.blend(start, end, t))
                if (t == 1f) break
                delay(16)
            } while (isActive)
        }
    }
    fun stop() { snapNextSample = true; observer?.cancel(); diagnosticObserver?.cancel(); transition?.cancel() }
}
