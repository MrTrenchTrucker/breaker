package dev.breaker.dictation.ui.screen.onboarding

/*
 * What the set-up screen knows, as plain data and plain rules.
 *
 * The phone is asked four questions (may Breaker draw over other apps, record, post
 * notices, type into other apps) and the answers arrive as one PlatformStatus. Which
 * button each step offers, which step comes next and which warnings sit beside the
 * switch are decided here and nowhere else, so they are checkable on a plain JVM
 * with no device and no Android class.
 *
 * Nothing here is stored. Whether the user was already asked for a permission is a
 * fact the caller holds in memory and passes in; a phone that was asked once is sent
 * to a settings page afterwards, because Android may show no dialog a second time.
 */

/** The first Android version that shows a prompt for notifications. */
private const val FIRST_SDK_WITH_NOTIFICATION_PROMPT = 33

/** The first Android version that can block an accessibility switch for an app installed by hand. */
private const val FIRST_SDK_WITH_RESTRICTED_SETTINGS = 33

/** The four things Breaker needs from the phone, in the order the screen shows them. */
internal enum class SetupStep { OVERLAY, MICROPHONE, NOTIFICATIONS, ACCESSIBILITY }

/** Whether the phone has given Breaker what a step asks for. */
internal enum class StepStatus { GRANTED, NOT_GRANTED }

/**
 * The phone's answers, read at one moment.
 *
 * [sdkInt] is the Android version number; the rules that differ by version read it
 * here instead of asking the phone again.
 */
internal data class PlatformStatus(
    val overlay: Boolean,
    val microphone: Boolean,
    val notifications: Boolean,
    val accessibility: Boolean,
    val sdkInt: Int,
)

/** What a button can do: ask in a dialog, or open a page of the phone's settings. */
internal enum class OpenAction {
    REQUEST_MICROPHONE,
    REQUEST_NOTIFICATIONS,
    OVERLAY_PAGE,
    NOTIFICATION_PAGE,
    ACCESSIBILITY_LIST,
    APP_INFO,
}

/** Whether the phone carried out an [OpenAction]. */
internal enum class OpenResult { OPENED, NOT_POSSIBLE }

/**
 * The phone, as the set-up screen sees it.
 *
 * Either call may throw; the caller guards both, so an implementation does not
 * need to hide a failure.
 */
internal interface SetupPlatform {
    /** The phone's answers right now. */
    fun status(): PlatformStatus

    /** Asks for a permission or opens a settings page. */
    fun open(action: OpenAction): OpenResult
}

/**
 * The phone's list of enabled accessibility services, read as the list it is.
 *
 * The phone keeps the list as one text, the services' names joined by colons. A
 * name is written in one of two ways, "pkg/pkg.Class" or the short "pkg/.Class",
 * and the phone may hold either one.
 */
internal object AccessibilityList {
    private const val SEPARATOR = ':'
    private const val SLASH = '/'
    private const val DOT = '.'

    /**
     * Whether [component] is one whole entry of [enabledServices].
     *
     * Both sides are first brought to the long form, so "pkg/.Class" and
     * "pkg/pkg.Class" are the same name. Then entries are compared whole and
     * case-sensitively, with nothing trimmed, so a name that is only part of another
     * entry does not match. A missing or empty list holds nothing, and a blank
     * [component] matches nothing, not even the empty entries that stray colons
     * leave in a list.
     */
    fun contains(enabledServices: String?, component: String): Boolean {
        if (enabledServices.isNullOrEmpty() || component.isBlank()) return false
        val wanted = longForm(component)
        return enabledServices.split(SEPARATOR).any { entry -> longForm(entry) == wanted }
    }

    /**
     * [name] with a short class part written out: when the text after the first
     * slash starts with a dot, the text before the slash is put in front of it.
     * Anything else, including a text with no slash, is returned as it is.
     */
    private fun longForm(name: String): String {
        val slash = name.indexOf(SLASH)
        if (slash < 0) return name
        val packageName = name.substring(0, slash)
        val classPart = name.substring(slash + 1)
        return if (classPart.startsWith(DOT)) "$packageName/$packageName$classPart" else name
    }
}

/** Something that may be wrong beside the switch without stopping it. */
internal enum class SetupWarning { NO_OVERLAY, NO_ACCESSIBILITY }

/**
 * Everything the set-up screen is drawn from.
 *
 * [switchedOn] is read from the switch on every refresh. [micAskedBefore] and
 * [notificationsAskedBefore] say whether the matching prompt has already been shown
 * since the screen was opened. The second one defaults to false, so a caller that
 * never asks for notifications can leave it out.
 */
internal data class SetupState(
    val status: PlatformStatus,
    val switchedOn: Boolean,
    val micAskedBefore: Boolean,
    val notificationsAskedBefore: Boolean = false,
) {
    /** Whether the phone has given Breaker what [step] needs. */
    fun statusOf(step: SetupStep): StepStatus =
        if (isGranted(step)) StepStatus.GRANTED else StepStatus.NOT_GRANTED

    /**
     * The button [step] offers, or null when the step is already granted.
     *
     * Android may not show a refused microphone or notification prompt again, so
     * after the first ask the button opens a settings page instead.
     */
    fun actionFor(step: SetupStep): OpenAction? {
        if (isGranted(step)) return null
        return when (step) {
            SetupStep.OVERLAY -> OpenAction.OVERLAY_PAGE
            SetupStep.MICROPHONE ->
                if (micAskedBefore) OpenAction.APP_INFO else OpenAction.REQUEST_MICROPHONE
            SetupStep.NOTIFICATIONS ->
                if (canPromptForNotifications) OpenAction.REQUEST_NOTIFICATIONS else OpenAction.NOTIFICATION_PAGE
            SetupStep.ACCESSIBILITY -> OpenAction.ACCESSIBILITY_LIST
        }
    }

    /** Only the microphone stops Breaker being switched on. */
    val canSwitchOn: Boolean
        get() = status.microphone

    /**
     * Whether to explain the extra confirmation Android asks for.
     *
     * The phone cannot say whether the switch is blocked, so the explanation is shown
     * from Android 13 on for as long as the accessibility switch is not on.
     */
    val showRestrictedHelp: Boolean
        get() = status.sdkInt >= FIRST_SDK_WITH_RESTRICTED_SETTINGS && !status.accessibility

    /** The first step, in display order, that is not granted; null when all are. */
    val nextStep: SetupStep?
        get() = SetupStep.entries.firstOrNull { step -> !isGranted(step) }

    /**
     * What is missing that Breaker can work without, in the order the screen lists it.
     *
     * Empty while the switch can neither be turned on nor is on, because there is
     * nothing to warn about yet.
     */
    val warnings: List<SetupWarning>
        get() {
            if (!canSwitchOn && !switchedOn) return emptyList()
            return buildList {
                if (!status.overlay) add(SetupWarning.NO_OVERLAY)
                if (!status.accessibility) add(SetupWarning.NO_ACCESSIBILITY)
            }
        }

    private val canPromptForNotifications: Boolean
        get() = status.sdkInt >= FIRST_SDK_WITH_NOTIFICATION_PROMPT && !notificationsAskedBefore

    private fun isGranted(step: SetupStep): Boolean = when (step) {
        SetupStep.OVERLAY -> status.overlay
        SetupStep.MICROPHONE -> status.microphone
        SetupStep.NOTIFICATIONS -> status.notifications
        SetupStep.ACCESSIBILITY -> status.accessibility
    }
}
