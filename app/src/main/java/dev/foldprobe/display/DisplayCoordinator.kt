package dev.foldprobe.display

import android.app.Activity
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.widget.TextView
import dev.foldprobe.ProbeApplication
import dev.foldprobe.debug.EventLog

class DisplayCoordinator(private val activity: Activity, private val log: EventLog) : DisplayManager.DisplayListener {
    private val manager = activity.getSystemService(DisplayManager::class.java)
    private val presentations = mutableListOf<Presentation>()
    var summary = "Waiting for display snapshot"; private set
    fun start() { manager.registerDisplayListener(this, Handler(Looper.getMainLooper())); snapshot("start") }
    fun stop() {
        manager.unregisterDisplayListener(this)
        presentations.forEach { it.dismiss() }; presentations.clear()
    }
    fun snapshot(reason: String) {
        val metrics = activity.resources.displayMetrics
        val config = activity.resources.configuration
        val current = activity.display
        val window = activity.windowManager.currentWindowMetrics.bounds
        val displays = manager.displays.map { d -> mapOf("id" to d.displayId, "name" to d.name, "state" to d.state,
            "rotation" to d.rotation, "flags" to d.flags, "valid" to d.isValid,
            "modeWidth" to d.mode.physicalWidth, "modeHeight" to d.mode.physicalHeight, "refreshRate" to d.refreshRate,
            "description" to d.toString()) }
        log.record("display", reason, mapOf("activityInstance" to System.identityHashCode(activity),
            "activityDisplayId" to current?.displayId, "windowBounds" to window.toString(),
            "widthPixels" to metrics.widthPixels, "heightPixels" to metrics.heightPixels,
            "density" to metrics.density, "densityDpi" to metrics.densityDpi,
            "screenWidthDp" to config.screenWidthDp, "screenHeightDp" to config.screenHeightDp,
            "smallestScreenWidthDp" to config.smallestScreenWidthDp, "orientation" to config.orientation,
            "configuration" to config.toString(), "displays" to displays))
        summary = "Activity display: ${current?.displayId} (${current?.name})\n" +
            "Window: $window\nLogical pixels: ${metrics.widthPixels} × ${metrics.heightPixels}\n" +
            "Density: ${metrics.density} (${metrics.densityDpi} dpi)\n" +
            "Config: ${config.screenWidthDp} × ${config.screenHeightDp} dp; smallest ${config.smallestScreenWidthDp}\n" +
            "Orientation: ${config.orientation}; rotation: ${current?.rotation}\n" +
            "ON display IDs: ${manager.displays.filter { it.state == Display.STATE_ON }.map { it.displayId }}\n" +
            "Logical display IDs do not necessarily identify physical cover/inner panels.\n\n" +
            displays.joinToString("\n") { "ID ${it["id"]}: ${it["name"]}, state=${it["state"]}, mode=${it["modeWidth"]}×${it["modeHeight"]}, flags=${it["flags"]}" }
    }
    override fun onDisplayAdded(displayId: Int) { snapshot("added:$displayId") }
    override fun onDisplayRemoved(displayId: Int) { snapshot("removed:$displayId") }
    override fun onDisplayChanged(displayId: Int) { snapshot("changed:$displayId") }

    fun probePresentations() {
        presentations.forEach { it.dismiss() }; presentations.clear()
        val candidates = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        log.record("presentation", "candidates", mapOf("ids" to candidates.map { it.displayId }))
        candidates.forEach { d ->
            if (d.displayId == activity.display?.displayId) return@forEach
            try {
                val p = object : Presentation(activity, d) {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(TextView(context).apply {
                            text = "Fold Probe\nPresentation on display ${d.displayId}\n${System.currentTimeMillis()}"
                            textSize = 28f; setPadding(32, 64, 32, 32)
                        })
                    }
                }
                p.setOnDismissListener { log.record("presentation", "dismissed", mapOf("id" to d.displayId)) }
                p.show(); presentations.add(p)
                p.window?.decorView?.post { log.record("presentation", "decorPosted", mapOf("id" to d.displayId, "shown" to p.window?.decorView?.isShown,
                    "interpretation" to "Does not prove physical panel illumination")) }
                log.record("presentation", "showReturned", mapOf("id" to d.displayId))
            } catch (e: Exception) { log.failure("presentation", "show:${d.displayId}", e) }
        }
    }

    fun probeSecondaryActivity() {
        val target = manager.displays.firstOrNull { it.displayId != activity.display?.displayId && it.isValid }
        if (target == null) { log.record("secondaryActivity", "noCandidate"); return }
        try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(target.displayId)
            val intent = Intent(activity, DisplayProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val allowed = activity.getSystemService(android.app.ActivityManager::class.java).isActivityStartAllowedOnDisplay(activity, target.displayId, intent)
            log.record("secondaryActivity", "allowedCheck", mapOf("targetId" to target.displayId, "allowed" to allowed))
            if (allowed) { activity.startActivity(intent, options.toBundle()); log.record("secondaryActivity", "launchReturned", mapOf("requestedId" to target.displayId)) }
        } catch (e: Exception) { log.failure("secondaryActivity", "launch", e) }
    }
}

class DisplayProbeActivity : Activity() {
    private val log get() = (application as ProbeApplication).log
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Fold Probe: secondary activity\nActual display: ${display?.displayId}\nTap to close"
            textSize = 24f; setPadding(32, 64, 32, 32); setOnClickListener { finish() }
        })
        log.record("secondaryActivity", "created", mapOf("actualDisplayId" to display?.displayId))
    }
    override fun onResume() { super.onResume(); log.record("secondaryActivity", "resumed", mapOf("actualDisplayId" to display?.displayId)) }
    override fun onDestroy() { log.record("secondaryActivity", "destroyed"); super.onDestroy() }
}
