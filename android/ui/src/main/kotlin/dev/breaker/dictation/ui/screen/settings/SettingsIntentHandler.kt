package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.theme.Theme
import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.ui.theme.ThemeOutcome
import dev.breaker.dictation.ui.theme.Themes
import dev.breaker.dictation.ui.write.WriteResult
import dev.breaker.dictation.ui.write.writeThrough

/*
 * The settings screen's memory, and the one place an intent is acted on.
 *
 * The screen itself is pure: it describes a tree and turns an intent into an
 * edit, and knows nothing of what happened. This class is what connects the two
 * halves, so it is also the only place that can know whether the settings were
 * read, whether the last action took effect, and therefore which message, if
 * any, belongs above the rows.
 *
 * Every action ends by building the whole result again rather than by patching
 * the tree that is already on the display. A tree has no field for "this label is
 * now stale", so a screen that was correct a moment ago can only be brought
 * forward by being replaced, and replacing it means asking the pure screen again
 * with the settings and theme as they are now.
 */

/** A screen tree and the scheme to draw it in, ready to hand to the renderer. */
internal data class Rendered(
    val screen: Screen,
    val theme: Theme,
)

/**
 * Acts on the intents the settings screen reports and says what to draw next.
 *
 * Two pieces of state are held: [known], the settings as last read or saved, and
 * [notice], the message for the action that did not take effect. Neither is
 * written to any storage of its own; both are derived from the store the caller
 * passes in.
 *
 * [known] can be stale for the fields a theme action does not touch. A theme
 * change goes through the theme controller, which reads and writes the store
 * itself, so the copy held here still carries whatever the non-theme fields held
 * when this class was built. The rows are refreshed on the next settings write,
 * and the theme rows are never stale because they come from the controller.
 *
 * The constructor never throws. When the settings cannot be read, [known] is
 * null, which the screen draws as a tree with no value rows at all rather than
 * one filled with defaults the user never chose, and the notice says the
 * settings could not be read.
 *
 * A message is never assembled from a value or from an exception's own text: a
 * failed write leaves the stored settings as they were, so any value in the
 * message would describe something not in force. The texts are fixed in [Notice].
 *
 * Call it from the main thread only; it does no locking.
 *
 * @param settings the store the app owns, through which every change is written.
 * @param themes the theme choice, read and written through the same store.
 * @param screen the pure screen, which draws and offers edits.
 */
internal class SettingsIntentHandler(
    private val settings: SettingsStore,
    private val themes: ThemeController,
    private val screen: SettingsScreen,
) {
    private var known: AppSettings? = readSettings()

    private var notice: Notice? = if (known == null || !themes.state.loaded) Notice.COULD_NOT_READ else null

    /** The screen as it stands now, without acting on anything. */
    fun current(): Rendered = render()

    /**
     * Acts on [intent] and returns the screen as it stands afterwards.
     *
     * The `when` names every intent the sealed hierarchy has, so an intent added
     * later is a compile error here rather than a tap that does nothing.
     */
    fun handle(intent: ScreenIntent): Rendered {
        when (intent) {
            ScreenIntent.ToggleTheme -> notice = themeNotice(themes.toggle())
            ScreenIntent.UseSystemTheme -> notice = themeNotice(themes.useSystem())
            is ScreenIntent.SetRoutingMode -> writeSetting(intent)
            is ScreenIntent.SetSetting -> writeSetting(intent)
            // The setup walk-through has its own screen and handler; this screen offers no such control.
            is ScreenIntent.Setup -> notice = Notice.NOT_ACCEPTED
            is ScreenIntent.History -> notice = Notice.NOT_ACCEPTED
        }
        return render()
    }

    /**
     * Turns a theme outcome into a message or its absence.
     *
     * A stored choice that was already in force is not a failure, so it leaves
     * no message behind; the next successful change clears an earlier one.
     */
    private fun themeNotice(outcome: ThemeOutcome): Notice? = when (outcome) {
        ThemeOutcome.CHANGED, ThemeOutcome.UNCHANGED -> null
        ThemeOutcome.COULD_NOT_READ -> Notice.COULD_NOT_READ
        ThemeOutcome.COULD_NOT_SAVE -> Notice.COULD_NOT_SAVE
    }

    /**
     * Applies a settings change the screen is willing to make.
     *
     * An intent the screen does not offer is refused here, before the store is
     * touched at all, so nothing is read and nothing is written for a request
     * that could not have been carried out.
     */
    private fun writeSetting(intent: ScreenIntent) {
        val edit = screen.editFor(intent)
        if (edit == null) {
            notice = Notice.NOT_ACCEPTED
            return
        }
        when (val result = writeThrough(settings, edit)) {
            // An edit that changed nothing is still a read that succeeded, so the
            // rows follow it and the message of an earlier failure goes away.
            is WriteResult.Saved -> {
                known = result.settings
                notice = null
            }
            is WriteResult.Unchanged -> {
                known = result.settings
                notice = null
            }
            WriteResult.FailedToLoad -> notice = Notice.COULD_NOT_READ
            WriteResult.FailedToSave -> notice = Notice.COULD_NOT_SAVE
            WriteResult.Refused -> notice = Notice.NOT_ACCEPTED
        }
    }

    /** The settings as they are stored, or null when they could not be read. */
    private fun readSettings(): AppSettings? = try {
        settings.load()
    } catch (loadFailure: Exception) {
        null
    }

    /**
     * Builds the whole result from the state as it is now.
     *
     * This runs after the write, never before, which is what makes the rows and
     * the scheme describe the change rather than the moment before it.
     */
    private fun render(): Rendered =
        Rendered(
            screen = screen.render(known, themes.state, notice),
            theme = Themes.of(themes.state.shown),
        )
}
