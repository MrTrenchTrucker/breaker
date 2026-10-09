package dev.breaker.dictation.service

/**
 * The few things the service does to itself, one platform call each, so the order of the start
 * handling can be run and tested without the platform.
 */
interface ServiceHost {
    /**
     * Puts the service in the foreground with its notification and the microphone type.
     * Throws a [RuntimeException] when the platform or the notification refuses.
     */
    fun enterForeground()

    /** Removes the notification and ends the service. */
    fun leaveForegroundAndStop()

    /** Ends the service only; used when it never reached the foreground. */
    fun stopWithoutForeground()
}

/**
 * What the service does with each start request.
 *
 * The first thing done is [ServiceHost.enterForeground], on every path, even when the service is
 * about to stop again: the platform wants a service that was started as a foreground service to
 * reach the foreground within a few seconds, and ending without that can bring the whole app down.
 * If the foreground is refused, the switch is marked off, the service ends and nothing is thrown.
 * Only then is the request read: [ServiceStartDecisions.decide] says whether to stay on or stop at once.
 */
class ServiceStartHandler(
    private val controller: DictationServiceController,
    private val host: ServiceHost,
) {

    /** Handles one start request; [actionText] is the action of the start intent, or null. */
    fun onStart(actionText: String?) {
        try {
            host.enterForeground()
        } catch (e: RuntimeException) {
            controller.serviceEnded()
            host.stopWithoutForeground()
            return
        }
        val action = serviceActionOf(actionText)
        when (ServiceStartDecisions.decide(action, controller.isArmed)) {
            StartDecision.KEEP -> Unit
            StartDecision.ADOPT_AND_KEEP ->
                if (controller.adopt() is StartResult.NotStarted) host.leaveForegroundAndStop()
            StartDecision.STOP_AT_ONCE -> {
                if (action == ServiceAction.DISARM && controller.isArmed) {
                    controller.disarm(DisarmReason.USER_WORD)
                }
                host.leaveForegroundAndStop()
            }
        }
    }
}
