package dev.breaker.dictation.overlay

/**
 * Remembers the last (bottom, visible) pair that was accepted, so that the inset probe reports a
 * change and not every dispatch of the window insets. A pair is accepted the first time, and again
 * only when it differs from the last accepted pair. [forget] makes the next pair count as the first.
 *
 * It holds no platform type, so a plain JVM test can fire it directly.
 */
internal class InsetChangeFilter {

    private var hasLast: Boolean = false
    private var lastBottom: Int = 0
    private var lastVisible: Boolean = false

    /** True when this pair is the first since the start or the last [forget], or differs from the last accepted pair. */
    fun accept(bottom: Int, visible: Boolean): Boolean {
        val changed = !hasLast || bottom != lastBottom || visible != lastVisible
        if (changed) {
            hasLast = true
            lastBottom = bottom
            lastVisible = visible
        }
        return changed
    }

    /** Make the next pair count as the first one, so it is accepted whatever it is. */
    fun forget() {
        hasLast = false
    }
}
