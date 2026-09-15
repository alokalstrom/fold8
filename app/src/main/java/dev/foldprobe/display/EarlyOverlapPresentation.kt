package dev.foldprobe.display

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import dev.foldprobe.debug.EventLog

/** Supplies the other physical panel while our shell request owns concurrent display mode. */
class EarlyOverlapPresentation(
    private val activity: Activity, private val log: EventLog,
    private val content: (Context, Boolean) -> View
) : DisplayManager.DisplayListener {
    private val manager = activity.getSystemService(DisplayManager::class.java)
    private var enabled = false
    private var navigationHandoff = false
    private var listening = false
    private var presentation: Presentation? = null
    private var failedDisplay: Int? = null
    private var lastSelection: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var releaseGrace = false
    private var panelWidth: Int? = null
    private val finishRelease = Runnable {
        releaseGrace = false
        updateListening()
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) {
            handler.removeCallbacks(finishRelease)
            releaseGrace = false
            failedDisplay = null
            lastSelection = null
        } else if (presentation?.isShowing == true) {
            // The policy releases before Samsung finishes remapping. Keep these pixels
            // until the display disappears, with a bounded fallback for a stalled transition.
            releaseGrace = true
            handler.postDelayed(finishRelease, 750)
            log.record("earlyOverlap", "releaseHold", mapOf("displayId" to presentation?.display?.displayId,
                "maxMs" to 750))
        }
        updateListening()
    }
    /** Lifecycle exit must dispose immediately, regardless of a pending remap. */
    fun stop() {
        handler.removeCallbacks(finishRelease)
        enabled = false; releaseGrace = false; navigationHandoff = false
        updateListening()
    }
    /** Keep the existing pixels until Android removes the secondary display during navigation.
     * This retains only the window, never the device-state request or its timeout. */
    fun holdForNavigation(value: Boolean) {
        if (navigationHandoff == value) return
        navigationHandoff = value
        log.record("earlyOverlap", "navigationHold", mapOf("enabled" to value,
            "showing" to (presentation?.isShowing == true)))
        updateListening()
    }
    private fun updateListening() {
        val needed = enabled || navigationHandoff || releaseGrace
        if (needed != listening) {
            listening = needed
            if (needed) manager.registerDisplayListener(this, handler)
            else manager.unregisterDisplayListener(this)
        }
        if (!needed) close() else sync()
    }
    private fun sync() {
        // Presentation dismisses itself when its display disappears. Do not replace it
        // with a new panel while logical IDs and physical panels are being remapped.
        if (navigationHandoff || releaseGrace) return
        if (!enabled) return
        // Physical modes verified on SM-F971B; logical IDs can change roles.
        val accessible = manager.displays
        val target = accessible.firstOrNull {
            it.isValid && it.displayId != activity.display?.displayId &&
                ((it.mode.physicalWidth == 1248 && it.mode.physicalHeight == 1972) ||
                 (it.mode.physicalWidth == 2448 && it.mode.physicalHeight == 1848))
        }
        val selection = "primary=${activity.display?.displayId}; candidates=" + accessible.joinToString {
            "${it.displayId}:${it.mode.physicalWidth}x${it.mode.physicalHeight}:${it.flags}"
        } + "; target=${target?.displayId}"
        if (selection != lastSelection) {
            lastSelection = selection
            log.record("earlyOverlap", "selection", mapOf("details" to selection,
                "presentationCategory" to manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }))
        }
        if (target == null) { close(); return }
        if (presentation?.display?.displayId == target.displayId && presentation?.isShowing == true &&
            panelWidth == target.mode.physicalWidth) return
        if (failedDisplay == target.displayId) return
        close()
        try {
            val dialog = Presentation(activity, target)
            presentation = dialog
            panelWidth = target.mode.physicalWidth
            // A Home presentation must respect normal inactivity timeout too.
            val keepAwake = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            dialog.window?.addFlags(keepAwake or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            val outer = target.mode.physicalWidth == 1248
            val panel = content(dialog.context, outer)
            dialog.setContentView(panel)
            dialog.setOnDismissListener {
                if (presentation === dialog) {
                    presentation = null; panelWidth = null
                    log.record("earlyOverlap", "platformDismissed", mapOf("displayId" to target.displayId))
                    if (releaseGrace) {
                        handler.removeCallbacks(finishRelease); releaseGrace = false
                        updateListening()
                    }
                }
            }
            dialog.show()
            log.record("earlyOverlap", "shown", mapOf("displayId" to target.displayId, "state" to target.state, "outer" to outer))
            panel.post { log.record("earlyOverlap", "attached", mapOf("shown" to panel.isShown,
                "width" to panel.width, "height" to panel.height,
                "meaning" to "App attachment; physical illumination requires observation")) }
        } catch (e: Exception) {
            failedDisplay = target.displayId
            log.failure("earlyOverlap", "showFailed", e)
            close()
        }
    }
    private fun close() {
        val previous = presentation; presentation = null; panelWidth = null
        if (previous != null) {
            runCatching { previous.dismiss() }.onFailure { log.failure("earlyOverlap", "closeFailed", it) }
            log.record("earlyOverlap", "closed")
        }
    }
    override fun onDisplayAdded(displayId: Int) { sync() }
    override fun onDisplayChanged(displayId: Int) { sync() }
    override fun onDisplayRemoved(displayId: Int) { failedDisplay = null; sync() }
}
