package dev.foldprobe.animation

import android.content.res.Configuration
import android.content.Intent
import android.app.role.RoleManager
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.foldprobe.ProbeApplication
import dev.foldprobe.display.*
import dev.foldprobe.fold.JetpackFoldProvider
import dev.foldprobe.fold.DiagnosticAngleSource
import dev.foldprobe.fold.DiagnosticReading
import dev.foldprobe.home.HomeApps
import dev.foldprobe.home.AppPicker
import dev.foldprobe.home.CoverTouchPolicy
import dev.foldprobe.home.TouchSequenceGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.combine

class PreviewActivity : ComponentActivity() {
    private val app get() = application as ProbeApplication
    // HOME alias and direct activity launches must share the same settings file.
    private val preferences get() = getSharedPreferences("FoldStudy", MODE_PRIVATE)
    private lateinit var controller: VisualProgressController
    private lateinit var areas: WindowAreaProbe
    private lateinit var displays: DisplayCoordinator
    private lateinit var jetpack: JetpackFoldProvider
    private lateinit var diagnostic: DiagnosticAngleSource
    private lateinit var overlap: EarlyOverlapPresentation
    private var overlapObserver: kotlinx.coroutines.Job? = null
    private var automaticObserver: kotlinx.coroutines.Job? = null
    private var foregroundHeartbeat: kotlinx.coroutines.Job? = null
    private var automaticCyclePrepared = false
    private var automatic by mutableStateOf(true)
    // Normal automation always retains the validated foreground display lease.
    // Old preferences/debug extras must not silently restore endpoint remapping.
    private val steadyTrial = true
    private var shader by mutableStateOf(false)
    private var coverPerspective by mutableStateOf(false)
    private var fullscreen by mutableStateOf(false)
    private lateinit var homeApps: HomeApps
    private var homeLoading: kotlinx.coroutines.Job? = null
    private var editingSlot by mutableStateOf<Int?>(null)
    private var navigationBusy by mutableStateOf(false)
    private var navigationJob: kotlinx.coroutines.Job? = null
    private var defaultHome by mutableStateOf(false)
    private var homeEntry by mutableStateOf(false)
    private val homeSurface get() = defaultHome || homeEntry
    private val homeRoleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshHomeRole()
        app.log.record("homeRole", "choiceReturned", mapOf("held" to defaultHome, "result" to it.resultCode))
    }
    private lateinit var coverTouchPolicy: CoverTouchPolicy
    private val primaryTouchGuard = TouchSequenceGuard()
    private var coverTouchBlocked by mutableStateOf(false)
    private var touchObserver: kotlinx.coroutines.Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        setIntent(sanitizedEntry(intent))
        super.onCreate(savedInstanceState)
        homeEntry = intent.hasCategory(Intent.CATEGORY_HOME)
        refreshHomeRole()
        controller = VisualProgressController(lifecycleScope, app.log,
            savedInstanceState?.getFloat("progress") ?: 0f)
        diagnostic = DiagnosticAngleSource(this, lifecycleScope, app.log)
        homeApps = HomeApps(this)
        coverTouchPolicy = CoverTouchPolicy(savedInstanceState?.getBoolean("coverTouchBlocked") ?: false)
        coverTouchBlocked = coverTouchPolicy.blocked
        automatic = preferences.getBoolean("automaticOverlap", true)
        coverPerspective = preferences.getBoolean("coverPerspective", false)
        shader = savedInstanceState?.getBoolean("shader") ?: intent.getBooleanExtra("shader", false)
        fullscreen = savedInstanceState?.getBoolean("fullscreen") ?: intent.getBooleanExtra("fullscreen", true)
        val mode = savedInstanceState?.getString("mode") ?: intent.getStringExtra("mode")
        if (mode == "MANUAL") controller.manual(savedInstanceState?.getFloat("progress") ?: intent.getFloatExtra("progress", 0f))
        else if (mode == "ESTIMATED") controller.mode(PreviewMode.ESTIMATED)
        else if (mode == "DIAGNOSTIC" || mode == null) controller.mode(PreviewMode.DIAGNOSTIC)
        displays = DisplayCoordinator(this, app.log)
        jetpack = JetpackFoldProvider(this, app.log, app.folds)
        areas = WindowAreaProbe(this, app.log, { displays.snapshot(it) }, ::outerPanel)
        overlap = EarlyOverlapPresentation(this, app.log, ::secondaryPanel)
        app.log.record("preview", "created", mapOf("version" to dev.foldprobe.BuildConfig.VERSION_NAME,
            "homeEntry" to homeEntry, "defaultHome" to defaultHome, "steadyTrial" to steadyTrial))
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
            LaunchedEffect(fullscreen) { applyFullscreen() }
            BackHandler(enabled = fullscreen || homeSurface) {
                if (editingSlot != null) editingSlot = null
                else if (homeSurface) fullscreen = true
                else leaveFullscreen()
            }
            if (fullscreen) FullscreenScene() else PreviewControls()
            val home by homeApps.state.collectAsState()
            editingSlot?.let { slot ->
                AppPicker(slot, home, onChoose = { component ->
                    editingSlot = null
                    navigationBusy = true
                    lifecycleScope.launch {
                        try {
                            homeApps.assign(slot, component)
                            app.log.record("home", "layoutSaved", mapOf("slot" to slot))
                        } catch (e: Exception) {
                            app.log.failure("home", "saveFailed", e)
                            message("Kunde inte spara placeringen. Försök igen.")
                        } finally { navigationBusy = false }
                    }
                }, onDismiss = { editingSlot = null })
            }
        } } }
    }
    override fun onNewIntent(newIntent: Intent) {
        val entry = sanitizedEntry(newIntent)
        super.onNewIntent(entry)
        setIntent(entry)
        homeEntry = entry.hasCategory(Intent.CATEGORY_HOME)
        refreshHomeRole()
        if (homeEntry || entry.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            navigationJob?.cancel(); navigationJob = null
            navigationBusy = false
            editingSlot = null
            fullscreen = true
            controller.mode(PreviewMode.DIAGNOSTIC)
        }
        app.log.record("homeRole", "newIntent", mapOf("homeEntry" to homeEntry,
            "instance" to System.identityHashCode(this), "taskId" to taskId))
    }
    private fun sanitizedEntry(value: Intent): Intent {
        // Android enforces the private target's caller boundary. The exported HOME alias
        // reaches the same Activity, so it must not forward attacker-chosen debug controls.
        val privateTarget = value.component?.packageName == packageName &&
            value.component?.className == PreviewActivity::class.java.name
        return if (privateTarget) value else Intent(value).replaceExtras(null as Bundle?)
    }
    private fun refreshHomeRole() {
        defaultHome = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
        if (homeSurface) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun chooseHome() {
        if (navigationBusy) return
        navigationBusy = true
        navigationJob = lifecycleScope.launch {
            if (!prepareNavigation()) return@launch
            try {
                val roles = getSystemService(RoleManager::class.java)
                if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    homeRoleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                } else startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                app.log.failure("homeRole", "chooserFailed", e)
                message("Öppna Inställningar → Appar → Välj standardappar → Hemapp för att byta hemskärm.")
            } finally { navigationBusy = false }
        }
    }
    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    private fun leaveFullscreen() {
        automaticCyclePrepared = false
        diagnostic.earlyDisplay(false)
        fullscreen = false
    }
    private fun changeAutomatic(enabled: Boolean) {
        automatic = enabled
        automaticCyclePrepared = false
        preferences.edit().putBoolean("automaticOverlap", enabled).apply()
        app.log.record("automaticOverlap", "enabled", mapOf("enabled" to enabled))
        if (!enabled) diagnostic.earlyDisplay(false, automatic = true)
    }
    @Composable private fun FullscreenScene() {
        val reading by controller.state.collectAsState()
        val home by homeApps.state.collectAsState()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 600.dp
            // The home remains usable without Shizuku; this is a static panel layout,
            // never a fabricated live hinge reading.
            val sceneReading = if (homeSurface && reading.mode == PreviewMode.DIAGNOSTIC && !reading.diagnostic.live)
                reading.copy(progress = if (compact) 0f else 1f) else reading
            DuoHome(sceneReading, outer = compact, useShader = shader, modifier = Modifier.fillMaxSize(),
                coverPerspective = coverPerspective,
                home = home, enabled = !navigationBusy && (!compact || !coverTouchBlocked), onOpen = ::openSlot, onEdit = ::editSlot,
                onViewport = { w, h, v -> recordProjection("primary", w, h, v) },
                onShaderStatus = { reportShader("primary", it) })
            TextButton(enabled = !compact || !coverTouchBlocked, onClick = { leaveFullscreen() }, modifier = Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.systemBars).padding(8.dp)) { Text("Kontroller") }
            Text(if (compact && coverTouchBlocked) "Utskärmen visar bara animationen" else "Håll inne en ikon för att byta app", color = Color.White.copy(alpha = .7f),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.systemBars).padding(12.dp))
            if (reading.mode == PreviewMode.DIAGNOSTIC && !reading.diagnostic.live) {
                Text(if (homeSurface) "Vikningen är pausad · apparna går att öppna" else "Vinkelmätningen är pausad", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = .7f)).padding(12.dp))
            }
            val blackCover = steadyTrial && compact && (reading.displayedAngle ?: 0f) >= 178f
            LaunchedEffect(blackCover) {
                if (steadyTrial) app.log.record("steadyTrial", "coverBlack", mapOf("enabled" to blackCover,
                    "angle" to reading.displayedAngle, "meaning" to "Black pixels, not panel power off"))
            }
            if (blackCover) Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
    private fun recordProjection(panel: String, width: Float, height: Float, viewport: SceneViewport) {
        app.log.record("scene", "projection", mapOf("panel" to panel, "widthPx" to width,
            "heightPx" to height, "pixelsPerUnit" to viewport.scale, "leftPx" to viewport.left,
            "topPx" to viewport.top, "sceneWidth" to DuoLayout.WIDTH, "sceneHeight" to DuoLayout.HEIGHT))
    }
    private fun outerPanel(context: android.content.Context) = secondaryPanel(context, true)
    private fun secondaryPanel(context: android.content.Context, outer: Boolean): android.view.View {
        val panelView = ComposeView(context).apply {
                setViewTreeLifecycleOwner(this@PreviewActivity)
                setViewTreeSavedStateRegistryOwner(this@PreviewActivity)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val reading by controller.state.collectAsState()
                    val home by homeApps.state.collectAsState()
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        val panel = if (outer) "outer" else "secondaryInner"
                        Box(Modifier.fillMaxSize()) {
                            DuoHome(reading, outer = outer, useShader = shader, modifier = Modifier.fillMaxSize(),
                                coverPerspective = coverPerspective,
                                home = home, enabled = !navigationBusy && !outer, onOpen = ::openSlot, onEdit = ::editSlot,
                                onViewport = { w, h, v -> recordProjection(panel, w, h, v) },
                                onShaderStatus = { reportShader(panel, it) })
                            if (!outer) TextButton(onClick = { leaveFullscreen() },
                                modifier = Modifier.align(Alignment.TopEnd)
                                    .windowInsetsPadding(WindowInsets.systemBars).padding(8.dp)) {
                                Text("Kontroller")
                            }
                        }
                    }
                }
        }
        if (!outer) return panelView
        // A secondary cover only exists while the phone is open. Swallow the whole stream,
        // including empty areas, so a hidden control cannot react to the user's grip.
        return object : android.widget.FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) logBlockedCoverTouch("secondary")
                return true
            }
        }.apply {
            // WindowRecomposer resolves the presentation content root, which is this
            // touch guard on the cover, rather than the nested ComposeView.
            setViewTreeLifecycleOwner(this@PreviewActivity)
            setViewTreeSavedStateRegistryOwner(this@PreviewActivity)
            addView(panelView, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
    }
    @Composable private fun PreviewControls() {
        val reading by diagnostic.state.collectAsState()
        val shizukuAction by diagnostic.shizukuAction.collectAsState()
        Column(Modifier.fillMaxSize().background(Color(0xFF0E161F))
            .windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Fold Study", style = MaterialTheme.typography.headlineMedium)
            Text("Automatisk vikning behåller skärmläget medan hemskärmen är aktiv. " +
                "Utskärmen visas svart helt öppen. Appstart kan fortfarande ge ett kort blink.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = automatic, onCheckedChange = ::changeAutomatic)
                Text("Automatisk vikning", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp))
            }
            Text("Det här är det enda läget du behöver. Skärmarna följer öppning och stängning. " +
                "Utskärmen visar animationen utan att reagera på fingrarna under vikningen.")
            if (Build.VERSION.SDK_INT >= 33) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = coverPerspective, onCheckedChange = { selected ->
                        coverPerspective = selected
                        preferences.edit().putBoolean("coverPerspective", selected).apply()
                        app.log.record("preview", "coverPerspective", mapOf("enabled" to selected))
                    })
                    Text("Perspektiv på utskärmen", modifier = Modifier.padding(start = 12.dp))
                }
                Text("Testläge: bilden ändrar perspektiv medan du viker. Stäng av för att jämföra med enbart oskärpa.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(if (automatic) "Automatiken fortsätter när du går tillbaka till hemskärmen."
                else "Automatiken är avstängd. Du kan fortfarande öppna dina appar.",
                color = Color(0xFFB7D4D6))
            if (reading.live) Text("Vinkelmätningen är ansluten", color = Color(0xFFB7D4D6))
            else {
                Text("Starta Shizuku för att aktivera vikanimationen.")
                OutlinedButton(onClick = {
                    if (!diagnostic.requestShizuku()) {
                        val manager = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (manager != null) startActivity(manager)
                        else startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/")))
                    }
                }) { Text(shizukuAction) }
            }
            Button(onClick = { controller.mode(PreviewMode.DIAGNOSTIC); fullscreen = true }) {
                Text("Tillbaka till hemskärmen")
            }
            OutlinedButton(enabled = !navigationBusy, onClick = ::chooseHome) {
                Text(if (defaultHome) "Byt hemskärm" else "Använd som hemskärm")
            }
            Text("En lång vikning visas i högst 45 sekunder. Ett kort blink kan fortfarande " +
                "synas när telefonen byter skärmläge.", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable private fun LegacyPreviewControls() {
        val reading by controller.state.collectAsState()
        val home by homeApps.state.collectAsState()
        val shizukuAction by diagnostic.shizukuAction.collectAsState()
        val earlyStatus by diagnostic.earlyDisplayStatus.collectAsState()
        val scrub: (Float) -> Unit = remember(controller) { { controller.manual(it) } }
        val finishScrub: () -> Unit = remember(controller) { { controller.markManual() } }
        var areaText by remember { mutableStateOf(areas.summary) }
        LaunchedEffect(Unit) { while (true) { areaText = areas.summary; delay(250) } }
        Column(Modifier.fillMaxSize().background(Color(0xFF0E161F)).windowInsetsPadding(WindowInsets.systemBars).padding(12.dp)) {
            Text("Fold Study · visuell prototyp", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !navigationBusy, onClick = ::chooseHome) {
                    Text(if (defaultHome) "Byt hemskärm" else "Använd som hemskärm")
                }
                if (defaultHome) Text("Vald hemskärm", modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall)
            }
            Text("${reading.label}   •   p=${"%.2f".format(reading.progress)}   •   mätt vinkel: ${reading.displayedAngle ?: "—"}°",
                style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PreviewMode.entries.forEach { mode ->
                    FilterChip(selected = reading.mode == mode, onClick = {
                        if (mode != PreviewMode.DIAGNOSTIC) changeAutomatic(false)
                        controller.mode(mode)
                    },
                        label = { Text(when (mode) { PreviewMode.SENSOR -> "Sensor"; PreviewMode.DIAGNOSTIC -> "Vikvinkel"; PreviewMode.ESTIMATED -> "Uppskattad"; PreviewMode.MANUAL -> "Manuell" }) })
                }
            }
            if (reading.mode == PreviewMode.DIAGNOSTIC) {
                Text("Mätt vinkel · mjuk övergång 80 ms · stannar om signalen försvinner",
                    style = MaterialTheme.typography.labelSmall)
                if (!intent.hasExtra("bridgeToken")) TextButton(onClick = {
                    if (!diagnostic.requestShizuku()) {
                        val manager = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (manager != null) startActivity(manager)
                        else startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://shizuku.rikka.app/download/")))
                    }
                }) { Text(shizukuAction) }
                if (!intent.hasExtra("bridgeToken")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = automatic, onCheckedChange = { changeAutomatic(it) })
                        Text("Starta automatiskt när jag öppnar", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text("Stäng och lås upp med Fold Study synlig. Varje öppning visas i högst 45 sekunder.",
                        style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            changeAutomatic(false)
                            areas.close("early display trial"); fullscreen = true; diagnostic.earlyDisplay(true)
                        }) {
                            Text("Testa tidig överlappning")
                        }
                        TextButton(onClick = { changeAutomatic(false) }) { Text("Stoppa test") }
                    }
                    OutlinedButton(onClick = {
                        changeAutomatic(false)
                        areas.close("outer-first trial"); fullscreen = true; diagnostic.earlyDisplay(true, outerFirst = true)
                    }) { Text("Testa utskärmen först") }
                    Text("Behåller båda skärmarna tända tills du avslutar, högst 45 sekunder.",
                        style = MaterialTheme.typography.labelSmall)
                    Text(earlyStatus, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (reading.mode == PreviewMode.MANUAL) Slider(value = reading.progress, onValueChange = scrub,
                onValueChangeFinished = finishScrub)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (Build.VERSION.SDK_INT >= 33) FilterChip(selected = shader, onClick = {
                    shader = !shader; app.log.record("preview", "shader", mapOf("enabled" to shader))
                }, label = { Text("AGSL") })
                Button(enabled = !earlyStatus.startsWith("Tidig") && !earlyStatus.startsWith("Redo"),
                    onClick = { areas.requestPresentation() }) { Text("Visa utskärmen") }
                OutlinedButton(onClick = { areas.close() }) { Text("Avsluta") }
                TextButton(onClick = {
                    areas.close("diagnostics")
                    startActivity(android.content.Intent(this@PreviewActivity, dev.foldprobe.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }) { Text("Diagnostik") }
            }
            Text(areaText, style = MaterialTheme.typography.labelSmall, maxLines = 3)
            TextButton(onClick = { fullscreen = true }) { Text("Visa helskärm") }
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // Compact windows use the cover-sized crop; IDs change roles on this phone.
                val compact = maxWidth < 600.dp
                LaunchedEffect(compact, maxWidth, maxHeight) {
                    app.log.record("preview", "viewport", mapOf("compact" to compact,
                        "widthDp" to maxWidth.value, "heightDp" to maxHeight.value,
                        "progress" to reading.progress, "angle" to reading.displayedAngle))
                }
                DuoHome(reading, outer = compact, useShader = shader, modifier = Modifier.fillMaxSize(),
                    coverPerspective = coverPerspective,
                    home = home, enabled = !navigationBusy && (!compact || !coverTouchBlocked), onOpen = ::openSlot, onEdit = ::editSlot,
                    onShaderStatus = { reportShader("primary", it) })
            }
        }
    }
    override fun onStart() {
        super.onStart(); app.startMonitoring(this); displays.start()
        homeLoading?.cancel()
        homeLoading = lifecycleScope.launch {
            try {
                homeApps.refresh()
                app.log.record("home", "catalogLoaded", mapOf("count" to homeApps.state.value.apps.size))
            } catch (e: Exception) {
                app.log.failure("home", "catalogFailed", e)
                message("Kunde inte läsa apparna. Öppna Fold Study igen för att försöka på nytt.")
            }
        }
        reportLifecycle("started")
        touchObserver = lifecycleScope.launch {
            combine(diagnostic.state, diagnostic.earlyDisplayStatus) { reading, status -> reading to status }
                .collect { (reading, status) ->
                    val fresh = reading.live && android.os.SystemClock.elapsedRealtime() - reading.receivedMs <= 1000
                    val blocked = coverTouchPolicy.update(if (fresh) reading.angle?.toInt() else null,
                        status.startsWith("Tidig skärmstart begärd"))
                    if (blocked != coverTouchBlocked) {
                        coverTouchBlocked = blocked
                        app.log.record("coverTouch", "changed", mapOf("blocked" to blocked, "angle" to reading.angle))
                    }
                    updatePrimaryTouchGuard()
                    if (blocked && primaryIsCover()) editingSlot = null
                }
        }
        jetpack.start(lifecycleScope); controller.observe(app.folds); areas.start(lifecycleScope)
        controller.observeDiagnostic(diagnostic)
        overlapObserver = lifecycleScope.launch {
            diagnostic.earlyDisplayStatus.collect { overlap.setEnabled(it.startsWith("Tidig skärmstart begärd")) }
        }
        diagnostic.start(intent.getStringExtra("bridgeToken"))
        if (intent.getBooleanExtra("autoOuter", false)) {
            intent.removeExtra("autoOuter")
            lifecycleScope.launch { delay(1500); if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) areas.requestPresentation() }
        }
    }
    override fun onResume() {
        super.onResume()
        refreshHomeRole()
        navigationBusy = false
        automaticCyclePrepared = false
        automaticObserver?.cancel()
        foregroundHeartbeat?.cancel()
        foregroundHeartbeat = lifecycleScope.launch {
            while (true) {
                val reading = diagnostic.state.value
                if (steadyTrial && automaticCyclePrepared && automaticEligible(reading)) {
                    diagnostic.renewForegroundLease()
                }
                delay(250)
            }
        }
        automaticObserver = lifecycleScope.launch {
            diagnostic.state.collect { reading ->
                val eligible = automaticEligible(reading)
                if (eligible && !automaticCyclePrepared) {
                    automaticCyclePrepared = true
                    areas.close("automatic cycle")
                    app.log.record("automaticOverlap", "prepared", mapOf("angle" to reading.angle))
                    diagnostic.automaticDisplay(steadyTrial)
                } else if (!eligible && automaticCyclePrepared) {
                    automaticCyclePrepared = false
                    diagnostic.earlyDisplay(false, automatic = true)
                }
            }
        }
    }
    override fun onPause() {
        foregroundHeartbeat?.cancel(); foregroundHeartbeat = null
        automaticObserver?.cancel(); automaticObserver = null
        automaticCyclePrepared = false
        if (automatic) diagnostic.earlyDisplay(false, automatic = true)
        super.onPause()
    }
    private fun automaticEligible(reading: DiagnosticReading): Boolean =
        automatic && fullscreen && !navigationBusy && editingSlot == null &&
            controller.state.value.mode == PreviewMode.DIAGNOSTIC && !intent.hasExtra("bridgeToken") &&
            diagnostic.canRequestEarlyDisplay && reading.live &&
            android.os.SystemClock.elapsedRealtime() - reading.receivedMs <= 1000 &&
            !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
            getSystemService(android.os.PowerManager::class.java).isInteractive
    override fun onStop() {
        reportLifecycle("stopped")
        navigationJob?.cancel(); navigationJob = null
        touchObserver?.cancel(); touchObserver = null
        homeLoading?.cancel(); homeLoading = null
        overlapObserver?.cancel(); overlapObserver = null; overlap.stop()
        diagnostic.stop(); areas.close("preview stopped"); areas.stopObserving(); controller.stop(); jetpack.stop(); displays.stop()
        app.stopMonitoring(this); super.onStop()
    }
    override fun onDestroy() { reportLifecycle("destroyed"); areas.destroy(); super.onDestroy() }
    private fun reportLifecycle(event: String) {
        app.log.record("previewLifecycle", event, mapOf("instance" to System.identityHashCode(this),
            "displayId" to display?.displayId, "changingConfigurations" to isChangingConfigurations,
            "progress" to controller.state.value.progress, "angle" to controller.state.value.displayedAngle))
    }
    private fun reportShader(panel: String, error: Throwable?) {
        if (error != null) app.log.failure("preview", "shaderFailed:$panel", error)
        else app.log.record("preview", "shaderReady", mapOf("panel" to panel))
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); reportLifecycle("configurationChanged")
        updatePrimaryTouchGuard()
        displays.snapshot("previewConfiguration")
        applyFullscreen()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode", controller.state.value.mode.name); outState.putFloat("progress", controller.state.value.progress)
        outState.putBoolean("shader", shader); outState.putBoolean("fullscreen", fullscreen)
        outState.putBoolean("coverTouchBlocked", coverTouchBlocked)
        super.onSaveInstanceState(outState)
    }

    private fun message(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun primaryIsCover(): Boolean = display?.mode?.let {
        it.physicalWidth == 1248 && it.physicalHeight == 1972
    } == true

    private fun updatePrimaryTouchGuard() {
        if (primaryTouchGuard.setBlocked(primaryIsCover() && coverTouchBlocked)) {
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            app.log.record("coverTouch", "gestureCancelled")
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        updatePrimaryTouchGuard()
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchSequenceGuard.Action.DOWN
            MotionEvent.ACTION_UP -> TouchSequenceGuard.Action.UP
            MotionEvent.ACTION_CANCEL -> TouchSequenceGuard.Action.CANCEL
            else -> TouchSequenceGuard.Action.OTHER
        }
        if (!primaryTouchGuard.allows(action)) {
            if (action == TouchSequenceGuard.Action.DOWN) logBlockedCoverTouch("primary")
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun logBlockedCoverTouch(panel: String) {
        app.log.record("coverTouch", "blockedDown", mapOf("panel" to panel,
            "angle" to diagnostic.state.value.angle))
    }

    private fun isPhonePanel(display: android.view.Display): Boolean =
        (display.mode.physicalWidth == 1248 && display.mode.physicalHeight == 1972) ||
            (display.mode.physicalWidth == 2448 && display.mode.physicalHeight == 1848)

    /** Finish our panel request before launching an activity on normal display 0. */
    private suspend fun prepareNavigation(): Boolean {
        navigationBusy = true
        val manager = getSystemService(android.hardware.display.DisplayManager::class.java)
        val handoff = diagnostic.earlyDisplayStatus.value.startsWith("Tidig skärmstart begärd") ||
            manager.displays.any { it.displayId != android.view.Display.DEFAULT_DISPLAY && isPhonePanel(it) }
        val began = android.os.SystemClock.elapsedRealtime()
        app.log.record("home", "navigationStarted", mapOf("handoff" to handoff))
        overlap.holdForNavigation(handoff)
        areas.close("home navigation")
        val cancellation = diagnostic.earlyDisplay(false)
        overlap.setEnabled(false)
        val ready = try { withTimeoutOrNull(2500) {
            cancellation.join()
            if (handoff) {
                var stableSince = 0L
                while (true) {
                    val active = manager.displays.filter(::isPhonePanel)
                    val normal = active.size == 1 && active.single().displayId == android.view.Display.DEFAULT_DISPLAY &&
                        active.single().state == android.view.Display.STATE_ON
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!normal) stableSince = 0L
                    else if (stableSince == 0L) stableSince = now
                    else if (now - stableSince >= 150) break
                    delay(20)
                }
            }
            true
        } == true } finally {
            overlap.holdForNavigation(false)
        }
        app.log.record("home", "navigationReady", mapOf("ready" to ready,
            "handoff" to handoff, "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - began)))
        if (!ready || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            navigationBusy = false
            message("Kunde inte växla skärm. Försök igen.")
            return false
        }
        return true
    }

    private fun openSlot(index: Int) {
        if (navigationBusy) return
        val selected = homeApps.state.value.appAt(index) ?: return editSlot(index)
        navigationBusy = true
        navigationJob = lifecycleScope.launch {
            if (!prepareNavigation()) return@launch
            try {
                val component = checkNotNull(android.content.ComponentName.unflattenFromString(selected.component))
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setComponent(component)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
                startActivity(intent, options.toBundle())
                app.log.record("home", "launched", mapOf("component" to selected.component, "slot" to index))
            } catch (e: Exception) {
                navigationBusy = false
                app.log.failure("home", "launchFailed", e)
                message("Appen kunde inte öppnas. Håll inne ikonen för att välja en annan.")
            }
        }
    }

    private fun editSlot(index: Int) {
        if (navigationBusy || !homeApps.state.value.loaded) return
        navigationBusy = true
        navigationJob = lifecycleScope.launch {
            if (prepareNavigation()) { editingSlot = index; navigationBusy = false }
        }
    }
}
