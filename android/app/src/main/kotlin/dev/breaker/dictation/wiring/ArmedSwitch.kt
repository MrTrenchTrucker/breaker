package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.DisarmReason
import dev.breaker.dictation.service.StartResult

/** The answer to a request to switch dictation on. */
sealed class SwitchOn {
    /** Dictation is on because of this request. */
    object On : SwitchOn()

    /** Dictation was already on; nothing was started. */
    object AlreadyOn : SwitchOn()

    /** Dictation is not on; [sentence] says why in plain words. */
    data class Refused(val sentence: String) : SwitchOn()
}

/**
 * The one on/off switch for dictation, over the microphone service controller.
 *
 * "On" is the controller's armed state. The user's off choice is remembered in an [OffStore]:
 * it is written only when the controller goes off because the user said so
 * ([DisarmReason.USER_WORD]), and cleared whenever dictation goes on. The service ending by itself
 * ([DictationServiceController.serviceEnded]) and the owner closing ([DisarmReason.OWNER_CLOSED])
 * never write it.
 *
 * The store is updated by [onStateChanged], which the app registers as the controller's state
 * observer, so every path that switches the controller (this switch, the notification's off
 * action, the runner) is seen in one place. Nothing here throws: a store that fails leaves the
 * switch itself working.
 */
class ArmedSwitch(
    private val controller: DictationServiceController,
    private val store: OffStore,
) {
    /** True while dictation is on. */
    fun isOn(): Boolean = controller.isArmed

    /** Switches dictation on. */
    fun switchOn(): SwitchOn =
        when (val answer = controller.arm()) {
            StartResult.Started -> SwitchOn.On
            StartResult.AlreadyRunning -> SwitchOn.AlreadyOn
            is StartResult.NotStarted -> SwitchOn.Refused(answer.sentence)
        }

    /**
     * Switches dictation off because the user said so. The choice is stored also when the controller
     * was already off (for example when switching on was refused), so the next start does not arm.
     */
    fun switchOff() {
        controller.disarm(DisarmReason.USER_WORD)
        if (!controller.isArmed) onStateChanged(false, DisarmReason.USER_WORD)
    }

    /**
     * What the app does each time the launcher becomes visible: switches dictation on unless the user
     * switched it off earlier. Answers null when it did not try.
     */
    fun armAtStart(): StartResult? = if (userSwitchedOff()) null else controller.arm()

    /**
     * The controller's state observer. Turning on clears the off choice; turning off because the user
     * said so ([DisarmReason.USER_WORD]) stores it; any other change leaves it as it was. A store that
     * fails is ignored and the state is not touched.
     */
    fun onStateChanged(armed: Boolean, reason: DisarmReason?) {
        try {
            if (armed) {
                store.setOff(false)
            } else if (reason == DisarmReason.USER_WORD) {
                store.setOff(true)
            }
        } catch (e: Exception) {
            // The choice could not be written; the switch itself is unchanged.
        }
    }

    private fun userSwitchedOff(): Boolean =
        try {
            store.isOff()
        } catch (e: Exception) {
            false
        }
}
