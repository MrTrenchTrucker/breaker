package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.render.HomeHostView
import dev.breaker.dictation.ui.render.ScreenRenderer
import dev.breaker.dictation.ui.screen.home.HomeIntentHandler
import dev.breaker.dictation.ui.screen.home.HomeScreen
import dev.breaker.dictation.ui.theme.isNightMode
import dev.breaker.dictation.ui.theme.storedTheme
import dev.breaker.dictation.core.port.SettingsStore

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
 * The theme this screen draws in comes from the shared reader [storedTheme],
 * so it stays with the stored choice and does not read the phone's own mode
 * again for its colour here. A mode that reports neither night nor day still
 * counts as light when it is resolved.
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
 * @param settings the shared store, through which this screen's theme is read
 *   once here instead of reading the phone's own mode again.
 * @param onTap asks the app to start a take from the big mic on the screen.
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
    onTap: () -> Unit,
    settings: dev.breaker.dictation.core.port.SettingsStore,
): android.view.View {
    val theme = storedTheme(settings, isNightMode(context.resources.configuration.uiMode))
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
        onTap,
        theme,
        ScreenRenderer(context),
    )
}

/**
 * What the view from [createHomeView] answers to the app: the line that is drawn below
 * the last-transcript box, and whether the big mic shows the recording look. Both are called on the
 * main thread; each stores its value and draws the screen again.
 */
interface HomeControl {
    /** Shows [line] below the last-transcript box, or nothing when it is null. */
    fun showLine(line: String?)

    /** Shows the recording look on the big mic while [recording] is true. */
    fun showRecording(recording: Boolean)
}
