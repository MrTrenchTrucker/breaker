package dev.breaker.dictation.service

/** What the service does when a start request arrives. */
enum class StartDecision {
    /** Stay on. */
    KEEP,

    /** Mark the switch as on (it was started from the notification), then stay on. */
    ADOPT_AND_KEEP,

    /** Leave the foreground and stop at once. */
    STOP_AT_ONCE,
}

/** The rule for a start request, as a pure function so every row is a plain test. */
object ServiceStartDecisions {

    /**
     * Switch-on while on: keep. Switch-on while off: adopt and keep. Switch-off: stop at once, on or off.
     * Anything else: keep when on; stop at once when off, so a service restarted with nothing on ends.
     */
    fun decide(action: ServiceAction, armed: Boolean): StartDecision = when (action) {
        ServiceAction.ARM -> if (armed) StartDecision.KEEP else StartDecision.ADOPT_AND_KEEP
        ServiceAction.DISARM -> StartDecision.STOP_AT_ONCE
        ServiceAction.UNKNOWN -> if (armed) StartDecision.KEEP else StartDecision.STOP_AT_ONCE
    }
}
