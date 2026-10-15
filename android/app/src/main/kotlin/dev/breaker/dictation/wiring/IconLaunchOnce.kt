package dev.breaker.dictation.wiring

/**
 * Decides whether an icon launch of the launcher activity switches dictation on.
 *
 * An icon launch switches dictation on once for each activity instance the icon created. The activity
 * keeps the icon's start intent, so the same start comes back each time the activity becomes visible
 * again; the flag stops that repeat from switching on over a switch-off the user chose. Only an answer
 * of [SwitchOn.On] or [SwitchOn.AlreadyOn] counts as done. A refusal, such as a missing microphone
 * permission, leaves the rule open, so the return from the system settings still switches on.
 *
 * Every other start, and every start after the rule is done, only arms the service when the user has
 * not switched it off. The activity saves [isDone] and passes it back as [restoredDone] when it is
 * recreated, so a rotation does not fire the rule again.
 */
internal class IconLaunchOnce(restoredDone: Boolean) {

    /** True once an icon launch has switched dictation on, or found it on already, for this instance. */
    var isDone: Boolean = restoredDone
        private set

    /**
     * Called each time the activity becomes visible. [isIconLaunch] says whether the launcher icon
     * started it, [switchOn] makes the switch-on request and [armAtStart] makes the start-up request.
     * A refused switch-on is handed to [onRefused] with its sentence; the rule stays open, and a failure
     * in [onRefused] does not escape this call.
     */
    fun onStart(
        isIconLaunch: Boolean,
        switchOn: () -> SwitchOn,
        armAtStart: () -> Unit,
        onRefused: (String) -> Unit = {},
    ) {
        if (!isIconLaunch || isDone) {
            armAtStart()
            return
        }
        when (val answer = switchOn()) {
            SwitchOn.On, SwitchOn.AlreadyOn -> isDone = true
            is SwitchOn.Refused -> refused(answer.sentence, onRefused)
        }
    }

    /** Hands the refusal to [onRefused]. A failure there is skipped so the start goes on. */
    private fun refused(sentence: String, onRefused: (String) -> Unit) {
        try {
            onRefused(sentence)
        } catch (failure: Exception) {
        }
    }
}
