package dev.foldprobe.animation

/** Bounded inverse projection for a cover-only experiment, not an inferred Apple camera model. */
object CoverPerspective {
    data class Projection(val compression: Float, val skew: Float) {
        // Normalized cover coordinates. The hinge-side vertical edge stays fixed.
        fun sample(u: Float, v: Float): Pair<Float, Float> {
            val denominator = 1f + skew * u
            return u * (1f - compression) / denominator to
                .5f + (v - .5f) / denominator
        }
    }

    fun projection(progress: Float, enabled: Boolean, outer: Boolean): Projection {
        if (!enabled || !outer) return Projection(0f, 0f)
        val degrees = DuoLayout.bounded(progress) * 180f
        // Identity while closed/touchable; fade out before the cover faces away.
        // A bounded approximation avoids the singularity of inverse cos(angle) at 90°.
        val strength = DuoLayout.eased((degrees - 5f) / 65f) *
            (1f - DuoLayout.eased((degrees - 90f) / 75f))
        return Projection(.12f * strength, .08f * strength)
    }
}
