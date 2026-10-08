package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TrimStripe
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * The set-up screen, described as nodes and nothing else.
 *
 * One block per step, in the order the model lists them, then the switch, then a
 * button that reads the phone again. The screen holds no state: it is a function of
 * the SetupState and of the notice it is handed, so a plain JVM test can read every
 * text, id and button the screen can put on a display.
 *
 * Every button reports a Setup intent carrying one of the strings in SetupActions.
 * The strings are the only contract between this screen and the handler that acts
 * on a tap.
 */

/** The strings a button on the set-up screen reports, and how they map to what the phone is asked to do. */
internal object SetupActions {
    const val OPEN_OVERLAY = "open.overlay"
    const val OPEN_MICROPHONE = "open.microphone"
    const val OPEN_NOTIFICATIONS = "open.notifications"
    const val OPEN_ACCESSIBILITY = "open.accessibility"
    const val OPEN_APP_INFO = "open.appinfo"
    const val SWITCH_ON = "switch.on"
    const val SWITCH_OFF = "switch.off"
    const val RECHECK = "recheck"

    /** The strings that ask the phone to open something. */
    val OPENING: Set<String> =
        setOf(OPEN_OVERLAY, OPEN_MICROPHONE, OPEN_NOTIFICATIONS, OPEN_ACCESSIBILITY, OPEN_APP_INFO)

    /**
     * The string a button reports for [action].
     *
     * Asking for notifications and opening the notification settings share one
     * string, because the user sees one notifications step; [openActionFor] tells
     * them apart from the state.
     */
    fun stringFor(action: OpenAction): String = when (action) {
        OpenAction.REQUEST_MICROPHONE -> OPEN_MICROPHONE
        OpenAction.REQUEST_NOTIFICATIONS -> OPEN_NOTIFICATIONS
        OpenAction.OVERLAY_PAGE -> OPEN_OVERLAY
        OpenAction.NOTIFICATION_PAGE -> OPEN_NOTIFICATIONS
        OpenAction.ACCESSIBILITY_LIST -> OPEN_ACCESSIBILITY
        OpenAction.APP_INFO -> OPEN_APP_INFO
    }

    /**
     * What the phone is asked to do for the opening string [action], or null.
     *
     * Null for a string that does not open anything, and for the notifications
     * string once notifications are granted, when there is nothing left to open.
     * The notifications string is resolved against [state], the state the user was
     * looking at when they tapped.
     */
    fun openActionFor(action: String, state: SetupState): OpenAction? {
        if (action == OPEN_OVERLAY) return OpenAction.OVERLAY_PAGE
        if (action == OPEN_MICROPHONE) return OpenAction.REQUEST_MICROPHONE
        if (action == OPEN_ACCESSIBILITY) return OpenAction.ACCESSIBILITY_LIST
        if (action == OPEN_APP_INFO) return OpenAction.APP_INFO
        if (action == OPEN_NOTIFICATIONS) return state.actionFor(SetupStep.NOTIFICATIONS)
        return null
    }
}

/** The id of the message node shown after an action that did not work; the view that holds the screen looks for it. */
internal const val SETUP_NOTICE_ID = "setup.notice"

/** The set-up screen. */
internal class SetupScreen {
    /**
     * Builds the tree for [state].
     *
     * [notice] is the message for an action that did not work, or null; it is the
     * first node under the title's rule. The screen cannot move the view, so it is
     * the view that scrolls to the top when it draws a screen holding this node
     * (see SetupHostView.show), and that is what puts the message in sight after a
     * tap lower down the page.
     */
    fun render(state: SetupState, notice: SetupNotice?): Screen =
        Screen(
            id = SCREEN_ID,
            title = SetupTexts.TITLE,
            nodes = buildList {
                add(Label(TITLE_ID, SetupTexts.TITLE, TypeRole.DISPLAY, PaletteSlot.TEXT))
                add(TrimStripe(STRIPE_ID))
                if (notice != null) add(Label(SETUP_NOTICE_ID, notice.text, TypeRole.BODY, PaletteSlot.DANGER))
                add(Label(INTRO_ID, SetupTexts.INTRO, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
                SetupStep.entries.forEach { add(stepBlock(it, state)) }
                add(switchBlock(state))
                add(
                    Action(
                        id = RECHECK_ID,
                        text = SetupTexts.BUTTON_CHECK_AGAIN,
                        intent = ScreenIntent.Setup(SetupActions.RECHECK),
                    ),
                )
            },
        )

    /**
     * One step: what it is, why, whether it is done, what to read before the button,
     * the button while it is not done, and what is lost if it is left undone.
     */
    private fun stepBlock(step: SetupStep, state: SetupState): Box {
        val granted = state.statusOf(step) == StepStatus.GRANTED
        val prefix = "$STEP_PREFIX${step.name.lowercase()}"
        val opening = state.actionFor(step)
        return Box(
            id = prefix,
            background = PaletteSlot.SURFACE,
            children = buildList {
                add(Label("$prefix.heading", headingOf(step), TypeRole.BODY, PaletteSlot.TEXT))
                add(Label("$prefix.why", whyOf(step), TypeRole.BODY, PaletteSlot.TEXT_MUTED))
                add(statusLabel("$prefix.status", granted))
                addAll(readBeforeButton(step, state))
                if (opening != null) {
                    add(
                        Action(
                            id = "$prefix.button",
                            text = buttonTextOf(opening),
                            emphasis = Emphasis.PRIMARY,
                            intent = ScreenIntent.Setup(SetupActions.stringFor(opening)),
                        ),
                    )
                }
                addAll(lostWithout(step, granted))
            },
        )
    }

    /** Done in the sent colour, not done in the warning colour. */
    private fun statusLabel(id: String, granted: Boolean): Label =
        if (granted) {
            Label(id, SetupTexts.STATUS_GRANTED, TypeRole.BODY, PaletteSlot.STATE_SENT)
        } else {
            Label(id, SetupTexts.STATUS_NOT_GRANTED, TypeRole.BODY, PaletteSlot.STATE_WARNING)
        }

    /**
     * What the user reads before the button that leaves this screen: what the
     * accessibility access may and may not do, always, and the extra confirmation
     * steps while Android may be asking for them.
     */
    private fun readBeforeButton(step: SetupStep, state: SetupState): List<Node> = when (step) {
        SetupStep.OVERLAY, SetupStep.MICROPHONE, SetupStep.NOTIFICATIONS -> emptyList()
        SetupStep.ACCESSIBILITY -> listOfNotNull(
            note(LIMITS_ID, SetupTexts.ACCESSIBILITY_LIMITS),
            if (state.showRestrictedHelp) restrictedHelp() else null,
        )
    }

    /** What is lost without notifications, below their button, while they are off. */
    private fun lostWithout(step: SetupStep, granted: Boolean): List<Node> = when (step) {
        SetupStep.NOTIFICATIONS ->
            if (granted) emptyList() else listOf(note(NOTIFICATIONS_LOST_ID, SetupTexts.NOTIFICATIONS_LOST))
        SetupStep.OVERLAY, SetupStep.MICROPHONE, SetupStep.ACCESSIBILITY -> emptyList()
    }

    /** The three things to do when Android blocks the accessibility switch. Explanation only: it has no status and no button. */
    private fun restrictedHelp(): Box = Box(
        id = RESTRICTED_ID,
        background = PaletteSlot.BACKGROUND,
        children = listOf(
            Label("$RESTRICTED_ID.intro", SetupTexts.RESTRICTED_INTRO, TypeRole.BODY, PaletteSlot.TEXT),
            Label("$RESTRICTED_ID.step1", SetupTexts.RESTRICTED_STEP_1, TypeRole.BODY, PaletteSlot.TEXT),
            Label("$RESTRICTED_ID.step2", SetupTexts.RESTRICTED_STEP_2, TypeRole.BODY, PaletteSlot.TEXT),
            Label("$RESTRICTED_ID.step3", SetupTexts.RESTRICTED_STEP_3, TypeRole.BODY, PaletteSlot.TEXT),
        ),
    )

    /**
     * The switch: whether Breaker is on, the one button that fits, what is missing.
     *
     * Switching off is always offered while Breaker is on, even when the microphone
     * has been taken away since. Switching on needs the microphone and nothing else,
     * and the line that says so shows only while the on button is the one drawn; the
     * other steps only show a warning.
     */
    private fun switchBlock(state: SetupState): Box = Box(
        id = SWITCH_ID,
        background = PaletteSlot.SURFACE,
        children = buildList {
            add(Label("$SWITCH_ID.heading", SetupTexts.SWITCH_HEADING, TypeRole.BODY, PaletteSlot.TEXT))
            if (state.switchedOn) {
                add(Label("$SWITCH_ID.state", SetupTexts.SWITCH_ON_STATE, TypeRole.BODY, PaletteSlot.STATE_SENT))
                add(
                    Action(
                        id = "$SWITCH_ID.off",
                        text = SetupTexts.BUTTON_SWITCH_OFF,
                        intent = ScreenIntent.Setup(SetupActions.SWITCH_OFF),
                    ),
                )
            } else {
                add(Label("$SWITCH_ID.state", SetupTexts.SWITCH_OFF_STATE, TypeRole.BODY, PaletteSlot.TEXT))
                add(
                    Action(
                        id = "$SWITCH_ID.on",
                        text = SetupTexts.BUTTON_SWITCH_ON,
                        enabled = state.canSwitchOn,
                        emphasis = Emphasis.PRIMARY,
                        intent = ScreenIntent.Setup(SetupActions.SWITCH_ON),
                    ),
                )
                if (!state.canSwitchOn) {
                    add(
                        Label(
                            "$SWITCH_ID.needsMicrophone",
                            SetupTexts.SWITCH_NEEDS_MICROPHONE,
                            TypeRole.BODY,
                            PaletteSlot.STATE_WARNING,
                        ),
                    )
                }
            }
            add(note("$SWITCH_ID.note", SetupTexts.SWITCH_NOTE_ONGOING_NOTICE))
            state.warnings.forEach { add(warningLabel(it)) }
        },
    )

    private fun warningLabel(warning: SetupWarning): Label = when (warning) {
        SetupWarning.NO_OVERLAY ->
            Label("$WARNING_PREFIX.overlay", SetupTexts.WARNING_NO_OVERLAY, TypeRole.BODY, PaletteSlot.STATE_WARNING)
        SetupWarning.NO_ACCESSIBILITY ->
            Label(
                "$WARNING_PREFIX.accessibility",
                SetupTexts.WARNING_NO_ACCESSIBILITY,
                TypeRole.BODY,
                PaletteSlot.STATE_WARNING,
            )
    }

    private fun note(id: String, text: String): Label = Label(id, text, TypeRole.BODY, PaletteSlot.TEXT_MUTED)

    private fun headingOf(step: SetupStep): String = when (step) {
        SetupStep.OVERLAY -> SetupTexts.HEADING_OVERLAY
        SetupStep.MICROPHONE -> SetupTexts.HEADING_MICROPHONE
        SetupStep.NOTIFICATIONS -> SetupTexts.HEADING_NOTIFICATIONS
        SetupStep.ACCESSIBILITY -> SetupTexts.HEADING_ACCESSIBILITY
    }

    private fun whyOf(step: SetupStep): String = when (step) {
        SetupStep.OVERLAY -> SetupTexts.WHY_OVERLAY
        SetupStep.MICROPHONE -> SetupTexts.WHY_MICROPHONE
        SetupStep.NOTIFICATIONS -> SetupTexts.WHY_NOTIFICATIONS
        SetupStep.ACCESSIBILITY -> SetupTexts.WHY_ACCESSIBILITY
    }

    private fun buttonTextOf(action: OpenAction): String = when (action) {
        OpenAction.REQUEST_MICROPHONE -> SetupTexts.BUTTON_REQUEST_MICROPHONE
        OpenAction.REQUEST_NOTIFICATIONS -> SetupTexts.BUTTON_REQUEST_NOTIFICATIONS
        OpenAction.OVERLAY_PAGE -> SetupTexts.BUTTON_OVERLAY_PAGE
        OpenAction.NOTIFICATION_PAGE -> SetupTexts.BUTTON_NOTIFICATION_PAGE
        OpenAction.ACCESSIBILITY_LIST -> SetupTexts.BUTTON_ACCESSIBILITY_LIST
        OpenAction.APP_INFO -> SetupTexts.BUTTON_APP_INFO
    }

    /** Identity and node ids of this screen. */
    private companion object {
        const val SCREEN_ID = "setup"
        const val TITLE_ID = "setup.title"
        const val STRIPE_ID = "setup.stripe"
        const val INTRO_ID = "setup.intro"
        const val STEP_PREFIX = "setup.step."
        const val NOTIFICATIONS_LOST_ID = "setup.step.notifications.lost"
        const val LIMITS_ID = "setup.step.accessibility.limits"
        const val RESTRICTED_ID = "setup.step.accessibility.restricted"
        const val SWITCH_ID = "setup.switch"
        const val WARNING_PREFIX = "setup.switch.warning"
        const val RECHECK_ID = "setup.recheck"
    }
}
