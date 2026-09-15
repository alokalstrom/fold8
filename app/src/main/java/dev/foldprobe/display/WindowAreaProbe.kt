package dev.foldprobe.display

import android.app.Activity
import android.content.Context
import android.view.View
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.window.area.*
import androidx.window.core.ExperimentalWindowApi
import dev.foldprobe.debug.EventLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Explicit Jetpack session request; never forces an OEM device-state identifier. */
@OptIn(ExperimentalWindowApi::class)
class WindowAreaProbe(private val activity: Activity, private val log: EventLog,
    private val snapshot: (String) -> Unit,
    private val panelFactory: ((Context) -> View)? = null) : WindowAreaPresentationSessionCallback {
    private var controller: WindowAreaController? = null
    private var areas = emptyList<WindowAreaInfo>()
    private var observer: Job? = null
    private var presenter: WindowAreaSessionPresenter? = null
    private var pending = false
    private var destroyed = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { close("120-second test limit") }
    private val operation = WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA
    private var availability = "Waiting for WindowArea capabilities"
    private var sessionStatus = "No session requested"
    val summary get() = "WINDOW AREA\n$availability\n$sessionStatus"

    fun start(scope: CoroutineScope) {
        if (observer?.isActive == true) return
        observer = scope.launch {
            try {
                val api = controller ?: WindowAreaController.getOrCreate().also { controller = it }
                api.windowAreaInfos.collect { infos ->
                    areas = infos
                    availability = if (infos.isEmpty()) "No areas exposed by Jetpack WindowManager 1.3.0"
                    else infos.joinToString("\n") { "${it.type}: ${it.getCapability(operation).status}; ${it.metrics.bounds}" }
                    log.record("windowArea", "capabilities", mapOf("library" to "1.3.0", "areas" to infos.map {
                        mapOf("type" to it.type.toString(), "bounds" to it.metrics.bounds.toString(),
                            "presentation" to it.getCapability(operation).status.toString(),
                            "transfer" to it.getCapability(WindowAreaCapability.Operation.OPERATION_TRANSFER_ACTIVITY_TO_AREA).status.toString())
                    }))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { availability = "Capability query failed: $e"; log.failure("windowArea", "observe", e) }
        }
    }

    fun stopObserving() { observer?.cancel(); observer = null; areas = emptyList() }

    fun requestPresentation() {
        if (pending || presenter != null) { log.record("windowArea", "alreadyRequested"); return }
        val target = areas.firstOrNull { it.getCapability(operation).status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE }
        if (target == null) {
            sessionStatus = "No AVAILABLE presentation area; open the phone and check again"
            log.record("windowArea", "noAvailablePresentation", mapOf("capabilities" to availability))
            return
        }
        try {
            pending = true
            sessionStatus = "Session requested; waiting for system callback"
            log.record("windowArea", "request", mapOf("type" to target.type.toString(), "bounds" to target.metrics.bounds.toString()))
            controller!!.presentContentOnWindowArea(target.token, activity, activity.mainExecutor, this)
        } catch (e: Exception) { pending = false; sessionStatus = "Request failed: $e"; log.failure("windowArea", "request", e) }
    }

    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
        // A callback may arrive after Close was pressed or the owner was destroyed.
        if (!pending || destroyed) { session.close(); return }
        pending = false
        presenter = session
        sessionStatus = "Session active (automatically closes after 120 seconds)"
        log.record("windowArea", "sessionStarted")
        try {
            val panel = panelFactory?.invoke(session.context) ?: TextView(session.context).apply {
                text = "FOLD PROBE — SECOND PANEL\nDual-screen sensor test in progress\nThis is app content, not the launcher."
                textSize = 26f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(0, 80, 100)); setPadding(32, 64, 32, 32)

            }
            session.setContentView(panel)
            panel.post { log.record("windowArea", "contentAttached", mapOf("displayId" to panel.display?.displayId,
                "shown" to panel.isShown, "width" to panel.width, "height" to panel.height,
                "interpretation" to "View attachment does not prove physical illumination")); snapshot("windowAreaContent") }
            handler.postDelayed(timeout, 120_000)
        } catch (e: Exception) { log.failure("windowArea", "content", e); close("content failed") }
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {
        log.record("windowArea", "containerVisibility", mapOf("visible" to isVisible))
        snapshot("windowAreaVisibility:$isVisible")
    }

    override fun onSessionEnded(t: Throwable?) {
        handler.removeCallbacks(timeout); pending = false; presenter = null
        sessionStatus = if (t == null) "Session ended" else "Session ended: $t"
        if (t == null) log.record("windowArea", "sessionEnded") else log.failure("windowArea", "sessionEnded", t)
        if (!destroyed) snapshot("windowAreaEnded")
    }

    fun close(reason: String = "button") {
        handler.removeCallbacks(timeout); pending = false
        val active = presenter; presenter = null
        sessionStatus = "Session close requested: $reason"
        log.record("windowArea", "close", mapOf("reason" to reason, "hadSession" to (active != null)))
        try { active?.close() } catch (e: Exception) { log.failure("windowArea", "close", e) }
    }

    fun destroy() { destroyed = true; stopObserving(); close("activity destroyed") }
}
