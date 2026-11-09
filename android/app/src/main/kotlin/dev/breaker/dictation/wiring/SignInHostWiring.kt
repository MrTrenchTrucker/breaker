package dev.breaker.dictation.wiring

import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.core.port.AuthService
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.host.MainLooperPost
import dev.breaker.dictation.host.OneShotBackground
import dev.breaker.dictation.ui.AuthControl

/**
 * The auth-side wiring: a submit handler over an AuthService that settles the session-live state and returns
 * the view to idle, and the host that shows the sign-in view on request. The submit runs off the main thread;
 * the state and the view updates come back through [main].
 */

/**
 * The submit handler: it hands the password array straight to the service, zeros it in its own finally so it is
 * cleared even if the service is replaced, settles the session-live state, and posts the result back on main.
 *
 * Takes a Background so the service call runs off the main thread: [main] posts only the updates that follow. On
 * success it sets the state live and posts submitted(register); on any Exception it sets the state not live and
 * posts submitted(register) so the view returns to idle on the same face; the failure never reaches the user as a
 * crash. The array the view hands over is passed straight to the service, copied or stored nowhere. [onSettled]
 * is told the new live state after each submit settles, on the main post.
 */
internal class SignInSubmitHandler(
    private val authService: AuthService,
    private val state: SessionLiveState,
    private val main: MainPost,
    private val background: Background,
) {
    /** The auth control the submit handler answers; set when a view is open, null while closed (no default). */
    var authControl: AuthControl? = null

    /** The settle callback the host holds, run on the main post after each submit settles, with the new live state. */
    var onSettled: ((Boolean) -> Unit)? = null

    fun submit(username: String, password: CharArray, register: Boolean) {
        background.submit {
            var live = false
            try {
                if (register) authService.register(username, password) else authService.login(username, password)
                live = true
            } catch (failure: Exception) {
                // The failure never reaches the user as a crash; the state and the view settle on the main post.
            } finally {
                password.fill('\u0000')
            }
            main.post {
                state.setLive(live)
                authControl?.submitted(register)
                onSettled?.invoke(live)
            }
        }
    }
}

/**
 * The app-side sign-in host: it shows the sign-in view on request, and the open and the close are the activity's
 * own functions held as references: [openScreen] shows the settings scroll and registers Back, [closeToHome]
 * restores the home through the settings close, so one path restores everything. It holds the auth control the
 * submit handler answers; it holds no other view. Main-thread only.
 */
internal class SignInHost(
    private val activity: android.app.Activity,
    private val settings: SettingsStore,
    private val submitHandler: SignInSubmitHandler,
    private val openScreen: () -> Unit,
    private val closeToHome: () -> Unit,
) {
    /** Runs the held open, then builds the sign-in view and shows it in place of the home. */
    fun open() {
        openScreen()
        val view = dev.breaker.dictation.ui.createAuthView(activity, settings, submitHandler::submit)
        submitHandler.authControl = view as AuthControl
        activity.setContentView(view)
        applyInsets(view)
    }

    /** Clears the held control and returns to the home through the activity's settings close. */
    fun close() {
        if (submitHandler.authControl == null) return
        submitHandler.authControl = null
        closeToHome()
    }
}

/**
 * The activity's one-line sign-in mount: the submit handler over the root's auth service and session-live state,
 * the host over the activity, and the settle callback that returns the user to the home when a sign-in succeeds.
 */
internal fun mountSignInHost(
    activity: android.app.Activity,
    root: BreakerCompositionRoot,
    openScreen: () -> Unit,
    closeToHome: () -> Unit,
): SignInHost {
    val handler = SignInSubmitHandler(root.authService, root.sessionLive, MainLooperPost(), OneShotBackground())
    val host = SignInHost(activity, root.settingsStore, handler, openScreen, closeToHome)
    handler.onSettled = { live -> if (live) host.close() }
    return host
}

/**
 * The one-line decision of when a finished take flips the session-live state. It is false exactly when [result] is a
 * Failed failure whose sentence equals one of the two fixed refusal texts, and nothing else: no isCommitted check, no
 * contains, no flip on null or any other result. The comparison is against the local constants, not the engine's object.
 */
fun onFinishResultFor(state: SessionLiveState, result: FinishResult?) {
    if (result !is FinishResult.Failed) return
    if (result.sentence == SessionRefusalSentences.KEY_REFUSED_TEXT ||
        result.sentence == SessionRefusalSentences.NO_KEY_TEXT
    ) state.setLive(false)
}
