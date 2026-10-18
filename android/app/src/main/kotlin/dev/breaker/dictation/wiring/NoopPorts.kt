package dev.breaker.dictation.wiring

/** Starts a take from a gesture of the phone. */
interface GesturePort {
    /** Calls [trigger] each time the gesture happens, until [stop]. */
    fun start(trigger: () -> Unit)

    /** Stops listening for the gesture. Safe to call when nothing was started. */
    fun stop()
}

/** The gesture slot while no gesture exists: it never calls the trigger, so no take starts from it. */
class NoGesture : GesturePort {
    override fun start(trigger: () -> Unit) {
        // There is no gesture to wait for.
    }

    override fun stop() {
        // Nothing was started.
    }
}


