package dev.breaker.dictation.overlay

/**
 * The named factors and opacities of the close target box. Every number of its look is named here, so that
 * no colour or opacity is written inside a face or a view.
 */
internal object CloseTargetLook {

    /** The box fill is the dark surface moved toward white by this factor (#161B1E becomes #2A2E31). */
    const val FILL_LIGHTEN: Float = 0.085f

    /** The truck is the dark text moved toward black by this factor (#F2F5F2 becomes #E6E9E6). */
    const val TRUCK_SHADE: Float = -0.05f

    /**
     * The active load is the dark danger colour moved toward white by this factor (#E74C3C becomes #E9594A).
     * Lightening cannot reach the art value #FF5A47, so this is the nearest step of 0.01.
     */
    const val LOAD_ACTIVE_LIGHTEN: Float = 0.07f

    /** The fill opacity while the tile is not over the target. */
    const val FILL_OPACITY_IDLE: Float = 0.68f

    /** The fill opacity while the tile is over the target. */
    const val FILL_OPACITY_ACTIVE: Float = 0.82f

    /** The alpha byte of [opacity]: 0.68 gives 173, 0.82 gives 209, and the result is held to 0 to 255. */
    fun alphaOf(opacity: Float): Int = (opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
}
