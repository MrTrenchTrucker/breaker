package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.render.ScreenRenderer
import dev.breaker.dictation.ui.render.SettingsHostView
import dev.breaker.dictation.ui.screen.settings.SettingsIntentHandler
import dev.breaker.dictation.ui.screen.settings.SettingsScreen
import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.ui.theme.isNightMode

/*
 * The module's whole public surface, in one function.
 *
 * The app owns the activity, the manifest entry and the settings store; this
 * module owns no window and no storage. So there is one way in: hand over a
 * context and a store, get back a view to put in the layout. Everything the
 * screen needs is built here and held by the view, which is what lets the
 * whole module ship without an activity of its own and without a reference
 * that outlives the window it was made for.
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