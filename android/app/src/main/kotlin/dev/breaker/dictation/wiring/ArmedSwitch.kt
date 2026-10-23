package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.DisarmReason
import dev.breaker.dictation.service.StartResult
import java.util.concurrent.CopyOnWriteArrayList

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
 *
 * [onStateChanged] also tells the listeners added with [addSwitchListener], after the store has
 * settled, so a face that reads the switch is re-pushed on the arm and on the disarm.
 */
class ArmedSwitch(
    private val controller: DictationServiceController,
    private val store: OffStore,
) {
    /** The listeners told the settled armed state after each change the controller really made. */
    private val switchListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

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
     * The stored-off check the Home take shares with [armAtStart]: the very same check, exposed so a
     * tap on Home answers the off without turning the switch on. A store that cannot be read counts
     * as not off, exactly as the launcher's start-up rule does.
     */
    fun isUserSwitchedOff(): Boolean = userSwitchedOff()

    /**
     * The controller's state observer. Turning on clears the off choice; turning off because the user
     * said so ([DisarmReason.USER_WORD]) stores it; any other change leaves it as it was. A store that
     * fails is ignored and the state is not touched.
     *
     * The switch listeners are told the settled [armed] state once the store is done, so a face that
     * reads the switch is re-pushed on the arm and on the disarm, never before the store is settled.
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
        notifySwitchListeners(armed)
    }

    /**
     * Adds [listener], told the settled armed state after each change the controller really made:
     * an arm and every disarm, on the thread that made the change. A listener may be added more than
     * once and is told once for each time it was added.
     */
    fun addSwitchListener(listener: (Boolean) -> Unit) {
        switchListeners.add(listener)
    }

    /** Removes one [listener] registration; a listener that was never added is ignored. */
    fun removeSwitchListener(listener: (Boolean) -> Unit) {
        switchListeners.remove(listener)
    }

    /** Tells each listener the settled state; one that throws cannot stop the others. */
    private fun notifySwitchListeners(armed: Boolean) {
        for (listener in switchListeners) {
            try {
                listener(armed)
            } catch (e: Exception) {
                // The state is settled already; a failing listener cannot be handled here.
            }
        }
    }

    private fun userSwitchedOff(): Boolean =
        try {
            store.isOff()
        } catch (e: Exception) {
            false
        }
}
