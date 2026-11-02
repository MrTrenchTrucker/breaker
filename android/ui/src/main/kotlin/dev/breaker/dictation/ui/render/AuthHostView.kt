package dev.breaker.dictation.ui.render

import android.content.Context
import android.widget.ScrollView
import dev.breaker.dictation.ui.AuthControl
import dev.breaker.dictation.ui.render.ScreenRenderer
import dev.breaker.dictation.ui.render.openForBleed
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.auth.AuthIntentHandler
import dev.breaker.dictation.ui.screen.auth.AuthNodeIds
import dev.breaker.dictation.ui.screen.auth.AuthState
import dev.breaker.dictation.ui.screen.auth.AuthTexts
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/*
 * The view the app puts on the display for the auth screen.
 *
 * It renders the kit nodes only, because this phase carries the state-driven surface, not input:
 * there is no real text field to type into and no platform timer here. The username field stays
 * blank until the submit control runs; the password field is drawn as dots, never from typed text;
 * the register/login face toggles the choice; and the busy/refused/weak-warning line follows the
 * state. The host draws the tree the handler holds, dispatches each tap into it, and keeps no copy
 * of the password past submit. No typed input reaches the app here: the submit control hands an
 * empty array, so its answer is Refused until a typing slice lands on the screen.
 *
 * Everything here runs on the main thread: taps, attachment and focus changes. There is no thread,
 * no posted work and no lock; a change replaces the whole child rather than editing rows in place.
 */
internal class AuthHostView(
    context: Context,
    private val theme: Theme,
    private val handler: AuthIntentHandler,
) : ScrollView(context), AuthControl {

    /** The state last drawn, carried into the next drawing so taps read what is on screen. */
    private var drawn: AuthState = handler.current()


    init {
        openForBleed()
        show(drawn)
    }

    /** Replaces the child with [state], keeping scroll position and redrawing from the state held. */
    private fun show(state: AuthState) {
        val held = scrollY
        removeAllViews()
        addView(renderer.render(Screen("auth", titleOf(state), buildList { add(buildAuthState(state)) }), theme, ::onIntent))
        setBackgroundColor(theme.color(PaletteSlot.BACKGROUND).argb)
        scrollTo(0, held)
    }

    /** The screen's title from the state: idle caption, refused/warning line, or busy word. */
    private fun titleOf(state: AuthState): String = when (state) {
        is AuthState.Idle -> AuthTexts.IDLE
        is AuthState.Refused -> AuthTexts.REFUSED
        is AuthState.Warning -> AuthTexts.WEAK_WARNING
        is AuthState.Busy -> AuthTexts.BUSY
    }

    /** Dispatches a setup action into the handler, redraws it, and handles submit. */
    private fun onIntent(intent: ScreenIntent) {
        if (intent is ScreenIntent.Setup) {
            when (intent.action) {
                AuthNodeIds.FACE_REGISTER, AuthNodeIds.FACE_LOGIN -> {
                    drawn = handler.tap(intent.action)
                    show(drawn)
                }
                AuthNodeIds.SUBMIT -> submitNow()
                else -> show(handler.current())
            }
        } else {
            show(handler.current())
        }
    }

    /** The submit control hands an empty password array to the handler, then redraws from the face now held. */
    private fun submitNow() {
        val password = CharArray(0)
        handler.submit(password)
        drawn = handler.current()
        show(drawn)
    }

    override fun refresh() {
        drawn = handler.current()
        show(drawn)
    }

    /** The app's submit attempt has finished; the view returns to idle keeping the same face. */
    override fun submitted(register: Boolean) {
        drawn = handler.submitted()
        show(drawn)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        drawn = handler.current()
        show(drawn)
    }

    private val renderer get() = ScreenRenderer(context)
}

/** Builds the tree for [state]: the page background, the two fields, the face row and the submit control. */
private fun buildAuthState(state: AuthState): Box {
    val register = state.register
    return Box(
        id = "auth",
        background = PaletteSlot.BACKGROUND,
        children = buildList {
            add(Label("auth.idle", AuthTexts.IDLE, TypeRole.BODY, PaletteSlot.TEXT))
            add(Label("auth.username", AuthTexts.USERNAME_LABEL, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
            add(Label("auth.password", AuthTexts.PASSWORD_CAPTION, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
            if (register) {
                add(Action("face.register", AuthTexts.FACE_REGISTER, intent = ScreenIntent.Setup(AuthNodeIds.FACE_REGISTER)))
            } else {
                add(Action("face.login", AuthTexts.FACE_LOGIN, intent = ScreenIntent.Setup(AuthNodeIds.FACE_LOGIN)))
            }
            val submitText = if (register) AuthTexts.SUBMIT_REGISTER else AuthTexts.SUBMIT_LOGIN
            add(Action("submit", submitText, intent = ScreenIntent.Setup(AuthNodeIds.SUBMIT)))
            when (state) {
                is AuthState.Refused -> add(Label("auth.line", AuthTexts.REFUSED, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
                is AuthState.Warning -> add(Label("auth.line", AuthTexts.WEAK_WARNING, TypeRole.BODY, PaletteSlot.ACCENT))
                else -> {}
            }
        },
    )
}
