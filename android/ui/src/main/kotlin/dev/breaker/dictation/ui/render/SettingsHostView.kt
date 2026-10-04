package dev.breaker.dictation.ui.render

import android.content.Context
import android.widget.ScrollView
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.settings.Rendered
import dev.breaker.dictation.ui.screen.settings.SettingsIntentHandler
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeController

/*
 * The view the app puts on the display.
 *
 * The renderer draws one screen and hands back views that do not scroll, so
 * something has to hold them, and something has to decide when they are
 * replaced. That is this class: it is the scroll container, the place a click
 * on a row turns into an intent, and the place a theme change turns into a redraw.
 *
 * Every change replaces the whole child rather than editing rows in place. A row
 * has no field saying its label is out of date, so the only way to draw what is
 * true now is to ask the handler for the screen as it stands and draw that.
 * Replacing the child rather than the view is what keeps the scroll position and
 * the observer registration: both belong to the view, not to the rows.
 */

/**
 * A scrolling container that shows the settings screen and keeps it up to date.
 *
 * The rows are drawn by [renderer] and what they mean is decided by [handler];
 * this class only decides when they are drawn and when they are thrown away. A
 * click on an enabled action goes to [SettingsIntentHandler.handle] and the
 * result is drawn, and a theme change noticed by [themes] draws the handler's
 * current screen, so a choice made while the view was off screen is drawn when
 * it comes back.
 *
 * The listener is added when the view joins a window and taken away when it
 * leaves, so a view that is never attached registers nothing and cannot be
 * notified while it is off screen. See [ObserverBinding], which holds that rule.
 *
 * Everything here runs on the main thread: clicks, attachment and the calls into
 * the handler. There is no thread, no posted work and no lock.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param handler turns intents into the screen to draw next.
 * @param themes the theme choice, watched so a change can be drawn.
 * @param renderer draws a described screen into views.
 */
internal class SettingsHostView(
    context: Context,
    private val handler: SettingsIntentHandler,
    themes: ThemeController,
    private val renderer: ScreenRenderer,
) : ScrollView(context) {
    /**
     * The single redraw path.
     *
     * Held in a field rather than written at each use, because the controller
     * removes an observer by identity and a lambda built twice would never match
     * the one that was added.
     */
    private val binding = ObserverBinding(themes) { show(handler.current()) }

    init {
        // A screen is taller than a short window in landscape, and without this
        // the background of the child would stop short of the visible area.
        isFillViewport = true
        show(handler.current())
    }

    /**
     * Replaces the rows with [rendered], keeping the position on the screen.
     *
     * The position is read before the child goes away and put back afterwards,
     * so a change made at the bottom of a long screen does not throw the reader
     * back to the top. The background of the container itself follows the theme
     * too: what is behind a scroll that has reached its end would otherwise be
     * the window's own colour.
     */
    fun show(rendered: Rendered) {
        val held = scrollY
        removeAllViews()
        addView(renderer.render(rendered.screen, rendered.theme, ::onIntent))
        setBackgroundColor(rendered.theme.color(PaletteSlot.BACKGROUND).argb)
        scrollTo(0, held)
    }

    /**
     * Acts on [intent] and draws the result.
     *
     * A theme intent reaches the handler, which asks the controller; the
     * controller then notifies this binding, which draws the same screen once
     * more. Drawing twice costs one tree build and keeps every path that changes
     * the screen going through the same two lines.
     */
    private fun onIntent(intent: ScreenIntent) {
        show(handler.handle(intent))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        binding.attach()
        // Drawn again on attachment: the choice may have changed while this view
        // was off screen, and the tree that was built before that is not the one
        // that is true now.
        show(handler.current())
    }

    override fun onDetachedFromWindow() {
        binding.detach()
        super.onDetachedFromWindow()
    }
}