package dev.breaker.dictation.ui.render

import android.content.Context
import android.widget.FrameLayout
import dev.breaker.dictation.ui.SignInStartControl
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.signin.SignInStartIntentHandler
import dev.breaker.dictation.ui.screen.signin.SignInStartNodeIds
import dev.breaker.dictation.ui.screen.signin.SignInStartState
import dev.breaker.dictation.ui.screen.signin.SignInStartTexts
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/*
 * The view the app puts on the display for the sign-in start screen.
 *
 * It renders the kit nodes only: the server line, the one control, its hint and the state line when
 * there is one. It is a frame layout and not a scroll view, because the settings column scrolls it. The
 * server address is read again at each drawing and is never kept here. Nothing it draws holds a code,
 * a key or a link. Everything here runs on the main thread, as the auth host does.
 */
internal class SignInStartHostView(
    context: Context,
    private val theme: Theme,
    private val serverUrl: () -> String,
    private val handler: SignInStartIntentHandler,
) : FrameLayout(context), SignInStartControl {

    init {
        show()
    }

    /** Replaces the child with the screen for the state held, reading the server address at this drawing. */
    private fun show() {
        removeAllViews()
        val nodes = listOf(buildSignInStart(handler.current(), SignInStartTexts.serverLine(serverUrl())))
        addView(renderer.render(Screen("signin", SignInStartTexts.ACTION, nodes), theme, ::onIntent))
        setBackgroundColor(theme.color(PaletteSlot.BACKGROUND).argb)
    }

    /** A tap on the control goes to the handler, and the screen is drawn again from the state it holds. */
    private fun onIntent(intent: ScreenIntent) {
        if (intent is ScreenIntent.Setup && intent.action == SignInStartNodeIds.START) {
            handler.tap()
        }
        show()
    }

    override fun idle() {
        handler.change(SignInStartState.Idle)
        show()
    }

    override fun opening() {
        handler.change(SignInStartState.Opening)
        show()
    }

    override fun waiting() {
        handler.change(SignInStartState.Waiting)
        show()
    }

    override fun refused(noServer: Boolean) {
        handler.change(handler.refusalState(noServer))
        show()
    }

    override fun returned(finished: Boolean) {
        handler.change(handler.returnState(finished))
        show()
    }

    private val renderer get() = ScreenRenderer(context)
}

/** Builds the tree for [state]: the server line, the control, its hint, and the state line when there is one. */
private fun buildSignInStart(state: SignInStartState, serverLine: String): Box = Box(
    id = "signin",
    background = PaletteSlot.BACKGROUND,
    children = buildList {
        add(Label(SignInStartNodeIds.SERVER, serverLine, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
        add(Action(SignInStartNodeIds.START, SignInStartTexts.ACTION, intent = ScreenIntent.Setup(SignInStartNodeIds.START)))
        add(Label(SignInStartNodeIds.HINT, SignInStartTexts.HINT, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
        SignInStartTexts.stateLine(state)?.let { add(Label(SignInStartNodeIds.STATE, it, TypeRole.BODY, PaletteSlot.TEXT)) }
    },
)
