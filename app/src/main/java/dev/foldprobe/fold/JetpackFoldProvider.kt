package dev.foldprobe.fold

import android.app.Activity
import android.os.SystemClock
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.foldprobe.debug.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class JetpackFoldProvider(private val activity: Activity, private val log: EventLog, private val folds: FoldStateRepository) {
    var summary = "Waiting for WindowManager"; private set
    private var job: Job? = null
    private var lastNs = 0L
    private var count = 0
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { layout ->
                    val now = SystemClock.elapsedRealtimeNanos()
                    val delta = if (lastNs == 0L) null else (now - lastNs) / 1e6
                    lastNs = now; count++
                    val features = layout.displayFeatures.filterIsInstance<FoldingFeature>()
                    summary = "Callbacks: $count • last gap: $delta ms\n" + if (features.isEmpty()) "No FoldingFeature (does NOT establish closed state)" else features.joinToString("\n") { f ->
                        "${f.state} / ${f.orientation}\nSeparating=${f.isSeparating} occlusion=${f.occlusionType}\nBounds=${f.bounds}"
                    }
                    log.record("window", "layout", mapOf("callbackIndex" to count, "deltaMs" to delta,
                        "layout" to layout.toString(), "features" to features.map { f -> mapOf("state" to f.state.toString(),
                            "orientation" to f.orientation.toString(), "separating" to f.isSeparating,
                            "occlusion" to f.occlusionType.toString(), "bounds" to f.bounds.toString()) }))
                    val feature = features.firstOrNull()
                    folds.onPosture(when (feature?.state) { FoldingFeature.State.FLAT -> 1f; FoldingFeature.State.HALF_OPENED -> 0.5f; else -> null }, "WindowManager posture")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { summary = e.toString(); log.failure("window", "collect", e); folds.onPosture(null, "WindowManager failed") }
        }
    }
    fun stop() { job?.cancel(); job = null; log.record("window", "stopped") }
}
