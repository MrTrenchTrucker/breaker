package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.BreakerSwitch
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent

/*
 * The set-up screen's memory, and the one place a tap is acted on.
 *
 * The screen is pure: it describes a tree from a state and knows nothing of the
 * phone. This class is what connects it to the phone and to the switch. It asks the
 * phone and the switch afresh each time it draws, so a permission the user changed
 * in the phone's settings, or a switch turned off elsewhere, shows as it now is.
 *
 * Nothing is stored. The handler remembers, for as long as it lives, whether it has
 * already asked for the microphone or for notifications, and the last answers it
 * got, so that a failed read can fall back on them.
 */

/** What the phone is taken to say before it has said anything: nothing granted, version unknown. */
private val NOTHING_KNOWN = PlatformStatus(
    overlay = false,
    microphone = false,
    notifications = false,
    accessibility = false,
    sdkInt = 0,
)

/**
 * Acts on the intents the set-up screen reports and says what to draw next.
 *
 * Every call to the phone or to the switch is guarded. A call that throws is never
 * allowed out, and the text of what it threw is never shown: the user gets a fixed
 * sentence from [SetupNotice]. A read that fails keeps the last answers that did
 * arrive (or nothing granted and the switch off, if none ever did) and adds a
 * notice that the screen may be out of date.
 *
 * A notice is drawn once. The next drawing has none unless something fails again.
 * When an action fails and a read fails in the same drawing, the notice for the
 * action is the one shown; the next drawing shows the other.
 *
 * Call it from the main thread only; it does no locking.
 *
 * @param platform the phone, which answers what is granted and opens settings.
 * @param switch the app's switch that turns Breaker on and off.
 * @param screen the pure screen.
 */
internal class SetupIntentHandler(
    private val platform: SetupPlatform,
    private val switch: BreakerSwitch,
    private val screen: SetupScreen,
) {
    private var lastStatus: PlatformStatus? = null
    private var lastSwitchOn: Boolean = false
    private var micAsked: Boolean = false
    private var notificationsAsked: Boolean = false
    private var held: SetupState? = null

    /** The screen as the phone and the switch say it is right now. */
    fun current(): Screen = show(notice = null, fresh = true)

    /**
     * Acts on [intent] and returns the screen as it stands afterwards.
     *
     * The `when` names every intent the sealed hierarchy has, so an intent added
     * later is a compile error here rather than a tap that does nothing. An intent
     * this screen does not own, and a set-up action it does not know, are refused
     * and the screen is drawn again from what it already held, without asking the
     * phone or the switch anything. Before anything was drawn there is nothing to
     * hold, so the first drawing asks them once.
     */
    fun handle(intent: ScreenIntent): Screen {
        val notice = when (intent) {
            is ScreenIntent.Setup -> act(intent.action)
            ScreenIntent.ToggleTheme,
            ScreenIntent.UseSystemTheme,
            is ScreenIntent.SetRoutingMode,
            is ScreenIntent.SetSetting,
            -> SetupNotice.NOT_ACCEPTED
        }
        return show(notice = notice, fresh = notice != SetupNotice.NOT_ACCEPTED)
    }

    /** Does what the action string asks and returns the notice it leaves, or null. */
    private fun act(action: String): SetupNotice? {
        if (action == SetupActions.SWITCH_ON) return switchOn()
        if (action == SetupActions.SWITCH_OFF) return switchOff()
        if (action == SetupActions.RECHECK) return null
        if (action in SetupActions.OPENING) return open(action)
        return SetupNotice.NOT_ACCEPTED
    }

    /**
     * Asks the phone to open what the button the user tapped stood for.
     *
     * The target is worked out from the state that was on screen, not from a new
     * read, so the tap does what its button said. A prompt or settings page that
     * opened is remembered only when it was a prompt, because only a prompt is not
     * shown a second time.
     */
    private fun open(action: String): SetupNotice? {
        val target = SetupActions.openActionFor(action, held ?: build()) ?: return null
        val result = try {
            platform.open(target)
        } catch (failure: Exception) {
            return SetupNotice.COULD_NOT_OPEN
        }
        return when (result) {
            OpenResult.OPENED -> {
                remember(target)
                null
            }
            OpenResult.NOT_POSSIBLE -> SetupNotice.COULD_NOT_OPEN
        }
    }

    /** Notes that a prompt was shown, so the next button for it opens a settings page instead. */
    private fun remember(target: OpenAction) {
        when (target) {
            OpenAction.REQUEST_MICROPHONE -> micAsked = true
            OpenAction.REQUEST_NOTIFICATIONS -> notificationsAsked = true
            OpenAction.OVERLAY_PAGE,
            OpenAction.NOTIFICATION_PAGE,
            OpenAction.ACCESSIBILITY_LIST,
            OpenAction.APP_INFO,
            -> Unit
        }
    }

    /** Asks the switch to turn Breaker on. A refusal is shown in the app's own words. */
    private fun switchOn(): SetupNotice? {
        val result = try {
            switch.switchOn()
        } catch (failure: Exception) {
            return SetupNotice.SWITCH_FAILED
        }
        return when (result) {
            BreakerSwitch.Result.On, BreakerSwitch.Result.AlreadyOn -> null
            is BreakerSwitch.Result.Refused ->
                if (result.sentence.isBlank()) SetupNotice.SWITCH_FAILED else SetupNotice.Refused(result.sentence)
        }
    }

    /** Asks the switch to turn Breaker off. Already being off is not a failure. */
    private fun switchOff(): SetupNotice? {
        try {
            switch.switchOff()
        } catch (failure: Exception) {
            return SetupNotice.SWITCH_FAILED
        }
        return null
    }

    /**
     * Draws the screen with [notice].
     *
     * With [fresh] the phone and the switch are asked again. Without it, the state
     * drawn last is drawn again; if nothing was drawn yet, they are asked as for a
     * fresh drawing, and a read that fails leaves nothing granted and the switch off.
     */
    private fun show(notice: SetupNotice?, fresh: Boolean): Screen {
        val kept = if (fresh) null else held
        val readFailed = kept == null && readSeams()
        val state = kept ?: build().also { held = it }
        val shown = notice ?: if (readFailed) SetupNotice.COULD_NOT_CHECK else null
        return screen.render(state, shown)
    }

    /** Asks the phone and the switch, keeping the last answers for any that fail. Returns true if one failed. */
    private fun readSeams(): Boolean {
        var failed = false
        try {
            lastStatus = platform.status()
        } catch (failure: Exception) {
            failed = true
        }
        try {
            lastSwitchOn = switch.isOn()
        } catch (failure: Exception) {
            failed = true
        }
        return failed
    }

    /** The state from the last answers and the memory of what was asked. */
    private fun build(): SetupState =
        SetupState(
            status = lastStatus ?: NOTHING_KNOWN,
            switchedOn = lastSwitchOn,
            micAskedBefore = micAsked,
            notificationsAskedBefore = notificationsAsked,
        )
}
