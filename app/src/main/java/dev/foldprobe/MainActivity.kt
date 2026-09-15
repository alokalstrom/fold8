package dev.foldprobe

import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.os.*
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.foldprobe.debug.PackageInspector
import dev.foldprobe.display.DisplayCoordinator
import dev.foldprobe.display.WindowAreaProbe
import dev.foldprobe.fold.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val app get() = application as ProbeApplication
    private val log get() = app.log
    private val sensors get() = app.sensors
    private lateinit var jetpack: JetpackFoldProvider
    private lateinit var displays: DisplayCoordinator
    private lateinit var windowArea: WindowAreaProbe
    private lateinit var deviceState: DeviceStateFoldProvider
    private lateinit var content: LinearLayout
    private lateinit var header: TextView
    private lateinit var live: TextView
    private lateinit var scroll: ScrollView
    private var tab = "Live"
    private var packages = "Reading installed package metadata…"
    private var nonSdk = false
    private var running = false
    private var lastDraw = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() { if (running) { refresh(); handler.postDelayed(this, 100) } }
    }
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            log.record("screen", intent?.action ?: "unknown")
            displays.snapshot("screenBroadcast")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        nonSdk = savedInstanceState?.getBoolean("nonSdk") ?: false
        tab = savedInstanceState?.getString("tab") ?: "Live"
        jetpack = JetpackFoldProvider(this, log, app.folds)
        displays = DisplayCoordinator(this, log)
        windowArea = WindowAreaProbe(this, log, { reason -> displays.snapshot(reason) })
        deviceState = DeviceStateFoldProvider(this, log)
        log.record("lifecycle", "onCreate", mapOf("instance" to System.identityHashCode(this), "restored" to (savedInstanceState != null)))
        makeUi()
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(screenEvents, filter)
        lifecycleScope.launch {
            packages = withContext(Dispatchers.IO) { PackageInspector(this@MainActivity, log).inspect() }
            if (tab == "Packages") refresh()
        }
        window.decorView.viewTreeObserver.addOnDrawListener {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastDraw > 500_000_000) {
                lastDraw = now
                log.record("surface", "draw", mapOf("activityInstance" to System.identityHashCode(this),
                    "displayId" to display?.displayId, "visible" to window.decorView.isShown,
                    "meaning" to "App draw callback; not physical panel scanout"), sample = true)
            }
        }
    }

    private fun label(value: String, size: Float = 14f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(222, 231, 239)); setPadding(12, 10, 12, 10)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; setOnClickListener { action() } }
    private fun makeUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(14, 22, 31)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(bars.left + 8, bars.top, bars.right + 8, bars.bottom); insets
        }
        header = label("Fold Probe ${BuildConfig.VERSION_NAME}", 21f)
        root.addView(header)
        val actions = LinearLayout(this)
        actions.addView(button("Mark event") { log.record("user", "marker", mapOf("label" to "Physical test marker")); toast("Event marked") }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Export log") { export() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        val navigation = HorizontalScrollView(this)
        val tabs = LinearLayout(this)
        listOf("Live", "Sensors", "Displays", "Packages", "Events", "Options").forEach { name ->
            tabs.addView(button(name) { tab = name; showTab() })
        }
        navigation.addView(tabs); root.addView(navigation)
        scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); root.requestApplyInsets(); showTab()
    }

    private fun showTab() {
        content.removeAllViews(); scroll.scrollTo(0, 0)
        live = label("")
        if (tab == "Live") content.addView(button("Open visual prototype") {
            startActivity(Intent(this, dev.foldprobe.animation.PreviewActivity::class.java))
        })
        if (tab == "Sensors") {
            content.addView(label("All exposed sensors are enumerated in the export. Fold/hinge/hall/posture/display candidates register automatically. Vendor values remain raw until their meaning is verified."))
            val picker = Spinner(this)
            picker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sensors.sensors.map { "[${it.type}] ${it.name}" })
            content.addView(picker)
            val descriptor = label("")
            content.addView(descriptor)
            picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { descriptor.text = sensors.descriptor(sensors.sensors[position]) }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            content.addView(button("Probe selected sensor") {
                sensors.sensors.getOrNull(picker.selectedItemPosition)?.let { sensors.probe(it); descriptor.text = sensors.descriptor(it) }
            })
        }
        if (tab == "Displays") {
            content.addView(button("Start dual-screen session (120 seconds)") { windowArea.requestPresentation(); refresh() })
            content.addView(button("End dual-screen session") { windowArea.close(); refresh() })
            content.addView(button("Try Presentation on eligible displays") { displays.probePresentations(); toast("Presentation result recorded") })
            content.addView(button("Try activity on secondary display") { displays.probeSecondaryActivity(); toast("Launch result recorded") })
            content.addView(label("These explicit probes may briefly show a test panel. Success must be checked against the actual display ID and physical panel visibility."))
        }
        if (tab == "Options") {
            content.addView(label("Default: ordinary app sensors + public WindowManager / DisplayManager. No root, ADB grants, Shizuku, overlays, or accessibility service."))
            content.addView(Switch(this).apply {
                text = "Timed fallback (450 ms, ESTIMATED)"; isChecked = app.folds.estimatedMode
                setOnCheckedChangeListener { _, enabled -> app.folds.setEstimator(enabled); log.record("user", "estimator", mapOf("enabled" to enabled)) }
            })
            content.addView(Switch(this).apply {
                text = "Optional non-SDK DeviceStateManager probe"; isChecked = nonSdk
                setOnCheckedChangeListener { _, enabled ->
                    nonSdk = enabled; log.record("user", "nonSdkDeviceState", mapOf("enabled" to enabled))
                    if (enabled) deviceState.start() else deviceState.stop()
                }
            })
            content.addView(button("Probe non-SDK sensor permission metadata") { sensors.probeNonSdkPermissionMetadata(); toast("Metadata probe recorded") })
            content.addView(label("Non-SDK probes only attempt read-only reflection. Android restrictions are respected and failures recorded. Device-state IDs are never guessed to mean closed/open."))
        }
        content.addView(live)
        refresh()
    }

    private fun refresh() {
        val r = app.folds.state.value
        val value = if (r.quality == FoldProgressQuality.UNAVAILABLE) "—" else String.format(Locale.US, "%.3f", r.progress)
        val heading = "Fold Probe ${BuildConfig.VERSION_NAME}\np=$value • ${r.quality}"
        if (header.text.toString() != heading) header.text = heading
        val body = when (tab) {
            "Live" -> "Raw hinge angle: ${r.rawAngle?.let { "$it°" } ?: "unavailable"}\n${r.source}\n${r.note}\n" +
                "Last reading elapsed ms: ${r.receivedNs / 1_000_000}\nSample age: ${if (r.receivedNs == 0L) "n/a" else "${(SystemClock.elapsedRealtimeNanos() - r.receivedNs) / 1_000_000} ms"}\n" +
                "\nSENSORS\n${sensors.summary()}\n\nPOSTURE\n${jetpack.summary}\n\nDEVICE STATE\n${deviceState.summary}\n\nDISPLAY\n${displays.summary}"
            "Sensors" -> sensors.summary()
            "Displays" -> windowArea.summary + "\n\n" + displays.summary
            "Packages" -> packages
            "Events" -> "Newest first; UI shows last 150 events. Full raw stream is in export.\n\n" + log.lines.value.joinToString("\n\n")
            else -> "Device-state probe: ${deviceState.summary}\n\nLog session: ${log.session}\nStorage: ${log.storageError.value ?: "No error reported"}\n\nTest: mark → slowly unfold → hold halfway → reverse → open fully → export."
        }
        if (live.text.toString() != body) live.text = body
    }

    private fun export() {
        log.export { result ->
            runOnUiThread {
                result.fold({ file ->
                    if (!isFinishing && !isDestroyed) {
                        val uri = FileProvider.getUriForFile(this, "$packageName.logs", file)
                        val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .apply { clipData = ClipData.newRawUri("Fold Probe logs", uri) }
                        try { startActivity(Intent.createChooser(intent, "Save or share Fold Probe log")) }
                        catch (e: Exception) { log.failure("export", "share", e); toast("ZIP saved privately; collect through ADB") }
                    }
                }, { error -> log.failure("export", "failed", error); toast("Export failed: ${error.message}") })
            }
        }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    override fun onStart() {
        super.onStart(); log.record("lifecycle", "onStart", mapOf("instance" to System.identityHashCode(this)))
        app.startMonitoring(this); displays.start(); jetpack.start(lifecycleScope)
        windowArea.start(lifecycleScope)
        if (nonSdk) deviceState.start()
        running = true; handler.post(updater)
    }
    override fun onResume() { super.onResume(); log.record("lifecycle", "onResume"); displays.snapshot("resume") }
    override fun onPause() { log.record("lifecycle", "onPause"); super.onPause() }
    override fun onStop() {
        log.record("lifecycle", "onStop"); running = false; handler.removeCallbacks(updater)
        jetpack.stop(); app.stopMonitoring(this); displays.stop(); deviceState.stop()
        windowArea.stopObserving()
        super.onStop()
    }
    override fun onDestroy() { windowArea.destroy(); unregisterReceiver(screenEvents); log.record("lifecycle", "onDestroy", mapOf("changingConfigurations" to isChangingConfigurations)); super.onDestroy() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); log.record("configuration", "changed", mapOf("value" to newConfig.toString())); displays.snapshot("configurationChanged") }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); log.record("lifecycle", "windowFocus", mapOf("focused" to hasFocus)) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("nonSdk", nonSdk); outState.putString("tab", tab); super.onSaveInstanceState(outState) }
}
