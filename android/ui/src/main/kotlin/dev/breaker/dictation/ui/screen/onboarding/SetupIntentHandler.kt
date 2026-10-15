package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.ui.BreakerSwitch
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.write.writeThrough

/*
 * The set-up screen's memory, and the one place a tap is acted on.
 *
 * The screen is pure: it describes a tree from a state and knows nothing of the
 * phone. This class is what connects it to the phone and to the switch. It asks the
 * phone and the switch afresh each time it draws, so a permission the user changed
 * in the phone's settings, or a switch turned off elsewhere, shows as it now is.
 *
 * The handler stores nothing itself. It remembers, for as long as it lives, whether it
 * asked for notifications and the last answers it got, so that a failed read can fall
 * back on them. The microphone flag is kept in the settings store it is given, and in
 * memory too, so a tap still opens the app's page when the store cannot be written. It
 * also keeps, in memory only, a refusal the app reports for the switch, until a tap or a
 * read clears it.
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
 * A notice is drawn once, and the next drawing has none unless something fails again.
 * The one exception is the refusal the app reports through [switchRefused]: it is
 * pinned, and each drawing that is not a tap draws it again, until a tap or a read
 * that finds the switch on clears it. When an action fails and a read fails in the
 * same drawing, the notice for the action is the one shown; the pinned refusal wins
 * over a failed read; the next drawing shows the other.
 *
 * Call it from the main thread only; it does no locking.
 *
 * The speech model is read through three providers, [modelReady], [downloadLine] and
 * [modelDownloadMb], rather than being kept as a fact here. All are asked again on
 * every drawing, so a download that finishes while the screen is open is shown the
 * next time the screen is drawn, without this class having to be told.
 *
 * @param platform the phone, which answers what is granted and opens settings.
 * @param switch the app's switch that turns Breaker on and off.
 * @param modelReady answers whether the speech model is on the phone right now.
 * @param downloadLine answers the sentence to show under the model step's button,
 *   or null when the app has nothing to say.
 * @param modelDownloadMb answers the size in decimal megabytes the model step's
 *   source line shows; read again on every refresh.
 * @param onDownloadModel asks the app to download the speech model; the download
 *   itself, its progress and its sentences stay the app's.
 * @param screen the pure screen.
 * @param settings the store the microphone flag is kept in; null keeps it in memory only.
 */
internal class SetupIntentHandler(
    private val platform: SetupPlatform,
    private val switch: BreakerSwitch,
    private val modelReady: () -> Boolean,
    private val downloadLine: () -> String?,
    private val modelDownloadMb: () -> Int,
    private val onDownloadModel: () -> Unit,
    private val screen: SetupScreen,
    private val settings: SettingsStore? = null,
) {
    private var lastStatus: PlatformStatus? = null
    private var lastSwitchOn: Boolean = false
    private var lastModelReady: Boolean = false
    private var lastDownloadLine: String? = null
    private var lastModelDownloadMb: Int = 0
    private var micAsked: Boolean = false
    private var notificationsAsked: Boolean = false
    private var held: SetupState? = null

    /** A microphone prompt was handed to the system and its answer is not yet written; see [commitPendingAsk]. */
    private var micAskPending: Boolean = false

    /**
     * The refusal the app reported outside the walk-through, drawn again on each drawing
     * that is not a tap. Set only by [switchRefused]; cleared by any [handle] call once its
     * own drawing is made, and by a read that finds the switch on. Process memory only.
     */
    private var pinned: SetupNotice? = null

    /**
     * The screen as the phone and the switch say it is right now, with the pinned refusal if
     * there is one. A pending microphone answer is written first, so the first drawing after
     * the system prompt writes the flag.
     */
    fun current(): Screen {
        commitPendingAsk()
        return show(notice = null, fresh = true, redraw = true)
    }

    /**
     * The screen as it is drawn after a switch-on that the app refused outside this walk-through, with the
     * same notice a refused tap gets. The notice is pinned (see [pinned]).
     */
    fun switchRefused(sentence: String): Screen {
        val notice = refusalNotice(sentence)
        pinned = notice
        return show(notice = notice, fresh = true)
    }

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
            is ScreenIntent.SetThemeMode,
            is ScreenIntent.History,
            -> SetupNotice.NOT_ACCEPTED
        }
        val drawn = show(notice = notice, fresh = notice != SetupNotice.NOT_ACCEPTED)
        pinned = null
        return drawn
    }

    /** Does what the action string asks and returns the notice it leaves, or null. */
    private fun act(action: String): SetupNotice? {
        if (action == SetupActions.SWITCH_ON) return switchOn()
        if (action == SetupActions.SWITCH_OFF) return switchOff()
        if (action == SetupActions.RECHECK) return null
        if (action == SetupActions.DOWNLOAD_MODEL) return downloadModel()
        if (action in SetupActions.OPENING) return open(action)
        return SetupNotice.NOT_ACCEPTED
    }

    /**
     * Asks the app to download the speech model.
     *
     * The download itself is the app's; this only hands the tap over. The call is
     * guarded like the switch calls, so a failure shows as a plain sentence and is
     * never allowed out into the screen, and the text of what it threw is never
     * shown.
     */
    private fun downloadModel(): SetupNotice? {
        try {
            onDownloadModel()
        } catch (failure: Exception) {
            return SetupNotice.COULD_NOT_DOWNLOAD
        }
        return null
    }

    /**
     * Asks the phone to open what the tapped button stood for, resolved against the state on
     * screen, so the tap does what its button said. A prompt or settings page that opened is
     * remembered only when it was a prompt. A pending microphone answer is written first
     * ([commitPendingAsk]). The microphone string resolves through the memory flag that
     * [remember] set in the last drawing, so once the prompt was asked the tap opens the app's
     * page. In the one case where the system answers at once and no drawing follows, the first
     * tap does nothing visible and the next opens the page; that is reported, not hidden.
     */
    private fun open(action: String): SetupNotice? {
        commitPendingAsk()
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

    /**
     * Notes that a prompt was shown, so the next button for it opens a settings page instead.
     *
     * A microphone prompt is also marked pending; [commitPendingAsk] writes its answer later.
     */
    private fun remember(target: OpenAction) {
        when (target) {
            OpenAction.REQUEST_MICROPHONE -> {
                micAsked = true
                micAskPending = true
            }
            OpenAction.REQUEST_NOTIFICATIONS -> notificationsAsked = true
            OpenAction.OVERLAY_PAGE,
            OpenAction.NOTIFICATION_PAGE,
            OpenAction.ACCESSIBILITY_LIST,
            OpenAction.APP_INFO,
            -> Unit
        }
    }

    /**
     * Writes the microphone flag to the settings store once the system prompt has come back,
     * never when the prompt is handed over. Run at the start of [current] and [open]: if the
     * microphone is still not granted the flag is written, and if it is granted nothing is.
     * A failed phone read keeps the pending mark; a failed write keeps the memory flag and shows
     * no notice.
     */
    private fun commitPendingAsk() {
        if (!micAskPending) return
        val granted = try {
            platform.status().microphone
        } catch (failure: Exception) {
            return
        }
        micAskPending = false
        if (!granted && settings != null) writeThrough(settings) { it.copy(microphoneAsked = true) }
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
            is BreakerSwitch.Result.Refused -> refusalNotice(result.sentence)
        }
    }

    /** The notice for a refused switch-on: the app's sentence as given, or the fixed failure when it is blank. */
    private fun refusalNotice(sentence: String): SetupNotice =
        if (sentence.isBlank()) SetupNotice.SWITCH_FAILED else SetupNotice.Refused(sentence)

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
     *
     * [redraw] is true for a drawing that is not a tap: it draws the pinned refusal when
     * there is no notice of its own. A read that finds the switch on clears the pin first.
     */
    private fun show(notice: SetupNotice?, fresh: Boolean, redraw: Boolean = false): Screen {
        val kept = if (fresh) null else held
        val readFailed = kept == null && readSeams()
        val state = kept ?: build().also { held = it }
        if (kept == null && state.switchedOn) pinned = null
        val pinnedNow = if (redraw) pinned else null
        val shown = notice ?: pinnedNow ?: if (readFailed) SetupNotice.COULD_NOT_CHECK else null
        return screen.render(state, shown)
    }

    /**
     * Asks the phone, the switch and the model providers, keeping the last answers for any that
     * fail; returns true if one failed. The stored microphone flag only ever sets the memory flag,
     * and a failed read of it is not a failed seam.
     */
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
        try {
            lastModelReady = modelReady()
        } catch (failure: Exception) {
            failed = true
        }
        try {
            lastDownloadLine = downloadLine()
        } catch (failure: Exception) {
            failed = true
        }
        try {
            lastModelDownloadMb = modelDownloadMb()
        } catch (failure: Exception) {
            failed = true
        }
        try {
            if (settings != null && settings.load().microphoneAsked) micAsked = true
        } catch (failure: Exception) {
            // Not a failed seam: the memory flag stays as it is, and no notice is shown.
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
            modelReady = lastModelReady,
            downloadLine = lastDownloadLine,
            modelDownloadMb = lastModelDownloadMb,
        )
}
