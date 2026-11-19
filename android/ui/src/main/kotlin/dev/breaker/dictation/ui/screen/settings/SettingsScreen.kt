package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.BottomStripes
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.Spacer
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.titleBar
import dev.breaker.dictation.ui.screen.home.HomeActions
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.dictation.core.model.WakeGestureMode
import dev.breaker.dictation.ui.screen.KitLook

/*
 * The settings screen, described as nodes and nothing else.
 *
 * This class holds no store and no cached settings. [render] turns the settings
 * it is handed into a tree, and [editFor] turns an intent into the change that
 * intent asks for, or into null when the intent is not one this screen offers.
 * The one piece of state it keeps is the draft row count [askedRows], which the
 * add and remove row controls change and which is not a setting: a row the
 * person adds is a draft of the screen, drawn as an empty field, and is not
 * stored until it is filled. Keeping that count here is what lets the rows a
 * person adds and removes survive a redraw without writing them.
 *
 * Values are read from the settings argument only. When that argument is null
 * the settings have not been read successfully, so no value block is drawn at all:
 * filling the rows from a default object would put numbers on the display that
 * the user never chose, and a tap on a row drawn from those numbers would save a
 * value derived from them.
 */

/**
 * What the model row shows: whether the speech model is ready, and the sentence about a download, or null.
 *
 * [rows] holds one [ModelRowItem] per offered speech model. When it is empty the row is drawn as it always
 * was: one Download button, its line, and the READY badge when the model is ready.
 */
internal data class ModelRow(val ready: Boolean, val line: String?, val rows: List<ModelRowItem> = emptyList()) {
    internal companion object {
        /** The row when the speech model is on the phone and no download runs. */
        val READY: ModelRow = ModelRow(ready = true, line = null)
    }
}

/**
 * One offered speech model as the settings section draws it: its id, its label and rating from the registry,
 * its download size, whether it is on the phone, whether it is the model in use, and the download sentence
 * while this model is the one downloading.
 */
internal data class ModelRowItem(
    val id: String,
    val label: String,
    val rating: Int,
    val sizeMb: Int,
    val installed: Boolean,
    val inUse: Boolean,
    val line: String?,
)

/** The settings shown on screen, and the changes it is willing to make. */
internal class SettingsScreen {
    /**
     * The row count the person has asked for, or null to follow the stored list.
     *
     * Null is the default: the screen draws as many rows as are stored, at least
     * the one row of a blank install. A number is a draft the person made by
     * adding or removing a row, and it is never written to the settings.
     */
    private var askedRows: Int? = null

    /**
     * Builds the tree for the settings in [settings].
     *
     * [settings] is null when they have never been read; [theme] is the theme as
     * last read or saved, which is available even then, [notice] is the message for
     * a change that did not take effect, or null, and [model] is the speech model row.
     *
     * The title bar comes first, then the blocks in reading order, each block set
     * apart by a spacer named by its size, and the brand stripes last.
     *
     * [serverAddressStatus] is the app's own reading of the stored addresses, as
     * it was handed in through the host, or null when no provider was wired. The
     * screen never probes or reads an address itself: it only draws what it is told.
     */
    fun render(settings: AppSettings?, theme: ThemeState, notice: Notice?, model: ModelRow = ModelRow.READY, signedOut: Boolean = false, serverAddressStatus: ServerAddressStatus? = null): Screen =
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
                    add(valuesBlock(settings, model, askedRows ?: storedRowCount(settings), serverAddressStatus))
                }
                if (signedOut) {
                    add(Spacer(SettingsIds.GAP_SIGNED_OUT, Space.L))
                    add(Label(SettingsIds.SIGNED_OUT, SettingsTexts.SIGNED_OUT, TypeRole.BODY, PaletteSlot.TEXT))
                    add(Action(SettingsIds.SIGN_IN, SettingsTexts.SIGN_IN, intent = ScreenIntent.Home(HomeActions.SIGN_IN), kit = KitLook.TEXT))
                }
                add(BottomStripes(SettingsIds.STRIPE_ID, plain = true))
            },
        )

    /**
     * The change [intent] asks for, or null when this screen does not offer it.
     *
     * Only the three boolean switches, the routing mode, the wake-gesture mode,
     * the server address rows and the row actions are accepted, because those
     * are the only settings this screen offers a control for. A routing mode is
     * matched by its exact enum name and a boolean by the two literals
     * [Boolean.toString] produces, so a name in another case and a word that is
     * not a boolean are both refused rather than guessed at. A server address is
     * checked by [ServerAddressEntry], and an address it refuses gives null. The
     * appearance is not a setting of this screen: its intents belong to the
     * theme controller.
     *
     * The returned function is a copy of its argument with the fields the intent
     * names changed and every other field left as found. It reads nothing and
     * writes nothing; a change the model refuses throws
     * [IllegalArgumentException], which the writer reads as a refusal. The two
     * row actions are the exception: they change the screen's draft row count
     * and return the settings exactly as they were, so the write path stores
     * nothing for them.
     */
    fun editFor(intent: ScreenIntent): ((AppSettings) -> AppSettings)? = when (intent) {
        is ScreenIntent.SetRoutingMode -> routingEdit(intent.mode)
        is ScreenIntent.SetSetting -> wakeModeEdit(intent.key, intent.value)
            ?: booleanEdit(intent.key, intent.value)
            ?: serverAddressEdit(intent.key, intent.value)
        is ScreenIntent.AddServerAddress -> addServerAddressRowEdit(intent.index)
        is ScreenIntent.RemoveServerAddress -> removeServerAddressRowEdit()
        is ScreenIntent.MoveServerAddressUp -> moveServerAddressRow(intent.index, up = true)
        is ScreenIntent.MoveServerAddressDown -> moveServerAddressRow(intent.index, up = false)
        ScreenIntent.ToggleTheme,
        ScreenIntent.UseSystemTheme,
        is ScreenIntent.SetThemeMode,
        is ScreenIntent.Setup,
        ScreenIntent.Back,
        -> null
        is ScreenIntent.History -> null
        is ScreenIntent.Home -> null
        is ScreenIntent.About -> null
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
            SettingsIds.KEY_FORMATTING -> { settings -> settings.copy(formattingEnabled = asked) }
            else -> null
        }
    }

    /** The wake-gesture mode [mode] names, as a change of that one field, or null when it names no mode. Matched by exact enum name only - "always" and "off" are refused rather than guessed at. */
    private fun wakeModeEdit(key: String, value: String): ((AppSettings) -> AppSettings)? =
        WakeGestureMode.entries.firstOrNull { it.name == value }
            ?.let { target -> { settings: AppSettings -> settings.copy(wakeGestureMode = target) } }
            .takeIf { key == SettingsIds.KEY_WAKE_GESTURE_MODE }

    /**
     * The server address [value] names for the row [key] belongs to, as a change of
     * the stored list, or null when the key is no row's key or the address is refused.
     * Row 1 is the address key the screen always used; the other rows carry their own
     * stored key, so a row's typing keeps every other row where it is.
     */
    private fun serverAddressEdit(key: String, value: String): ((AppSettings) -> AppSettings)? {
        val index = SERVER_ROW_KEYS.indexOf(key)
        if (index < 0) return null
        return serverAddressRowEdit(index, value)
    }

    /**
     * The draft row change an Add asks for, as a change that writes nothing.
     *
     * A row the person adds is a draft of the screen, so [AppSettings] is
     * returned exactly as it was: the write path reads the store, compares the
     * two and saves nothing. The new count is the count the control was drawn
     * with, which is the stored count while no draft is held.
     */
    private fun addServerAddressRowEdit(index: Int): (AppSettings) -> AppSettings = { held ->
        askedRows = addServerAddressRow(askedRows ?: index.coerceIn(1, SERVER_ROW_LIMIT))
        held
    }

    /**
     * The draft row change a Remove asks for, as a change that writes nothing.
     *
     * The count falls to one and no further, so the blank install keeps its one
     * row; the stored list is left as it was until a row is filled.
     */
    private fun removeServerAddressRowEdit(): (AppSettings) -> AppSettings = { held ->
        askedRows = removeServerAddressRow(askedRows ?: storedRowCount(held))
        held
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
