package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.breaker.dictation.ui.DownloadControl
import dev.breaker.dictation.ui.render.controls.DownloadBarView
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.settings.ModelRow
import dev.breaker.dictation.ui.screen.settings.Rendered
import dev.breaker.dictation.ui.screen.settings.SettingsIds
import dev.breaker.dictation.ui.screen.settings.SettingsIntentHandler
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.ui.theme.Themes

/*
 * The view the app puts on the display.
 *
 * The renderer draws one screen and hands back views that do not scroll, so
 * something has to hold them, and something has to decide when they are
 * replaced. That is this class: it is the scroll container, the place a click
 * on a row turns into an intent, and the place a theme change turns into a redraw.
 *
 * Every change replaces the whole child rather than editing rows in place, so the
 * only way to draw what is true now is to ask the handler for the screen as it
 * stands and draw that. Replacing the child rather than the view is what keeps the
 * scroll position and the observer registration: both belong to the view.
 *
 * The model download bar is a view, not a node, so this class places it under the
 * download button while a download runs. The download state (the ModelDownloadNotice
 * callbacks) wires to this control through [DownloadControl].
 *
 * Everything here runs on the main thread: clicks, attachment and the calls into
 * the handler. There is no thread, no posted work and no lock.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param handler turns intents into the screen to draw next.
 * @param themes the theme choice, watched so a change can be drawn.
 * @param renderer draws a described screen into views.
 * @param modelReady answers whether the speech model is on the phone right now.
 * @param downloadLine answers the sentence to show under the model download, or null.
 * @param onDownloadModel asks the app to download the speech model.
 */
internal class SettingsHostView(
    context: Context,
    private val handler: SettingsIntentHandler,
    private val themes: ThemeController,
    private val renderer: ScreenRenderer,
    private val modelReady: () -> Boolean,
    private val downloadLine: () -> String?,
    private val onDownloadModel: () -> Unit,
) : ScrollView(context), DownloadControl {
    /**
     * The single redraw path.
     *
     * Held in a field rather than written at each use, because the controller
     * removes an observer by identity and a lambda built twice would never match
     * the one that was added.
     */
    private val binding = ObserverBinding(themes) { show(refreshed()) }

    private val downloadBar: DownloadBarView = DownloadBarView(context, Themes.of(themes.state.shown))

    /** Whether a download runs, so that the bar is placed under the download button. */
    private var running: Boolean = false

    init {
        // The content reaches past the padding for the rule and the stripes, so the
        // padding must not clip it.
        openForBleed()
        show(refreshed())
    }

    /**
     * Replaces the rows with [rendered], keeping the position on the screen.
     *
     * The position is read before the child goes away and put back afterwards; the
     * platform clamps it when the new content is shorter. The background of the
     * container itself follows the theme too.
     */
    internal fun show(rendered: Rendered) {
        val held = scrollY
        removeAllViews()
        val column = renderer.render(rendered.screen, rendered.theme, ::onIntent)
        addView(column)
        downloadBar.setTheme(rendered.theme)
        if (running) placeBar(column)
        setBackgroundColor(rendered.theme.color(PaletteSlot.BACKGROUND).argb)
        scrollTo(0, held)
    }

    /** The speech model row and the screen as the handler draws them now. */
    private fun refreshed(): Rendered {
        handler.showing(rowNow())
        return handler.current()
    }

    /** The speech model row as it stands: whether the model is ready, and the sentence for a download. */
    private fun rowNow(): ModelRow = ModelRow(ready = modelReady(), line = downloadLine())

    /** Puts the download bar right after the download button, when the button is in the drawn column. */
    private fun placeBar(column: View) {
        val button = findTagged(column, SettingsIds.DOWNLOAD_ID) ?: return
        val row = button.parent as? LinearLayout ?: return
        (downloadBar.parent as? ViewGroup)?.removeView(downloadBar)
        row.addView(
            downloadBar,
            row.indexOfChild(button) + 1,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    /**
     * Acts on [intent] and draws the result.
     *
     * Back goes to the activity, which closes the screen the way its own Back does.
     * The download button asks the app to download the model. Any other intent goes
     * to the handler, and the screen it answers is drawn.
     */
    private fun onIntent(intent: ScreenIntent) {
        when {
            intent == ScreenIntent.Back -> (context as? android.app.Activity)?.onBackPressed()
            intent is ScreenIntent.Setup && intent.action == SettingsIds.DOWNLOAD_ACTION -> onDownloadModel()
            else -> {
                handler.showing(rowNow())
                show(handler.handle(intent))
            }
        }
    }

    /** Shows download progress: the bar moves, and the line under the button is read again. */
    override fun downloadProgress(doneMb: Int, totalMb: Int) {
        running = true
        downloadBar.setProgress(doneMb, totalMb)
        show(refreshed())
    }

    /** Shows the checking state on the download bar, with the checking line. */
    override fun downloadChecking() {
        running = true
        downloadBar.setChecking()
        show(refreshed())
    }

    /** Clears the download bar, and the line once the download has ended. */
    override fun downloadStopped() {
        running = false
        downloadBar.setProgress(0, 0)
        show(refreshed())
    }

    /** Re-reads the settings and redraws; the settings has no walk-through step. */
    override fun refresh() {
        show(refreshed())
    }

    /**
     * Scrolls to the model download button and answers true when it is showing.
     *
     * The button is drawn only while the speech model is not ready, so the answer is
     * false when the model is ready and there is no button to focus. The answer is decided
     * now; the scroll itself waits for the first layout, because a button that has not been
     * laid out has no size to scroll to.
     */
    override fun focusDownload(): Boolean {
        if (findTagged(this, SettingsIds.DOWNLOAD_ID) == null) return false
        if (isLaidOut) {
            revealDownload()
        } else {
            val observer = viewTreeObserver
            observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    observer.removeOnGlobalLayoutListener(this)
                    revealDownload()
                }
            })
        }
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        binding.attach()
        // Drawn again on attachment: the choice may have changed while this view
        // was off screen, and the tree that was built before that is not the one
        // that is true now.
        show(refreshed())
    }

    override fun onDetachedFromWindow() {
        binding.detach()
        super.onDetachedFromWindow()
    }

    /** Scrolls the download button into view, measured now that the view has been laid out. */
    private fun revealDownload() {
        val button = findTagged(this, SettingsIds.DOWNLOAD_ID) ?: return
        button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
    }
}
