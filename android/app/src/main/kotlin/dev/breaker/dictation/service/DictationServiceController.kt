package dev.breaker.dictation.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the one answer to "is the microphone service switched on" and starts and stops the service.
 *
 * Several threads can reach it (the main thread and the capture thread), so the flag is an
 * [AtomicBoolean] and every change is a compare-and-set: two callers cannot both launch the service,
 * and a switch-off halts the service once. The flag is set before the launch, so a second
 * concurrent caller is answered [StartResult.AlreadyRunning] while the first launch is still undecided.
 *
 * It never throws, logs nothing and holds no text of the user. A permission check or a launcher that
 * throws an [Exception] is treated as "no" and "refused".
 */
class DictationServiceController(
    private val permission: MicPermission,
    private val launcher: ServiceLauncher,
) {
    private val armed = AtomicBoolean(false)
    private val endedListener = AtomicReference<(() -> Unit)?>(null)
    private val stateObserver = AtomicReference<((armed: Boolean, reason: DisarmReason?) -> Unit)?>(null)

    /** True while the service is switched on. */
    val isArmed: Boolean
        get() = armed.get()

    /**
     * Sets the one listener that [serviceEnded] calls, or removes it with null. A second call replaces the
     * first listener.
     */
    fun setEndedListener(listener: (() -> Unit)?) {
        endedListener.set(listener)
    }

    /**
     * Sets the one observer told when the switch really changes, or removes it with null. A second call
     * replaces the first observer.
     *
     * It is called with `true` and no reason when the switch turns on ([arm], [coldStart], [adopt]), with
     * `false` and the [DisarmReason] when [disarm] turns it off, and with `false` and no reason when
     * [serviceEnded] turns it off. It is not called when the call changes nothing (already on, already
     * off, a refused launch). It runs on the thread that made the change, after the change; an observer
     * that throws an [Exception] is swallowed.
     */
    fun setStateObserver(observer: ((armed: Boolean, reason: DisarmReason?) -> Unit)?) {
        stateObserver.set(observer)
    }

    /**
     * Switches the service on while the app is visible.
     *
     * Already on: answers [StartResult.AlreadyRunning] and asks nothing. No microphone permission:
     * answers the missing-permission sentence and does not launch. Otherwise launches once; a refusal
     * (or a launcher that throws) turns the switch back off and answers the arm-refused sentence. If the
     * switch was turned off while the launch was under way (a [disarm] or [serviceEnded] landed), the
     * launcher is halted once more and the arm-refused sentence is answered.
     */
    fun arm(): StartResult = start(ServiceSentences.ARM_REFUSED)

    /**
     * The tile was tapped while the service was off: tries to switch it on once.
     *
     * Same as [arm], but a refused launch answers the "open Breaker once" sentence.
     */
    fun coldStart(): StartResult = start(ServiceSentences.COLD_START_REFUSED)

    /**
     * The service itself was started by a switch-on it did not launch (from its notification, in a
     * fresh process): marks it on without launching anything.
     *
     * Already on: [StartResult.AlreadyRunning]. No microphone permission: the missing-permission sentence.
     */
    fun adopt(): StartResult {
        if (armed.get()) return StartResult.AlreadyRunning
        if (!permissionGranted()) return StartResult.NotStarted(ServiceSentences.MIC_PERMISSION_MISSING)
        if (!armed.compareAndSet(false, true)) return StartResult.AlreadyRunning
        notifyObserver(true, null)
        return StartResult.Started
    }

    /**
     * Switches the service off. If it was on, it is marked off and the launcher is halted exactly once;
     * if it was off, nothing happens. A halt that throws is swallowed after the mark is cleared.
     *
     * [reason] is not stored or logged; it is handed to the state observer, once, when the switch really
     * went from on to off.
     */
    fun disarm(reason: DisarmReason) {
        if (!armed.compareAndSet(true, false)) return
        haltQuietly()
        notifyObserver(false, reason)
    }

    /**
     * The service is gone (killed, or it stopped itself): marks it off without halting anything, and then
     * calls the ended listener once (also when the switch was already off, so the owner can drop any
     * capture). A listener that throws an [Exception] is swallowed. The state observer is told first, and
     * only when the switch was on.
     */
    fun serviceEnded() {
        if (armed.getAndSet(false)) notifyObserver(false, null)
        val listener = endedListener.get() ?: return
        try {
            listener()
        } catch (e: Exception) {
            // The switch is off already; a failing listener cannot be handled here.
        }
    }

    private fun start(refusedSentence: String): StartResult {
        if (armed.get()) return StartResult.AlreadyRunning
        if (!permissionGranted()) return StartResult.NotStarted(ServiceSentences.MIC_PERMISSION_MISSING)
        if (!armed.compareAndSet(false, true)) return StartResult.AlreadyRunning
        if (!launchSucceeded()) {
            armed.compareAndSet(true, false)
            return StartResult.NotStarted(refusedSentence)
        }
        if (armed.get()) {
            notifyObserver(true, null)
            return StartResult.Started
        }
        // Switched off while the launch was under way: the service may be running now, so stop it again.
        haltQuietly()
        return StartResult.NotStarted(refusedSentence)
    }

    private fun notifyObserver(now: Boolean, reason: DisarmReason?) {
        val observer = stateObserver.get() ?: return
        try {
            observer(now, reason)
        } catch (e: Exception) {
            // The switch has changed already; a failing observer cannot be handled here.
        }
    }

    private fun haltQuietly() {
        try {
            launcher.halt()
        } catch (e: Exception) {
            // The switch is off already; a failed halt cannot be retried here.
        }
    }

    private fun permissionGranted(): Boolean =
        try {
            permission.isRecordAudioGranted()
        } catch (e: Exception) {
            false
        }

    private fun launchSucceeded(): Boolean =
        try {
            launcher.launch() == LaunchResult.Launched
        } catch (e: Exception) {
            false
        }
}
