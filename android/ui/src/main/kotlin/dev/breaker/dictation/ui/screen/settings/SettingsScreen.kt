package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TrimStripe
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState

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
 * the settings have not been read successfully, so no value block is drawn at all:
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
     * Blocks are drawn in reading order and simply touch: the design gives no
     * spacing scale, so every distance between blocks is the platform's own.
     */
    fun render(settings: AppSettings?, theme: ThemeState, notice: Notice?): Screen =
        Screen(
            id = SCREEN_ID,
            title = TITLE,
            nodes = listOf(
                titleNode(),
                TrimStripe(SettingsIds.STRIPE_ID),
                noticeNode(notice),
                themeBlock(theme),
                settings?.let { routingBlock(it) },
                settings?.let { switchesBlock(it) },
                settings?.let { valuesBlock(it) },
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
     * not a boolean are both refused rather than guessed at. The appearance is
     * not a setting of this screen: its intents belong to the theme controller.
     *
     * The returned function is a copy of its argument with one field changed and
     * every other field left as found. It reads nothing, writes nothing and does
     * not throw.
     */
    fun editFor(intent: ScreenIntent): ((AppSettings) -> AppSettings)? = when (intent) {
        is ScreenIntent.SetRoutingMode -> routingEdit(intent.mode)
        is ScreenIntent.SetSetting -> booleanEdit(intent.key, intent.value)
        ScreenIntent.ToggleTheme,
        ScreenIntent.UseSystemTheme,
        is ScreenIntent.SetThemeMode,
        is ScreenIntent.Setup,
        -> null
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
            SettingsIds.KEY_PRELOAD -> { settings -> settings.copy(preloadModel = asked) }
            SettingsIds.KEY_WAKE_GESTURE -> { settings -> settings.copy(wakeGestureEnabled = asked) }
            SettingsIds.KEY_FORMATTING -> { settings -> settings.copy(formattingEnabled = asked) }
            else -> null
        }
    }

    private fun titleNode(): Label = Label(
        id = SettingsIds.TITLE_ID,
        text = TITLE,
        role = TypeRole.DISPLAY,
        color = PaletteSlot.TEXT,
    )

    /** The message for a change that did not take effect, or nothing when there is none. */
    private fun noticeNode(notice: Notice?): Label? = notice?.let {
        Label(
            id = SettingsIds.NOTICE_ID,
            text = it.text,
            role = TypeRole.BODY,
            color = PaletteSlot.DANGER,
        )
    }

    /** Identity of this screen and its title. */
    private companion object {
        const val SCREEN_ID = "settings"
        const val TITLE = "Settings"
    }
}
