package dev.breaker.dictation.ui.render

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.breaker.dictation.ui.AuthControl
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.auth.AuthIntentHandler
import dev.breaker.dictation.ui.screen.auth.AuthNodeIds
import dev.breaker.dictation.ui.screen.auth.AuthState
import dev.breaker.dictation.ui.screen.auth.AuthTexts
import dev.breaker.dictation.ui.screen.auth.submitThenWipe
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/*
 * The view the app puts on the display for the auth screen.
 *
 * It holds two real text boxes, one for the user name and one for the password, built once and kept
 * in a column between three holders. A redraw replaces only what the kit draws in the holders (the
 * captions, the face toggle, the submit control and the state line) and never touches the two boxes,
 * so a face toggle keeps the typed text. The submit control copies the user name into the handler,
 * takes the typed password as a new array, hands it to the handler, and then always wipes the
 * password box, whether the answer was a refusal or not. The user name stays. The array handed over
 * is never reused by this view: the handler zeroes it and the callback gets its own copy.
 *
 * Everything here runs on the main thread: taps, attachment and focus changes. There is no thread,
 * no posted work and no lock.
 */
internal class AuthHostView(
    context: Context,
    private val theme: Theme,
    private val handler: AuthIntentHandler,
) : ScrollView(context), AuthControl {

    /** The state last drawn, carried into the next drawing so taps read what is on screen. */
    private var drawn: AuthState = handler.current()

    private val density: Float = context.resources.displayMetrics.density
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topHolder = FrameLayout(context)
    private val userField = UsernameEntryView(context, theme, AuthTexts.USERNAME_LABEL)
    private val middleHolder = FrameLayout(context)
    private val passField = PasswordEntryView(context, theme, AuthTexts.PASSWORD_CAPTION)
    private val bottomHolder = FrameLayout(context)

    init {
        column.addView(topHolder)
        column.addView(userField, inputParams())
        column.addView(middleHolder)
        column.addView(passField, inputParams())
        column.addView(bottomHolder)
        addView(column)
        openForBleed()
        show(drawn)
    }

    /** Full width, as tall as the box, with one step S of the scale below it. */
    private fun inputParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, WRAP).apply {
            bottomMargin = (spaceDp(theme.metrics, Space.S) * density).toInt()
        }

    /** Redraws the three kit segments from [state], keeping the scroll position and both text boxes. */
    private fun show(state: AuthState) {
        val held = scrollY
        val title = titleOf(state)
        place(topHolder, title, topSegment())
        place(middleHolder, title, middleSegment())
        place(bottomHolder, title, bottomSegment(state))
        setBackgroundColor(theme.color(PaletteSlot.BACKGROUND).argb)
        scrollTo(0, held)
    }

    /** Replaces what [holder] shows with the freshly rendered [segment]. */
    private fun place(holder: FrameLayout, title: String, segment: Box) {
        holder.removeAllViews()
        holder.addView(
            renderer.render(Screen("auth", title, listOf(segment)), theme, ::onIntent),
            FrameLayout.LayoutParams(MATCH, WRAP),
        )
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

    /**
     * Hands the typed user name and password to the handler, in this order, then wipes the password
     * box, also when the submit callback throws, and redraws from the state the handler holds. The
     * array is not read or changed here after the handler has it.
     */
    private fun submitNow() {
        handler.username = userField.typedName()
        submitThenWipe(passField.takeTyped(), handler::submit) { passField.wipeTyped() }
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

/** The layout size that fills the width. */
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** The layout size that wraps the content. */
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** The kit nodes above the user name box: the idle caption and the user name caption. */
private fun topSegment(): Box = Box(
    id = "auth.top",
    background = PaletteSlot.BACKGROUND,
    children = listOf(
        Label("auth.idle", AuthTexts.IDLE, TypeRole.BODY, PaletteSlot.TEXT),
        Label("auth.username", AuthTexts.USERNAME_LABEL, TypeRole.BODY, PaletteSlot.TEXT_MUTED),
    ),
)

/** The kit node between the two boxes: the password caption. */
private fun middleSegment(): Box = Box(
    id = "auth.middle",
    background = PaletteSlot.BACKGROUND,
    children = listOf(
        Label("auth.password", AuthTexts.PASSWORD_CAPTION, TypeRole.BODY, PaletteSlot.TEXT_MUTED),
    ),
)

/** The kit nodes below the password box: the face toggle, the submit control and the state line. */
private fun bottomSegment(state: AuthState): Box {
    val register = state.register
    return Box(
        id = "auth.bottom",
        background = PaletteSlot.BACKGROUND,
        children = buildList {
            if (register) {
                val face = ScreenIntent.Setup(AuthNodeIds.FACE_REGISTER)
                add(Action("face.register", AuthTexts.FACE_REGISTER, intent = face))
            } else {
                val face = ScreenIntent.Setup(AuthNodeIds.FACE_LOGIN)
                add(Action("face.login", AuthTexts.FACE_LOGIN, intent = face))
            }
            val submitText = if (register) AuthTexts.SUBMIT_REGISTER else AuthTexts.SUBMIT_LOGIN
            add(Action("submit", submitText, intent = ScreenIntent.Setup(AuthNodeIds.SUBMIT)))
            when (state) {
                is AuthState.Refused ->
                    add(Label("auth.line", AuthTexts.REFUSED, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
                is AuthState.Warning ->
                    add(Label("auth.line", AuthTexts.WEAK_WARNING, TypeRole.BODY, PaletteSlot.ACCENT))
                else -> {}
            }
        },
    )
}
