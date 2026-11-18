package dev.breaker.dictation.ui.screen.about

import android.content.Context
import android.view.ViewGroup
import android.widget.ScrollView
import dev.breaker.dictation.ui.AboutControl
import dev.breaker.dictation.ui.render.ScreenRenderer
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * The view the app puts on the display for the About screen.
 *
 * A scroll view that answers [AboutControl]: it holds the last [AboutState] the app handed in and
 * the two callbacks [bind] stored, and every drawing renders the screen from the held state. A
 * redraw removes the child it drew before adding the new one, so the rows and the sentence follow
 * the app's facts. A tap on a row goes to the callback the app bound; before [bind] there is none,
 * so a tap does nothing.
 *
 * Everything here runs on the main thread: taps and drawings. There is no thread, no posted work
 * and no lock.
 */
internal class AboutHostView(
    context: Context,
    private val screen: AboutScreen,
    private val theme: Theme,
    private val renderer: ScreenRenderer,
) : ScrollView(context), AboutControl {

    /** The state last drawn, carried into the next drawing so a redraw shows what the app last said. */
    private var state: AboutState = AboutState()

    /** The two callbacks a row tap calls, set by [bind] and absent before it. */
    private var onCheck: () -> Unit = {}
    private var onInstall: () -> Unit = {}

    init {
        isFillViewport = true
        draw()
    }

    /** Stores the two callbacks a row tap calls. A tap before this call does nothing. */
    override fun bind(onCheck: () -> Unit, onInstall: () -> Unit) {
        this.onCheck = onCheck
        this.onInstall = onInstall
    }

    /** Stores the app's seven plain facts as the state and draws the screen from it. */
    override fun show(admin: Boolean, updateAvailable: Boolean, installReady: Boolean, checking: Boolean, installing: Boolean, failed: Boolean, upToDate: Boolean) {
        state = AboutState(admin, updateAvailable, installReady, checking, installing, failed, upToDate)
        draw()
    }

    /** Redraws the screen from the last facts the app handed in. */
    override fun refresh() {
        draw()
    }

    /** Removes the child drawn before, then renders the held state and adds the new child. */
    private fun draw() {
        removeAllViews()
        val rendered = renderer.render(screen.render(state), theme, ::onIntent)
        addView(rendered, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setBackgroundColor(theme.color(PaletteSlot.BACKGROUND).argb)
    }

    /** Hands a row tap to the callback the app bound for the action it reports. */
    private fun onIntent(intent: ScreenIntent) {
        if (intent !is ScreenIntent.About) return
        if (!AboutIntentHandler.accepts(intent.action, state)) return
        when (intent.action) {
            AboutActions.CHECK -> onCheck()
            AboutActions.INSTALL -> onInstall()
        }
    }
}
