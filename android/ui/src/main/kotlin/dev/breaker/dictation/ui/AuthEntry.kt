package dev.breaker.dictation.ui

import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.ui.render.AuthHostView
import dev.breaker.dictation.ui.render.SignInStartHostView
import dev.breaker.dictation.ui.screen.auth.AuthIntentHandler
import dev.breaker.dictation.ui.screen.signin.SignInStartIntentHandler
import dev.breaker.dictation.ui.theme.isNightMode
import dev.breaker.dictation.ui.theme.storedTheme

/*
 * The auth screen's way in.
 *
 * The app owns the activity, the manifest entry and the settings store; this module owns no window
 * and no storage. What is built here is held by the returned view, which is what lets the whole
 * module ship without an activity of its own and without a reference that outlives the window it was
 * made for. This screen has a real user name box and a real password box, wires nothing beyond the
 * callback passed in, and keeps no password past submit: the password box is wiped at every submit.
 */

/**
 * Builds the auth screen as a view for [context].
 *
 * The screen shows a username box the user types in, a masked password box the user types in, the
 * register/login choice, the busy/refused/weak-warning line and the submit control. The app hands in
 * the answer that runs when the user submits: it gets the typed username and password (the password
 * as an array it owns and must zero) and the chosen face. What is built here is held by the returned
 * view; no platform timer drives this screen.
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

/**
 * Builds the sign-in start view for [context]: the server line, the one control that opens the website,
 * its hint and the state line.
 *
 * The control asks the app to open the website once per tap that starts a sign-in. [serverUrl] is read
 * again at every drawing and is never kept by the view.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param settings the shared store, through which this screen reads its stored theme.
 * @param serverUrl the server address the app holds now, read again at every drawing.
 * @param onStart asks the app to open the website, once per tap that starts a sign-in.
 * @return a view that shows the sign-in start screen and answers [SignInStartControl].
 */
fun createSignInStartView(context: android.content.Context, settings: dev.breaker.dictation.core.port.SettingsStore, serverUrl: () -> String, onStart: () -> Unit): android.view.View {
    val theme = storedTheme(settings, isNightMode(context.resources.configuration.uiMode))
    val handler = SignInStartIntentHandler(onStart)
    return SignInStartHostView(context, theme, serverUrl, handler)
}

/** What the view from [createAuthView] answers to the app. */
interface AuthControl {
    /** Re-reads the screen's current state and draws it again. */
    fun refresh()

    /** The app's submit attempt has finished; the view returns to idle keeping the same face. */
    fun submitted(register: Boolean)
}

/** What the view from [createSignInStartView] answers to the app. */
interface SignInStartControl {
    /** Shows the idle state: the control is ready to open the website. */
    fun idle()

    /** Shows that the browser is being opened. */
    fun opening()

    /** Shows that the website is open and the return from it is awaited. */
    fun waiting()

    /** Shows a refusal: [noServer] is true when no server address is set, false when the address cannot be opened. */
    fun refused(noServer: Boolean)

    /** Shows the return from the website: [finished] is true when the sign-in is finished, false when it is not. */
    fun returned(finished: Boolean)
}
