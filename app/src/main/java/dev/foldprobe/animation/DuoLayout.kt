package dev.foldprobe.animation

data class ElementTransform(val x: Float, val y: Float, val scale: Float, val rotationY: Float)

/** Stateless geometry: reversing progress retraces exactly the same element path. */
object DuoLayout {
    // Rounded physical panel dimensions derived from this phone's pixel/DPI reports.
    // Shared millimetre-like scene units; not a metrology calibration.
    const val WIDTH = 154f
    const val HEIGHT = 116f
    const val COVER_WIDTH = 74f
    const val COVER_LEFT = WIDTH - COVER_WIDTH
    fun bounded(p: Float) = if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
    fun eased(progress: Float): Float {
        val p = bounded(progress)
        return p * p * (3 - 2 * p)
    }
    fun blend(a: Float, b: Float, p: Float): Float = a + (b - a) * bounded(p)
    @Suppress("UNUSED_PARAMETER") // Geometry is shared and stable; progress controls the panel reveal.
    fun icon(index: Int, progress: Float, outer: Boolean): ElementTransform {
        require(index in 0..15)
        // The original 4x4 group stays on the right as the new left surface appears.
        val x = (COVER_LEFT + COVER_WIDTH * (.125f + (index % 4) * .25f)) / WIDTH
        return ElementTransform(if (outer) (x * WIDTH - COVER_LEFT) / COVER_WIDTH else x,
            .34f + (index / 4) * .105f, 1f, 0f)
    }

    fun viewport(widthPx: Float, heightPx: Float, outer: Boolean): SceneViewport {
        val panelWidth = if (outer) COVER_WIDTH else WIDTH
        val scale = minOf(widthPx / panelWidth, heightPx / HEIGHT).coerceAtLeast(.001f)
        return SceneViewport(scale, (widthPx - panelWidth * scale) / 2f -
            (if (outer) COVER_LEFT * scale else 0f), (heightPx - HEIGHT * scale) / 2f)
    }
}

data class SceneViewport(val scale: Float, val left: Float, val top: Float)
