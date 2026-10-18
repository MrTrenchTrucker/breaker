package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.ui.screen.BottomStripes
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.Spacer
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.titleBar
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

/** What the model row shows: whether the speech model is ready, and the sentence about a download, or null. */
internal data class ModelRow(val ready: Boolean, val line: String?) {
    internal companion object {
        /** The row when the speech model is on the phone and no download runs. */
        val READY: ModelRow = ModelRow(ready = true, line = null)
    }
}

/** The settings shown on screen, and the changes it is willing to make. */
internal class SettingsScreen {
    /**
     * Builds the tree for the settings in [settings].
     *
     * [settings] is null when they have never been read; [theme] is the theme as
     * last read or saved, which is available even then, [notice] is the message for
     * a change that did not take effect, or null, and [model] is the speech model row.
     *
     * The title bar comes first, then the blocks in reading order, each block set
     * apart by a spacer named by its size, and the brand stripes last.
     */
    fun render(settings: AppSettings?, theme: ThemeState, notice: Notice?, model: ModelRow = ModelRow.READY): Screen =
        Screen(
            id = SCREEN_ID,
            title = TITLE,
            nodes = buildList<Node> {
                addAll(titleBar(SCREEN_ID, TITLE))
                noticeNode(notice)?.let { add(it) }
                add(Spacer(SettingsIds.GAP_HEAD, Space.L))
                add(themeBlock(theme))
                if (settings != null) {
                    add(Spacer(SettingsIds.GAP_ROUTING, Space.L))
                    add(routingBlock(settings))
                    add(Spacer(SettingsIds.GAP_SWITCHES, Space.L))
                    add(switchesBlock(settings))
                    add(Spacer(SettingsIds.GAP_VALUES, Space.XL))
                    add(valuesBlock(settings, model))
                }
                add(BottomStripes(SettingsIds.STRIPE_ID, plain = true))
            },
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
        ScreenIntent.Back,
        -> null
        is ScreenIntent.History -> null
        is ScreenIntent.Home -> null
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
    internal companion object {
        const val SCREEN_ID = "settings"
        const val TITLE = "Settings"
    }
}
