package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
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
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode

/*
 * The settings screen, described as nodes and nothing else.
 *
 * This class holds no store, no cached settings and no state of its own. Both of
 * its functions are pure: [render] turns the settings it is handed into a tree,
 * and [editFor] turns an intent into the one-field change that intent asks for,
 * or into null when the intent is not one this screen offers. Keeping it pure is
 * what lets a plain JVM test read every text and every id the screen can put on
 * a display, with no device and no Android framework class.
 *
 * Values are read from the settings argument only. When that argument is null
 * the settings have not been read successfully, so no value row is drawn at all:
 * filling the rows from a default object would put numbers on the display that
 * the user never chose, and a tap on a row drawn from those numbers would save a
 * value derived from them.
 */

/** The settings shown on screen, and the changes it is willing to make. */
internal class SettingsScreen {
    /**
     * Builds the tree for the settings in [settings].
     *
     * [settings] is null when they have never been read; [theme] is the theme as
     * last read or saved, which is available even then, and [notice] is the
     * message for a change that did not take effect, or null.
     *
     * Rows are drawn in reading order and simply touch: the design gives no
     * spacing scale, so every distance between rows is the platform's own.
     */
    fun render(settings: AppSettings?, theme: ThemeState, notice: Notice?): Screen =
        Screen(
            id = SCREEN_ID,
            title = TITLE,
            nodes = listOf(
                titleNode(),
                TrimStripe(STRIPE_ID),
                noticeNode(notice),
                themeBlock(theme),
                routingBlock(settings),
                valuesBlock(settings),
            ).filterNotNull(),
        )

    /**
     * The one-field change [intent] asks for, or null when this screen does not
     * offer it.
     *
     * Only the three boolean switches and the routing mode are accepted, because
     * those are the only settings this screen offers a control for. A routing
     * mode is matched by its exact enum name and a boolean by the two literals
     * [Boolean.toString] produces, so a name in another case and a word that is
     * not a boolean are both refused rather than guessed at.
     *
     * The returned function is a copy of its argument with one field changed and
     * every other field left as found. It reads nothing, writes nothing and does
     * not throw.
     */
    fun editFor(intent: ScreenIntent): ((AppSettings) -> AppSettings)? = when (intent) {
        is ScreenIntent.SetRoutingMode -> routingEdit(intent.mode)
        is ScreenIntent.SetSetting -> booleanEdit(intent.key, intent.value)
        ScreenIntent.ToggleTheme, ScreenIntent.UseSystemTheme, is ScreenIntent.Setup -> null
        is ScreenIntent.History -> null
    }

    /**
     * The routing mode [mode] names, as a change of that one field, or null when
     * it names no mode.
     */
    private fun routingEdit(mode: String): ((AppSettings) -> AppSettings)? =
        SttMode.entries.firstOrNull { it.name == mode }
            ?.let { target -> { settings: AppSettings -> settings.copy(mode = target) } }

    /**
     * The change of the switch [key] to [value], or null when the screen has no
     * such switch or the value is not one [Boolean.toString] produces.
     */
    private fun booleanEdit(key: String, value: String): ((AppSettings) -> AppSettings)? {
        val asked = value.toBooleanStrictOrNull() ?: return null
        return when (key) {
            KEY_PRELOAD -> { settings -> settings.copy(preloadModel = asked) }
            KEY_WAKE_GESTURE -> { settings -> settings.copy(wakeGestureEnabled = asked) }
            KEY_FORMATTING -> { settings -> settings.copy(formattingEnabled = asked) }
            else -> null
        }
    }

    private fun titleNode(): Label = Label(
        id = TITLE_ID,
        text = TITLE,
        role = TypeRole.DISPLAY,
        color = PaletteSlot.TEXT,
    )

    /** The message for a change that did not take effect, or nothing when there is none. */
    private fun noticeNode(notice: Notice?): Label? = notice?.let {
        Label(
            id = NOTICE_ID,
            text = it.text,
            role = TypeRole.BODY,
            color = PaletteSlot.DANGER,
        )
    }

    /**
     * The scheme on screen, the control that pins the other one, and the control
     * that hands the choice back to the phone.
     *
     * The block is drawn from [ThemeState] alone, so it survives settings that
     * could not be read: the scheme shown then is the phone's own, which is the
     * honest answer when no choice has been read.
     */
    private fun themeBlock(theme: ThemeState): Box = Box(
        id = THEME_BLOCK_ID,
        background = PaletteSlot.SURFACE,
        children = listOf(
            sectionLabel(THEME_HEADING_ID, "Appearance"),
            Label(
                id = THEME_VALUE_ID,
                text = themeText(theme),
                role = TypeRole.BODY,
                color = PaletteSlot.TEXT,
            ),
            Action(
                id = THEME_TOGGLE_ID,
                text = toggleText(theme.shown),
                emphasis = Emphasis.PRIMARY,
                intent = ScreenIntent.ToggleTheme,
            ),
            Action(
                id = THEME_SYSTEM_ID,
                text = FOLLOW_PHONE_TEXT,
                // Already following the phone: asking again would be asking for a change that is not one.
                enabled = theme.stored != StoredThemeMode.SYSTEM,
                intent = ScreenIntent.UseSystemTheme,
            ),
        ),
    )

    /**
     * The three routing modes, the current one among them marked by being the one
     * that cannot be chosen; or nothing when the settings have not been read,
     * because with no mode in hand there is no current one to mark.
     */
    private fun routingBlock(settings: AppSettings?): Node? = settings?.let { held ->
        Box(
            id = ROUTING_BLOCK_ID,
            background = PaletteSlot.SURFACE,
            children = buildList {
                add(sectionLabel(ROUTING_HEADING_ID, "Routing"))
                add(
                    Label(
                        id = ROUTING_VALUE_ID,
                        text = "Mode: ${routingName(held.mode)}",
                        role = TypeRole.BODY,
                        color = PaletteSlot.TEXT,
                    ),
                )
                SttMode.entries.forEach { mode ->
                    add(
                        Action(
                            id = "$ROUTING_ACTION_PREFIX${mode.name}",
                            text = routingName(mode),
                            // The mode in force is not a choice the user can make here.
                            enabled = held.mode != mode,
                            intent = ScreenIntent.SetRoutingMode(mode.name),
                        ),
                    )
                }
            },
        )
    }

    /**
     * The switches and the read-only lines, or nothing at all when the settings
     * have not been read.
     */
    private fun valuesBlock(settings: AppSettings?): Node? = settings?.let { held ->
        Box(
            id = VALUES_BLOCK_ID,
            background = PaletteSlot.SURFACE,
            children = listOf(
                sectionLabel(SWITCHES_HEADING_ID, "Dictation"),
                switchNode(SWITCH_PRELOAD_ID, "Preload model", KEY_PRELOAD, held.preloadModel),
                switchNode(
                    SWITCH_WAKE_ID,
                    "Wake gesture",
                    KEY_WAKE_GESTURE,
                    held.wakeGestureEnabled,
                ),
                switchNode(SWITCH_FORMATTING_ID, "Formatting", KEY_FORMATTING, held.formattingEnabled),
                sectionLabel(VALUES_HEADING_ID, "Model and server"),
                valueLabel(MODEL_SIZE_ID, "Model: ${held.modelSize}"),
                valueLabel(SERVER_URL_ID, "Server: ${serverText(held)}"),
                valueLabel(LANGUAGE_ID, "Language: ${held.language}"),
                // Whether a key is held says nothing about which key, so the reference
                // itself is never drawn.
                valueLabel(API_KEY_ID, "API key: ${keyHeldText(held)}"),
            ),
        )
    }

    /**
     * A switch: its current state in its own text, and the opposite state in its
     * intent, so a tap asks for the one value the switch does not now hold.
     */
    private fun switchNode(id: String, name: String, key: String, current: Boolean): Action = Action(
        id = id,
        text = "$name: ${stateText(current)}",
        intent = ScreenIntent.SetSetting(key, (!current).toString()),
    )

    private fun sectionLabel(id: String, text: String): Label = Label(
        id = id,
        text = text,
        role = TypeRole.BODY,
        color = PaletteSlot.TEXT_MUTED,
    )

    private fun valueLabel(id: String, text: String): Label = Label(
        id = id,
        text = text,
        role = TypeRole.BODY,
        color = PaletteSlot.TEXT,
    )

    /**
     * The stored server address, or a plain statement that there is none.
     *
     * Whitespace is not an address, so a field holding only spaces reads the same
     * as an empty one.
     */
    private fun serverText(settings: AppSettings): String =
        if (settings.hasServerUrl) settings.serverUrl else NOT_SET_TEXT

    /** The scheme on screen, with the phone named as the source when it is the source. */
    private fun themeText(theme: ThemeState): String {
        val shown = shownName(theme.shown)
        val source = if (theme.stored == StoredThemeMode.SYSTEM) FOLLOWING_PHONE else PINNED_HERE
        return "Scheme: $shown, $source"
    }

    /** The scheme the control would switch to, which is the opposite of the one on screen. */
    private fun toggleText(shown: ShownThemeMode): String = when (shown) {
        ShownThemeMode.LIGHT -> "Use the dark scheme"
        ShownThemeMode.DARK -> "Use the light scheme"
    }

    private fun shownName(shown: ShownThemeMode): String = when (shown) {
        ShownThemeMode.LIGHT -> "light"
        ShownThemeMode.DARK -> "dark"
    }

    private fun routingName(mode: SttMode): String = when (mode) {
        SttMode.AUTO -> "Automatic"
        SttMode.LOCAL -> "On this phone"
        SttMode.SERVER -> "On the server"
    }

    private fun stateText(current: Boolean): String = if (current) ON_TEXT else OFF_TEXT

    /**
     * Whether a key is held, and nothing about which one.
     *
     * An empty reference is still a reference: the credential behind it is held,
     * so the line says the same thing for an empty reference as for a filled one.
     */
    private fun keyHeldText(settings: AppSettings): String =
        if (settings.apiKeyRef != null) HELD_TEXT else NOT_HELD_TEXT

    /** Identity and node ids of this screen. */
    private companion object {
        const val SCREEN_ID = "settings"
        const val TITLE = "Settings"
        const val TITLE_ID = "settings.title"
        const val STRIPE_ID = "settings.stripe"
        const val NOTICE_ID = "settings.notice"
        const val THEME_BLOCK_ID = "settings.themeBlock"
        const val THEME_HEADING_ID = "settings.themeHeading"
        const val THEME_VALUE_ID = "settings.theme"
        const val THEME_TOGGLE_ID = "settings.themeToggle"
        const val THEME_SYSTEM_ID = "settings.themeSystem"
        const val ROUTING_BLOCK_ID = "settings.routingBlock"
        const val ROUTING_HEADING_ID = "settings.routingHeading"
        const val ROUTING_VALUE_ID = "settings.routing"
        const val ROUTING_ACTION_PREFIX = "settings.mode."
        const val VALUES_BLOCK_ID = "settings.valuesBlock"
        const val SWITCHES_HEADING_ID = "settings.switchesHeading"
        const val SWITCH_PRELOAD_ID = "settings.preloadModel"
        const val SWITCH_WAKE_ID = "settings.wakeGestureEnabled"
        const val SWITCH_FORMATTING_ID = "settings.formattingEnabled"
        const val VALUES_HEADING_ID = "settings.valuesHeading"
        const val MODEL_SIZE_ID = "settings.modelSize"
        const val SERVER_URL_ID = "settings.serverUrl"
        const val LANGUAGE_ID = "settings.language"
        const val API_KEY_ID = "settings.apiKey"

        const val KEY_PRELOAD = "preloadModel"
        const val KEY_WAKE_GESTURE = "wakeGestureEnabled"
        const val KEY_FORMATTING = "formattingEnabled"

        const val FOLLOW_PHONE_TEXT = "Follow the phone"
        const val FOLLOWING_PHONE = "following the phone"
        const val PINNED_HERE = "chosen here"
        const val NOT_SET_TEXT = "(not set)"
        const val HELD_TEXT = "set"
        const val NOT_HELD_TEXT = "not set"
        const val ON_TEXT = "on"
        const val OFF_TEXT = "off"
    }
}
