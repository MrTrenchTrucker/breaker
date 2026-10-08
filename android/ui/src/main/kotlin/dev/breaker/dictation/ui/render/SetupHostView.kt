package dev.breaker.dictation.ui.render

import android.content.Context
import android.widget.ScrollView
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.onboarding.SETUP_NOTICE_ID
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/*
 * The view the app puts on the display for the setup steps.
 *
 * The renderer draws one screen into views that do not scroll, so something has
 * to hold them and decide when they are replaced. That is this class, the same
 * job SettingsHostView does for the settings screen, with one difference: what
 * the setup screen shows is decided outside the app, by the user, on system pages
 * and in permission dialogs. So the screen is drawn again whenever the window
 * gets focus back, which is what a page or a dialog closing does.
 */

/**
 * A scrolling container that shows the setup screen and keeps it up to date.
 *
 * The rows are drawn by [renderer] and what they mean is decided by [handler]. A
 * tap on an enabled action goes to [SetupIntentHandler.handle] and the result is
 * drawn. The handler's current screen is drawn when the view joins a window and
 * each time the window gains focus, so a permission granted on a system page, or
 * Breaker switched off from elsewhere, is shown as soon as the user is back.
 *
 * Every change replaces the whole child rather than editing rows in place, so the
 * scroll position stays with this view and not with the rows; [show] says when it
 * is put back and when the view goes to the top.
 *
 * Everything here runs on the main thread: taps, attachment and focus changes.
 * There is no thread, no posted work and no lock.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param handler turns intents into the screen to draw next.
 * @param theme the colours and sizes the screen is drawn in.
 * @param renderer draws a described screen into views.
 */
internal class SetupHostView(
    context: Context,
    private val handler: SetupIntentHandler,
    private val theme: Theme,
    private val renderer: ScreenRenderer,
) : ScrollView(context) {
    init {
        // A screen is taller than a short window in landscape, and without this
        // the background of the child would stop short of the visible area.
        isFillViewport = true
        show(handler.current())
    }

    /**
     * Replaces the rows with [screen], keeping the position on the screen.
     *
     * The position is read before the child goes away and asked for again
     * afterwards, and the platform limits it to the height of the new content.
     * The one exception is a screen that holds the message for an action that did
     * not work: it is the first row under the title, so the view goes to the top,
     * and the message is in sight whatever part of the page the tap was on. Every
     * other drawing, including the ones on attachment and on gaining focus, asks
     * for the held position back in the same way.
     */
    fun show(screen: Screen) {
        val held = if (screen.nodes.any { it.id == SETUP_NOTICE_ID }) 0 else scrollY
        removeAllViews()
        addView(renderer.render(screen, theme, ::onIntent))
        setBackgroundColor(theme.color(PaletteSlot.BACKGROUND).argb)
        scrollTo(0, held)
    }

    /** Acts on [intent] and draws the result. */
    private fun onIntent(intent: ScreenIntent) {
        show(handler.handle(intent))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Drawn again on attachment: what the phone grants may have changed while
        // this view was off screen.
        show(handler.current())
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            show(handler.current())
        }
    }
}
