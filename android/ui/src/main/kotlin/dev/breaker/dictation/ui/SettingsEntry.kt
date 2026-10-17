package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.render.AndroidClipboard
import dev.breaker.dictation.ui.render.AndroidSetupPlatform
import dev.breaker.dictation.ui.render.HistoryHostView
import dev.breaker.dictation.ui.render.LateDelayedWork
import dev.breaker.dictation.ui.render.ScreenRenderer
import dev.breaker.dictation.ui.render.SettingsHostView
import dev.breaker.dictation.ui.render.SetupHostView
import dev.breaker.dictation.ui.screen.history.HistoryDispatcher
import dev.breaker.dictation.ui.screen.history.LocalizedTimestampFormat
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.screen.onboarding.SetupScreen
import dev.breaker.dictation.ui.screen.settings.SettingsIntentHandler
import dev.breaker.dictation.ui.screen.settings.SettingsScreen
import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.ui.theme.isNightMode
import dev.breaker.dictation.ui.theme.storedTheme
import java.time.ZoneId

/*
 * The module's whole public surface: three functions and one interface.
 *
 * The app owns the activity, the manifest entry, the settings store and the
 * switch that turns Breaker on and off; this module owns no window and no
 * storage. So there are three ways in, each of which takes what the app owns and
 * gives back a view to put in the layout, and one interface, BreakerSwitch, for
 * the app to implement. Everything a screen needs is built here and held by its
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
 * @param context the context the views are built with, normally an activity's.
 * @param settings the store the app owns, through which every change is written.
 * @return a view that shows the settings and can be put in a layout as it stands.
 */
// The parameter types are written out in full: this is the one line of this
// module that an app reads, and it should say what it takes without asking the
// reader to follow an import.
fun createSettingsView(context: android.content.Context, settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View {
    val phoneIsDark = isNightMode(context.resources.configuration.uiMode)
    val themes = ThemeController(settings, phoneIsDark)
    val handler = SettingsIntentHandler(settings, themes, SettingsScreen())
    return SettingsHostView(context, handler, themes, ScreenRenderer(context))
}

/**
 * Builds the setup screen as a view for [context].
 *
 * The screen walks the user through the permissions Breaker needs and holds the
 * switch that turns Breaker on and off. What is granted is read from the phone
 * each time the screen is drawn, and again when the user comes back from a system
 * page or a permission dialog, so the screen shows what is true now. This module
 * stores nothing itself: whether Breaker is on is read from [switch], and the flag
 * that the microphone prompt was asked is kept in [settings], written once the prompt
 * has come back. The notification prompt is remembered in memory only, for as long as
 * the view lives. So the next tap on the same button opens a settings page instead
 * of asking again.
 *
 * The colours this screen draws in come from the shared reader [storedTheme], so it
 * stays with the stored choice; its phone's own light or dark mode is resolved there.
 * A mode that reports neither night nor day still counts as light once resolved.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param switch the switch the app provides for turning Breaker on and off.
 * @param accessibilityServiceComponent the accessibility service's component
 *   name, as the phone lists it among the enabled services, for example
 *   "com.example/com.example.TheService". It is compared whole, never as a part of
 *   a longer name. Either spelling of a class that starts with the package is
 *   accepted: "com.example/com.example.TheService" or "com.example/.TheService".
 * @param modelReady answers whether the speech model is on the phone right now. It
 *   is asked again on every refresh, so a download that finishes while the screen is
 *   open is shown without this module keeping the answer.
 * @param downloadLine answers the sentence to show under the model step's button,
 *   or null when the app has nothing to say; the download's own progress and failure
 *   sentences stay the app's.
 * @param modelDownloadMb answers the size in decimal megabytes the model step's
 *   line shows; read again on every refresh.
 * @param onDownloadModel asks the app to download the speech model; the download
 *   itself stays the app's.
 * @param settings the store the app owns; the microphone prompt flag is kept in it.
 * @return a view that shows the setup steps and the switch and can be put in a
 *   layout as it stands. The view also answers [ModelStepControl], which the app
 *   calls to refresh the model step and to focus its download button.
 */
// The parameter types are written out in full, for the same reason as above.
fun createOnboardingView(
    context: android.content.Context,
    switch: BreakerSwitch,
    accessibilityServiceComponent: String,
    modelReady: () -> Boolean,
    downloadLine: () -> String?,
    modelDownloadMb: () -> Int,
    onDownloadModel: () -> Unit,
    settings: dev.breaker.dictation.core.port.SettingsStore,
): android.view.View {
    val theme = storedTheme(settings, isNightMode(context.resources.configuration.uiMode))
    val platform = AndroidSetupPlatform(context, accessibilityServiceComponent)
    val handler = SetupIntentHandler(platform, switch, modelReady, downloadLine, modelDownloadMb, onDownloadModel, SetupScreen(), settings)
    return SetupHostView(context, handler, theme, ScreenRenderer(context))
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
