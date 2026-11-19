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
import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.settings.ModelRow
import dev.breaker.dictation.ui.screen.settings.ModelRowItem
import dev.breaker.dictation.ui.screen.settings.Rendered
import dev.breaker.dictation.ui.screen.settings.modelControlId
import dev.breaker.dictation.ui.screen.settings.SettingsIds
import dev.breaker.dictation.ui.screen.settings.SettingsIntentHandler
import dev.breaker.dictation.ui.screen.settings.ServerAddressStatus
import dev.breaker.dictation.ui.screen.home.HomeActions
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
 * @param sessionLive answers whether the session is live; the signed-out block shows when not.
 * @param onSignIn asks the app to start signing in; called when the signed-out block's Sign in is tapped.
 * @param serverAddressStatus answers the app's reading of the stored server addresses, or null for no line; read once per drawing.
 * @param offeredModels answers the offered speech models, with their id, label, rating and size; the app passes them, since this module does not read the settings module. Empty keeps the one Download button.
 * @param installedModel answers whether the model with that id is on the phone.
 * @param heldModelId answers the id of the model in use (the app's stored choice), or null when none is held.
 * @param downloadingModelId answers the id of the model that is downloading now, or null.
 * @param onDownloadModelId asks the app to download the model with that id.
 * @param onDeleteModel asks the app to delete the model with that id; the model in use is never passed.
 * @param onUseModel asks the app to save the model with that id as the model in use.
 */
internal class SettingsHostView(
    context: Context,
    private val handler: SettingsIntentHandler,
    private val themes: ThemeController,
    private val renderer: ScreenRenderer,
    private val modelReady: () -> Boolean,
    private val downloadLine: () -> String?,
    private val onDownloadModel: () -> Unit,
    private val sessionLive: () -> Boolean,
    private val onSignIn: () -> Unit,
    private val serverAddressStatus: (() -> ServerAddressStatus)?,
    private val offeredModels: () -> List<ModelRowItem> = { emptyList() },
    private val installedModel: (String) -> Boolean = { false },
    private val heldModelId: () -> String? = { null },
    private val downloadingModelId: () -> String? = { null },
    private val onDownloadModelId: (String) -> Unit = {},
    private val onDeleteModel: (String) -> Unit = {},
    private val onUseModel: (String) -> Unit = {},
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
        handler.showingSignedOut(signedOut())
        handler.showingServerAddressStatus(serverAddressStatus?.invoke())
        return handler.current()
    }

    /** Whether the signed-out block should show, re-read on every drawing. */
    private fun signedOut(): Boolean = !sessionLive()

    /** The speech model row as it stands: whether the model is ready, the sentence for a download, and one row per offered model. */
    private fun rowNow(): ModelRow = ModelRow(ready = modelReady(), line = downloadLine(), rows = modelRowsNow())

    /**
     * One row per offered model: the app's facts about it (id, label, rating, size) with whether it is on the
     * phone, whether it is the model in use, and the download sentence under the model that is downloading.
     */
    private fun modelRowsNow(): List<ModelRowItem> {
        val inUse = heldModelId()
        val downloading = downloadingModelId()
        return offeredModels().map { offered ->
            offered.copy(
                installed = installedModel(offered.id),
                inUse = offered.id == inUse,
                line = if (offered.id == downloading) downloadLine() else null,
            )
        }
    }

    /** The button the progress bar sits under: the downloading model's button, or the one button of the single-model row. */
    private fun downloadingButtonId(): String =
        downloadingModelId()?.let { modelControlId(it, "download") } ?: SettingsIds.DOWNLOAD_ID

    /** Puts the download bar right after the download button, when the button is in the drawn column. */
    private fun placeBar(column: View) {
        val button = findTagged(column, downloadingButtonId()) ?: return
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
     * The download button asks the app to download the model. The signed-out block's
     * Sign in intent is intercepted here and answered with [onSignIn]; every other
     * intent goes to the handler, and the screen it answers is drawn.
     */
    private fun onIntent(intent: ScreenIntent) {
        when {
            intent == ScreenIntent.Back -> (context as? android.app.Activity)?.onBackPressed()
            intent is ScreenIntent.Setup && intent.action == SettingsIds.DOWNLOAD_ACTION -> onDownloadModel()
            intent is ScreenIntent.Setup && intent.action == ModelActions.MODEL_DOWNLOAD -> onDownloadModelId(intent.id)
            // A delete of the model in use is not passed on: it falls through to the handler, which refuses it.
            intent is ScreenIntent.Setup && intent.action == ModelActions.MODEL_DELETE && intent.id != heldModelId() -> onDeleteModel(intent.id)
            intent is ScreenIntent.Setup && intent.action == ModelActions.MODEL_USE -> {
                onUseModel(intent.id)
                refresh()
            }
            intent is ScreenIntent.Home && intent.action == HomeActions.SIGN_IN -> onSignIn()
            else -> {
                handler.showing(rowNow())
                handler.showingSignedOut(signedOut())
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
        val id = downloadControlIdFor(modelRowsNow(), heldModelId()) ?: return false
        if (findTagged(this, id) == null) return false
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
        val id = downloadControlIdFor(modelRowsNow(), heldModelId()) ?: return
        val button = findTagged(this, id) ?: return
        button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
    }
}

/**
 * The id of the download control that focus and reveal go to: with rows, the selected model's control when that
 * model is not on the phone, else the first offered model that is not on the phone, and null when every offered
 * model is on the phone. With no rows it is the one id of the single download button.
 */
internal fun downloadControlIdFor(rows: List<ModelRowItem>, selectedId: String?): String? {
    if (rows.isEmpty()) return SettingsIds.DOWNLOAD_ID
    val chosen = rows.firstOrNull { it.id == selectedId && !it.installed } ?: rows.firstOrNull { !it.installed }
    return chosen?.let { modelControlId(it.id, "download") }
}
