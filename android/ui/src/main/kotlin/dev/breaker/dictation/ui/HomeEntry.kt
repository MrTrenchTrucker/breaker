package dev.breaker.dictation.ui

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.render.HomeHostView
import dev.breaker.dictation.ui.render.ScreenRenderer
import dev.breaker.dictation.ui.screen.home.HomeIntentHandler
import dev.breaker.dictation.ui.screen.home.HomeScreen
import dev.breaker.dictation.ui.theme.Themes
import dev.breaker.dictation.ui.theme.isNightMode
import dev.breaker.dictation.ui.theme.shownMode

/*
 * The home screen's way in.
 *
 * The app owns the activity, the manifest entry, the settings store and the
 * switch that turns Breaker on and off; this module owns no window and no
 * storage. The three other entries take what the app owns and give back a view
 * to put in the layout; this one is the home screen's, and takes the app's
 * answers as functions instead of stores, because the home screen's facts are
 * read again on every drawing and core has no port to read them through. What
 * is built here is held by the returned view, which is what lets the whole
 * module ship without an activity of its own and without a reference that
 * outlives the window it was made for.
 */

/**
 * Builds the home screen as a view for [context].
 *
 * The screen shows whether Breaker is on, whether the model is downloaded, the
 * last dictation and its transcript with a Copy button, and a menu with the
 * history, the settings and two pages of the app's own. Every fact is read again
 * on every drawing, so a take that finished while the screen was off screen, or
 * a switch turned off elsewhere, is shown the next time the view is drawn.
 *
 * The card's switch row is wired to [switch], the same BreakerSwitch the setup
 * screen is given: a tap asks it to change, and the answer is read again on the
 * next drawing. A tap on Copy asks the app to copy the transcript on display,
 * and a menu item asks the app for the screen it names; the drawer those live
 * in is the screen's own and opens and closes with the taps.
 *
 * This screen has no settings store, so it follows the phone's own light or
 * dark mode, which is read from [context] once, here. A mode that reports
 * neither night nor day counts as light.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param switch the switch the app offers for turning Breaker on and off.
 * @param modelReady answers whether the speech model is on the phone right now.
 * @param lastDictation answers the last thing that was dictated, or null when
 *   there is none.
 * @param lastTranscript answers the last transcript, or null when there is
 *   none; it is what a tap on Copy copies.
 * @param transcriptTime answers the phone's own short time for the transcript.
 * @param onCopy asks the app to copy the transcript on display to the clipboard.
 * @param onHistory asks the app to show the history screen.
 * @param onSettings asks the app to show the settings screen.
 * @return a view that shows the home screen and can be put in a layout as it
 *   stands.
 */
// The parameter types are written out in full, for the same reason as above.
fun createHomeView(
    context: android.content.Context,
    switch: BreakerSwitch,
    modelReady: () -> Boolean,
    lastDictation: () -> String?,
    lastTranscript: () -> String?,
    transcriptTime: () -> String?,
    onCopy: (String) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
): android.view.View {
    val phoneIsDark = isNightMode(context.resources.configuration.uiMode)
    val theme = Themes.of(shownMode(StoredThemeMode.SYSTEM, phoneIsDark))
    val handler = HomeIntentHandler(HomeScreen())
    return HomeHostView(
        context,
        handler,
        switch,
        modelReady,
        lastDictation,
        lastTranscript,
        transcriptTime,
        onCopy,
        onHistory,
        onSettings,
        theme,
        ScreenRenderer(context),
    )
}
