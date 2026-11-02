package dev.breaker.dictation.ui

import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.ui.render.AuthHostView
import dev.breaker.dictation.ui.screen.auth.AuthIntentHandler
import dev.breaker.dictation.ui.theme.isNightMode
import dev.breaker.dictation.ui.theme.storedTheme

/*
 * The auth screen's way in.
 *
 * The app owns the activity, the manifest entry and the settings store; this module owns no window
 * and no storage. What is built here is held by the returned view, which is what lets the whole
 * module ship without an activity of its own and without a reference that outlives the window it was
 * made for. This screen carries only the state-driven surface: it never types, wires nothing beyond
 * the callback passed in, and keeps no password past submit.
 */

/**
 * Builds the auth screen as a view for [context].
 *
 * The screen shows a username field, a masked password field, the register/login choice, the
 * busy/refused/weak-warning line and the submit control. The app hands in the answer that runs when
 * the user submits: it gets the username and password (the password as an array it can zero) and the
 * chosen face. What is built here is held by the returned view; no platform timer drives this phase.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param settings the shared store, through which this screen reads its one stored value.
 * @param onSubmit asks the app to submit with the username and password chosen from the faces.
 * @return a view that shows the auth screen and can be put in a layout as it stands.
 */
fun createAuthView(
    context: android.content.Context,
    settings: dev.breaker.dictation.core.port.SettingsStore,
    onSubmit: (username: String, password: CharArray, register: Boolean) -> Unit,
): android.view.View {
    val theme = storedTheme(settings, isNightMode(context.resources.configuration.uiMode))
    val handler = AuthIntentHandler(onSubmit)
    return AuthHostView(context, theme, handler)
}

/** What the view from [createAuthView] answers to the app. */
interface AuthControl {
    /** Re-reads the screen's current state and draws it again. */
    fun refresh()

    /** The app's submit attempt has finished; the view returns to idle keeping the same face. */
    fun submitted(register: Boolean)
}
