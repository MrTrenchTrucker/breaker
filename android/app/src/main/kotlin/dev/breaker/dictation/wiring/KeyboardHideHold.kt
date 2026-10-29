package dev.breaker.dictation.wiring

/**
 * Keeps a keyboard-gone hide that arrives while a take is in flight, and lets it through once, when the
 * take has left flight. The kept hide is applied only when the last raw keyboard reading still says the
 * keyboard is gone; a keyboard show clears it, and so does a switch-off. The tile stays up while it is
 * held. Plain Kotlin: it names no Android type.
 */
internal class KeyboardHideHold {
    private var held: Boolean = false
    private var keyboardGone: Boolean = false

    /** Records every raw keyboard reading, before the session decides on it. */
    fun onReading(ime: Boolean) {
        keyboardGone = !ime
    }

    /** True when a keyboard-gone hide may take the tile down now; false when it is held for the take in flight. */
    fun hideNow(inFlight: Boolean): Boolean {
        if (!inFlight) return true
        held = true
        return false
    }

    /** A keyboard show clears the held hide. */
    fun clearOnShow() {
        held = false
    }

    /** A user close drops the held hide, so nothing automatic takes the closed tile down later. */
    fun clearOnClose() {
        held = false
    }

    /** A switch-off clears the held hide; true when there was one to clear. */
    fun clearOnSwitchOff(): Boolean {
        val was: Boolean = held
        held = false
        return was
    }

    /** Called on every push of the tile state: true when the held hide comes down now. Nothing is applied while a take is in flight. */
    fun applyWhenIdle(inFlight: Boolean): Boolean {
        if (inFlight) return false
        val apply: Boolean = held && keyboardGone
        held = false
        return apply
    }
}
