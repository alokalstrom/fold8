package dev.foldprobe.animation

/** Spatial sharp/blur mix. Parameters approximate the video; they are not iOS measurements. */
object RevealSweep {
    const val FEATHER = .18f
    const val INNER_EDGE = DuoLayout.COVER_LEFT / DuoLayout.WIDTH

    data class Mask(val low: Float, val high: Float, val outer: Boolean) {
        fun weight(sceneX: Float): Float {
            if (!sceneX.isFinite()) return 0f
            val ramp = DuoLayout.eased((sceneX - low) / (high - low))
            return if (outer) ramp else (1f - ramp) *
                (1f - DuoLayout.eased((sceneX - (INNER_EDGE - .04f)) / .04f))
        }
    }

    fun mask(progress: Float, outer: Boolean): Mask {
        val p = DuoLayout.bounded(progress)
        val travel = if (outer) DuoLayout.bounded(p / .60f)
            else DuoLayout.bounded((p - .20f) / .75f)
        val front = 1f + FEATHER - travel * (1f + 2f * FEATHER)
        val start = if (outer) INNER_EDGE else 0f
        val span = if (outer) 1f - INNER_EDGE else INNER_EDGE
        return Mask(start + (front - FEATHER) * span,
            start + (front + FEATHER) * span, outer)
    }

    fun isSharp(progress: Float, outer: Boolean): Boolean {
        val p = DuoLayout.bounded(progress)
        return if (outer) p == 0f else p >= .95f
    }
}
