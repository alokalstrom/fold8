package dev.foldprobe.animation

data class RevealAppearance(val blurUnits: Float = 0f, val opacity: Float = 1f)

/** Panel-specific approximations of the filmed reveal, not measured iOS effect curves. */
object DuoReveal {
    fun appearance(sceneX: Float, progress: Float, outer: Boolean = false): RevealAppearance {
        val p = DuoLayout.bounded(progress)
        if (!sceneX.isFinite()) return RevealAppearance()
        if (outer) {
            // Outgoing cover softens; closing retraces this and resolves to sharp at zero.
            return RevealAppearance(blurUnits = 2.2f * DuoLayout.eased(p / .45f))
        }
        // Keep the existing right group sharp while the incoming left surface resolves.
        val leftMask = 1f - DuoLayout.eased((sceneX - .42f) / .10f)
        val unresolved = 1f - DuoLayout.eased((p - .20f) / .75f)
        return RevealAppearance(blurUnits = 3f * leftMask * unresolved)
    }
}
