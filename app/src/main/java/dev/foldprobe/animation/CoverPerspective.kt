package dev.foldprobe.animation

import kotlin.math.cos
import kotlin.math.sin

/** Rays from an assumed viewer through the rotating cover into a fixed image plane. */
object CoverPerspective {
    const val VIEW_DISTANCE = 400f // Same approximate millimetre units as DuoLayout.
    const val VERTICAL_ANCHOR = 1f // Bottom: rows above it all slope down toward the near edge.
    data class Projection(val compression: Float, val skew: Float) {
        // Normalized cover coordinates; raw rays can land outside the finite source image.
        // GPU renders rays above the image black; other edges clamp. Hinge edge stays fixed.
        fun sample(u: Float, v: Float): Pair<Float, Float> {
            val denominator = 1f + skew * u
            return u * (1f - compression) / denominator to
                VERTICAL_ANCHOR + (v - VERTICAL_ANCHOR) / denominator
        }
    }

    fun projection(progress: Float, enabled: Boolean, outer: Boolean): Projection {
        if (!enabled || !outer) return Projection(0f, 0f)
        val degrees = DuoLayout.bounded(progress) * 180f
        // Preserve hit alignment through 5°, then reach the measured angle at 10°.
        val activeAngle = degrees * DuoLayout.eased((degrees - 5f) / 5f)
        // Exact angle through 65°, then smoothly cap at 75° to avoid image foldover.
        val tail = (activeAngle - 65f).coerceIn(0f, 20f)
        val angle = if (activeAngle <= 65f) activeAngle else 65f + tail - tail * tail / 40f
        val radians = Math.toRadians(angle.toDouble())
        val depthRatio = DuoLayout.COVER_WIDTH / VIEW_DISTANCE * sin(radians).toFloat()
        // Viewer E=(W/2,H,D); cover P=(u W cos(a),v H,u W sin(a)).
        // Intersect E+t(P-E) with z=0: x/W=u(cos(a)-depthRatio/2)/(1-u depthRatio),
        // y/H=1+(v-1)/(1-u depthRatio). This vertical viewpoint is a reference-fit choice.
        val visible = 1f - DuoLayout.eased((degrees - 90f) / 75f)
        return Projection((1f - cos(radians).toFloat() + depthRatio / 2f) * visible,
            -depthRatio * visible)
    }
}
