package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.render.AndroidClipboard
import dev.breaker.dictation.ui.render.HistoryHostView
import dev.breaker.dictation.ui.render.LateDelayedWork
import dev.breaker.dictation.ui.render.ScreenRenderer
import dev.breaker.dictation.ui.render.SettingsHostView
import dev.breaker.dictation.ui.screen.about.AboutHostView
import dev.breaker.dictation.ui.screen.about.AboutScreen
import dev.breaker.dictation.ui.screen.history.HistoryDispatcher
import dev.breaker.dictation.ui.screen.history.LocalizedTimestampFormat
import dev.breaker.dictation.ui.screen.howitworks.HowItWorksHostView
import dev.breaker.dictation.ui.screen.howitworks.HowItWorksScreen
import dev.breaker.dictation.ui.screen.settings.SettingsIntentHandler
import dev.breaker.dictation.ui.screen.settings.SettingsScreen
import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.ui.theme.isNightMode
import dev.breaker.dictation.ui.theme.storedTheme
import java.time.ZoneId

/*
 * The module's whole public surface: six functions and four interfaces, pinned by
 * the public surface check.
 *
 * The app owns the activity, the manifest entry, the settings store and the
 * switch that turns Breaker on and off; this module owns no window and no
 * storage. So there are six ways in, each of which takes what the app owns and
 * gives back a view to put in the layout, and four interfaces: BreakerSwitch, for
 * the app to implement, and three controls the app calls on the views it is given.
 * Everything a screen needs is built here and held by its
 * view, which is what lets the whole module ship without an activity of its own
 * and without a reference that outlives the window it was made for.
 */

/**
 * Builds the settings screen as a view for [context].
 *
 * The phone's own light or dark mode is read from [context] once, here, and
 * never again: a mode that reports neither night nor day counts as light, and
 * the reading belongs to the controller that decides what to draw. A change to
 * the phone's mode brings the activity back up by default, which calls this
 * again and reads the mode again; an app that keeps its view across such a
 * change keeps the mode that was read when the view was made.
 *
 * Every setting is read from and written to [settings], including the theme, so
 * this function touches no storage of its own and creates nothing that persists
 * after the returned view is dropped.
 *
 * The model download line is drawn with the ui kit's DownloadBarView. The
 * download state wires to the view through the app's ModelDownloadNotice callbacks
 * the app calls on the returned view.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param settings the store the app owns, through which every change is written.
 * @param modelReady answers whether the speech model is on the phone right now.
 * @param downloadLine answers the sentence to show under the model download, or null.
 * @param onDownloadModel asks the app to download the speech model.
 * @return a view that shows the settings and can be put in a layout as it stands.
 */
// The parameter types are written out in full: this is the one line of this
// module that an app reads, and it should say what it takes without asking the
// reader to follow an import.
fun createSettingsView(
    context: android.content.Context,
    settings: dev.breaker.dictation.core.port.SettingsStore,
    modelReady: () -> Boolean,
    downloadLine: () -> String?,
    onDownloadModel: () -> Unit,
): android.view.View {
    val phoneIsDark = isNightMode(context.resources.configuration.uiMode)
    val themes = ThemeController(settings, phoneIsDark)
    val handler = SettingsIntentHandler(settings, themes, SettingsScreen())
    return SettingsHostView(context, handler, themes, ScreenRenderer(context), modelReady, downloadLine, onDownloadModel)
}

/**
 * Builds the history screen as a view for [context].
 *
 * The screen lists what was dictated, newest first, with a copy and a delete action
 * on each row. A deletion can be undone for a short time: the row leaves [history]
 * only when that time has passed, or when the view leaves the window.
 *
 * The colours this screen draws in come from the shared reader [storedTheme], so it
 * stays with the stored choice; its phone's own light or dark mode is resolved there.
 * A mode that reports neither night nor day still counts as light once resolved. The
 * screen reads and deletes only through
 * [history], and copies text only to the system clipboard.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param history the store the app owns, through which every read and delete goes.
 * @param settings the store the app owns, through which the stored theme is read.
 * @return a view that shows the history and can be put in a layout as it stands.
 */
// The parameter types are written out in full, for the same reason as above.
fun createHistoryView(
    context: android.content.Context,
    history: dev.breaker.dictation.core.port.HistoryStore,
    settings: dev.breaker.dictation.core.port.SettingsStore,
): android.view.View {
    val theme = storedTheme(settings, isNightMode(context.resources.configuration.uiMode))
    val format = LocalizedTimestampFormat(context.resources.configuration.locales[0], ZoneId.systemDefault())
    val work = LateDelayedWork()
    val host = HistoryHostView(context, history, AndroidClipboard(context), format, work, HistoryDispatcher.serial, theme, ScreenRenderer(context))
    work.bind(host.scheduler())
    return host
}

/**
 * Builds the How it works screen as a view for [context].
 *
 * The screen reuses the welcome headings and whys from the first-run pages:
 * each permission page's heading and the short reason under it. The words are
 * the same ones the first-run pages show, so the wording stays in one place.
 *
 * The colours this screen draws in come from the shared reader [storedTheme], so it
 * stays with the stored choice; its phone's own light or dark mode is resolved there.
 * A mode that reports neither night nor day still counts as light once resolved.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param settings the store the app owns, through which the stored theme is read.
 * @return a view that shows the How it works screen and can be put in a layout
 *   as it stands.
 */
// The parameter types are written out in full, for the same reason as above.
fun createHowItWorksView(
    context: android.content.Context,
    settings: dev.breaker.dictation.core.port.SettingsStore,
): android.view.View {
    val theme = storedTheme(settings, isNightMode(context.resources.configuration.uiMode))
    return HowItWorksHostView(context, HowItWorksScreen(), theme, ScreenRenderer(context))
}

/**
 * Builds the About screen as a view for [context].
 *
 * The screen shows exactly two lines: what Breaker does and the license.
 * No version line unless one already exists on main.
 *
 * The colours this screen draws in come from the shared reader [storedTheme], so it
 * stays with the stored choice; its phone's own light or dark mode is resolved there.
 * A mode that reports neither night nor day still counts as light once resolved.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param settings the store the app owns, through which the stored theme is read.
 * @return a view that shows the About screen and can be put in a layout as it
 *   stands.
 */
// The parameter types are written out in full, for the same reason as above.
fun createAboutView(
    context: android.content.Context,
    settings: dev.breaker.dictation.core.port.SettingsStore,
): android.view.View {
    val theme = storedTheme(settings, isNightMode(context.resources.configuration.uiMode))
    return AboutHostView(context, AboutScreen(), theme, ScreenRenderer(context))
}
