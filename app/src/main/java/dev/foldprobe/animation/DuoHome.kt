package dev.foldprobe.animation

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.foldprobe.home.HomeAppsState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun DuoHome(reading: VisualReading, outer: Boolean, useShader: Boolean, modifier: Modifier = Modifier,
    home: HomeAppsState = HomeAppsState(), enabled: Boolean = true, coverPerspective: Boolean = false,
    onOpen: (Int) -> Unit = {}, onEdit: (Int) -> Unit = {},
    onViewport: (Float, Float, SceneViewport) -> Unit = { _, _, _ -> },
    onShaderStatus: (Throwable?) -> Unit = {}) {
    val shaderResult = remember(useShader) {
        if (Build.VERSION.SDK_INT >= 33 && useShader) runCatching { DuoShader() } else null
    }
    val shader = shaderResult?.getOrNull()
    LaunchedEffect(shaderResult) { if (shaderResult != null) onShaderStatus(shaderResult.exceptionOrNull()) }
    val sweepResult = remember {
        if (Build.VERSION.SDK_INT >= 33) runCatching { RevealSweepShader() } else null
    }
    val sweep = sweepResult?.getOrNull()
    LaunchedEffect(sweepResult) { if (sweepResult != null) onShaderStatus(sweepResult.exceptionOrNull()) }
    BoxWithConstraints(modifier.clipToBounds().background(Color(0xFF101823))) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val viewport = DuoLayout.viewport(width, height, outer)
        LaunchedEffect(width, height, outer) { onViewport(width, height, viewport) }
        // Measure the entire shared scene even when only the cover crop is visible.
        // One local dp/sp represents one scene unit, independent of either panel's UI density.
        val originalConfiguration = LocalViewConfiguration.current
        val sceneConfiguration = remember(originalConfiguration) { object : ViewConfiguration by originalConfiguration {
            // Defaults are UI dp; inside this scene a dp is approximately a physical millimetre.
            override val minimumTouchTargetSize = DpSize(10.dp, 10.dp)
        } }
        Layout(content = {
            CompositionLocalProvider(LocalDensity provides Density(viewport.scale, fontScale = 1f),
                LocalViewConfiguration provides sceneConfiguration) {
                SharedScene(reading.progress, outer, shader, sweep, home, enabled, coverPerspective, onOpen, onEdit)
            }
        }) { measurables, constraints ->
            val scene = measurables.single().measure(Constraints.fixed(
                (DuoLayout.WIDTH * viewport.scale).roundToInt().coerceAtLeast(1),
                (DuoLayout.HEIGHT * viewport.scale).roundToInt().coerceAtLeast(1)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                scene.place(viewport.left.roundToInt(), viewport.top.roundToInt())
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SharedScene(progress: Float, outer: Boolean, shader: DuoShader?, sweep: RevealSweepShader?, home: HomeAppsState, enabled: Boolean,
    coverPerspective: Boolean,
    onOpen: (Int) -> Unit, onEdit: (Int) -> Unit) {
    val p = DuoLayout.bounded(progress)
    val density = LocalDensity.current
    val width = with(density) { DuoLayout.WIDTH.dp.toPx() }
    val height = with(density) { DuoLayout.HEIGHT.dp.toPx() }
    val clockX = DuoLayout.COVER_LEFT + 6f
    val dockX = DuoLayout.COVER_LEFT + DuoLayout.COVER_WIDTH / 2
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val date = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    // Each physical panel gets its own moving mask over the complete scene.
    // Keep the validated uniform treatment as a fallback when runtime shaders are unavailable.
    Box(Modifier.fillMaxSize().then(if (outer && sweep == null) Modifier.foldReveal(.75f, p, true) else Modifier)
        .graphicsLayer {
            if (Build.VERSION.SDK_INT >= 33 && sweep != null)
                renderEffect = sweep.effect(size.width, size.height, p, outer, coverPerspective)
        }.graphicsLayer {
        if (Build.VERSION.SDK_INT >= 33 && shader != null) renderEffect = shader.effect(size.width, size.height, p)
    }.background(Brush.radialGradient(listOf(Color(0xFF326C72), Color(0xFF192F42), Color(0xFF101823)),
        center = androidx.compose.ui.geometry.Offset(width * .65f, height * .32f), radius = width * 1.1f))) {
        Box(Modifier.offset(34.dp, 6.dp).size(116.dp).graphicsLayer { rotationZ = -28f }
            .border(.15.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(22.dp)))
        if (!outer) CalendarSurface(now, Modifier.offset(8.dp, 12.dp).width(64.dp)
            .then(if (sweep == null) Modifier.foldReveal(.25f, p) else Modifier))
        Column(Modifier.offset(clockX.dp, 12.dp)) {
            Text(clock.format(Date(now)), color = Color.White, fontSize = 8.5.sp, lineHeight = 10.sp, letterSpacing = 0.sp)
            Text(date.format(Date(now)), color = Color(0xFFB7D4D6), fontSize = 2.5.sp, lineHeight = 3.2.sp, letterSpacing = 0.sp)
        }
        (0 until 16).forEach { index ->
            val app = home.appAt(index)
            val label = app?.label ?: if (!home.loaded) "Laddar…" else if (home.slots[index] != null) "App saknas" else "Välj app"
            val t = DuoLayout.icon(index, p, false)
            Column(Modifier.offset((DuoLayout.WIDTH * t.x - 5f).dp, (DuoLayout.HEIGHT * t.y).dp)
                .width(10.dp).graphicsLayer {
                    rotationY = t.rotationY; scaleX = t.scale; scaleY = t.scale; cameraDistance = 16 * density.density
                }.combinedClickable(enabled = enabled && home.loaded,
                    onClickLabel = "Öppna $label", onLongClickLabel = "Byt app",
                    onClick = { if (app != null) onOpen(index) else onEdit(index) },
                    onLongClick = { onEdit(index) }), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(7.2.dp).graphicsLayer {
                    shadowElevation = .6.dp.toPx(); shape = RoundedCornerShape(2.dp); clip = true
                }, contentAlignment = Alignment.Center) {
                    if (app?.icon != null) Image(app.icon, null, Modifier.fillMaxSize())
                    else Text(if (app != null) app.label.take(1) else "+", color = Color.White,
                        fontSize = 3.8.sp, lineHeight = 4.4.sp, letterSpacing = 0.sp)
                }
                Spacer(Modifier.height(.7.dp))
                Text(label, color = Color.White.copy(alpha = .85f), fontSize = 1.7.sp, lineHeight = 2.1.sp, letterSpacing = 0.sp,
                    style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.offset((dockX - 26f).dp, 100.dp).width(52.dp).clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = .10f)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (16 until 20).forEach { index ->
                val app = home.appAt(index)
                Box(Modifier.size(9.25.dp).clip(RoundedCornerShape(2.5.dp))
                    .combinedClickable(enabled = enabled && home.loaded, onClickLabel = app?.let { "Öppna ${it.label}" } ?: "Välj app",
                        onLongClickLabel = "Byt app", onClick = { if (app != null) onOpen(index) else onEdit(index) },
                        onLongClick = { onEdit(index) }), contentAlignment = Alignment.Center) {
                    if (app?.icon != null) Image(app.icon, app.label, Modifier.fillMaxSize())
                    else Text(if (app != null) app.label.take(1) else "+", color = Color.White,
                        fontSize = 5.sp, lineHeight = 6.sp, letterSpacing = 0.sp)
                }
            }
        }
    }
}

private fun Modifier.foldReveal(sceneX: Float, progress: Float, outer: Boolean = false): Modifier {
    val appearance = DuoReveal.appearance(sceneX, progress, outer)
    if (appearance.blurUnits < .005f) return this
    // Scene-local density gives both physical panels approximately the same blur size.
    // Unbounded edges avoid a hard rectangle around the resolving surface.
    return blur(appearance.blurUnits.dp, BlurredEdgeTreatment.Unbounded)
        .graphicsLayer { alpha = appearance.opacity }
}

/** Real local date and month; no invented events, weather or media playback. */
@Composable
private fun CalendarSurface(now: Long, modifier: Modifier = Modifier) {
    val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val locale = Locale("sv", "SE")
    val firstWeekday = date.withDayOfMonth(1).dayOfWeek.value - 1
    val rows = (firstWeekday + date.lengthOfMonth() + 6) / 7
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFBCE1D6)).padding(5.dp)) {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE", locale)), color = Color(0xFF315C57),
                fontSize = 3.sp, lineHeight = 4.sp)
            Text(date.format(DateTimeFormatter.ofPattern("d MMMM", locale)), color = Color(0xFF133C38),
                fontSize = 6.sp, lineHeight = 8.sp)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFF0F2E9)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(date.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)), color = Color(0xFF243F3D),
                fontSize = 3.5.sp, lineHeight = 5.sp)
            Row(Modifier.fillMaxWidth()) {
                listOf("M", "T", "O", "T", "F", "L", "S").forEach { day ->
                    Box(Modifier.weight(1f).height(5.dp), contentAlignment = Alignment.Center) {
                        Text(day, color = Color(0xFF677A73), fontSize = 2.sp, lineHeight = 3.sp)
                    }
                }
            }
            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val day = row * 7 + column - firstWeekday + 1
                        val today = day == date.dayOfMonth
                        Box(Modifier.weight(1f).height(6.dp), contentAlignment = Alignment.Center) {
                            if (day in 1..date.lengthOfMonth()) {
                                Box(Modifier.size(6.dp).background(if (today) Color(0xFF24675D) else Color.Transparent,
                                    RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                                    Text(day.toString(), color = if (today) Color.White else Color(0xFF243F3D),
                                        fontSize = 2.5.sp, lineHeight = 3.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
