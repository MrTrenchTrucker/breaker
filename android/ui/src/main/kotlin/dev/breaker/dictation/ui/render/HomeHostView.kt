package dev.breaker.dictation.ui.render

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import dev.breaker.dictation.ui.BreakerSwitch
import dev.breaker.dictation.ui.HomeControl
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.home.HomeActions
import dev.breaker.dictation.ui.screen.home.HomeIntentHandler
import dev.breaker.dictation.ui.screen.home.HomeNodeIds
import dev.breaker.dictation.ui.screen.home.HomeState
import dev.breaker.dictation.ui.screen.home.HomeTexts
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/*
 * The view the app puts on the display for the home screen.
 *
 * It does the job SetupHostView does for the setup steps, with one difference:
 * where the screen's facts come from. The setup screen's are the handler's own,
 * reached through the platform and the switch it was given; the home screen's are
 * the app's, and this module keeps no store of them and core has no port to read
 * them through. So the app offers them as functions: whether Breaker is on
 * through the same BreakerSwitch the setup screen is given, the rest as answers
 * pulled again on every drawing, and this view pulls them and hands them to the
 * handler as one state.
 *
 * The drawer is the one part of that state the screen owns itself, and the
 * handler owns it: the menu toggles it and a drawer item closes it. The view
 * cannot read the handler's state, so after each intent it reads the drawer's new
 * state off the screen the handler drew and carries it into the next drawing, so
 * pushing the app's fresh facts does not close a drawer the user opened.
 *
 * Every change replaces the whole child rather than editing rows in place, so the
 * scroll position stays with this view and not with the rows.
 *
 * Everything here runs on the main thread: taps, attachment and focus changes.
 * There is no thread, no posted work and no lock.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param handler turns intents into the screen to draw next and owns the drawer.
 * @param switch the switch the app offers for turning Breaker on and off.
 * @param modelReady answers whether the speech model is on the phone right now.
 * @param lastDictation answers the last thing that was dictated, or null.
 * @param lastTranscript answers the last transcript to copy, or null.
 * @param transcriptTime answers the phone's own short time for the transcript.
 * @param onCopy asks the app to copy the transcript on display to the clipboard.
 * @param onHistory asks the app to show the history screen.
 * @param onSettings asks the app to show the settings screen.
 * @param onTap asks the app to start a take from the big mic on the screen.
 * @param theme the colours and sizes the screen is drawn in.
 * @param renderer draws a described screen into views.
 */
internal class HomeHostView(
    context: Context,
    private val handler: HomeIntentHandler,
    private val switch: BreakerSwitch,
    private val modelReady: () -> Boolean,
    private val lastDictation: () -> String?,
    private val lastTranscript: () -> String?,
    private val transcriptTime: () -> String?,
    private val onCopy: (String) -> Unit,
    private val onHistory: () -> Unit,
    private val onSettings: () -> Unit,
    private val onTap: () -> Unit,
    private val theme: Theme,
    private val renderer: ScreenRenderer,
) : ScrollView(context), HomeControl {
    /** The screen drawn last, so the drawer's state can be carried into the next drawing. */
    private var drawn: Screen? = null

    /** The app's facts as they were last pulled, so Copy copies the transcript on display. */
    private var state: HomeState = HomeState()

    /** The line the app last gave, drawn below the last-transcript box, or null for none. */
    private var line: String? = null

    /** Whether the big mic shows the recording look. */
    private var recording: Boolean = false

    init {
        // A screen is taller than a short window in landscape, and without this
        // the background of the child would stop short of the visible area.
        isFillViewport = true
        draw(merged(null))
    }

    /**
     * Replaces the rows with [screen], keeping the position on the screen.
     *
     * The position is read before the child goes away and asked for again
     * afterwards, and the platform limits it to the height of the new content.
     */
    fun show(screen: Screen) {
        val held = scrollY
        removeAllViews()
        val rendered = renderer.render(screen, theme, ::onIntent)
        addView(rendered)
        setBackgroundColor(theme.color(PaletteSlot.BACKGROUND).argb)
        scrollTo(0, held)
        findTagged(rendered, HomeNodeIds.MIC)?.let { attachMic(it) }
    }

    /**
     * Draws [state]: the handler's own state is set to it, so the intent that
     * follows is acted on with the app's current facts, and the screen that is
     * rendered from them is the one put on the display.
     */
    private fun draw(state: HomeState) {
        this.state = state
        val screen = handler.show(state)
        drawn = screen
        show(screen)
    }

    /**
     * The state to draw next: the app's facts as the app offers them now, the
     * drawer as [source] last drew it, and the notice the handler is showing:
     * the answer to a HOW IT WORKS or ABOUT tap, which the host does not act on.
     *
     * The drawer is the handler's own state and cannot be read out of it, so it is
     * read off a screen the handler drew: the one just acted on, or the one on
     * display when the view is attached or the window regains focus.
     *
     * The line and the recording look are the ones last given through showLine and
     * showRecording, which are kept in this view.
     */
    private fun merged(source: Screen?): HomeState = HomeState(
        switchOn = switch.isOn(),
        modelReady = modelReady(),
        lastDictation = lastDictation(),
        lastTranscript = lastTranscript(),
        transcriptTime = transcriptTime(),
        line = line,
        recording = recording,
        menuOpen = source?.nodes?.flatMap { it.flatten() }?.any { it.id == HomeNodeIds.DRAWER_PANEL } ?: false,
        notice = handler.notice(),
    )

    /** Acts on [intent] and draws the result. */
    private fun onIntent(intent: ScreenIntent) {
        if (intent is ScreenIntent.Home) act(intent.action)
        val handled = handler.handle(intent)
        draw(merged(handled))
    }

    /**
     * The app's own actions. The drawer and its items are the handler's to act on;
     * the switch and the copy and the four destinations are the app's.
     */
    private fun act(action: String) {
        when (action) {
            HomeActions.SWITCH -> try {
                if (switch.isOn()) switch.switchOff() else switch.switchOn()
            } catch (failure: Exception) {
                // The app's switch failed to answer; the next drawing reads it again.
            }
            HomeActions.COPY -> onCopy(state.lastTranscript ?: "")
            HomeActions.HISTORY -> onHistory()
            HomeActions.SETTINGS -> onSettings()
            HomeActions.MIC -> onTap()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Drawn again on attachment: a take may have finished while this view
        // was off screen, and the transcript it left is the app's to show.
        draw(merged(drawn))
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            // Drawn again on focus: the switch, the model and the transcript may
            // have changed while the view was off screen.
            draw(merged(drawn))
        }
    }

    /** Shows [line] below the last-transcript box, or nothing when it is null, and draws the screen again. */
    override fun showLine(line: String?) {
        this.line = line
        draw(merged(drawn))
    }

    /** Shows the recording look on the big mic while [recording] is true, and draws the screen again. */
    override fun showRecording(recording: Boolean) {
        this.recording = recording
        draw(merged(drawn))
    }

    /** Makes the big mic a button: it takes a tap, is announced as a button, and carries its words. */
    private fun attachMic(mic: View) {
        mic.isClickable = true
        mic.isFocusable = true
        mic.contentDescription = HomeTexts.TAP_TO_TALK
        mic.minimumHeight = touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), resources.displayMetrics.density)
        mic.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        })
        mic.setOnClickListener { onIntent(ScreenIntent.Home(HomeActions.MIC)) }
    }

    /** The first view under [root] whose tag is [id], the root included, or null. */
    private fun findTagged(root: View, id: String): View? {
        if (root.tag == id) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            val found = findTagged(group.getChildAt(index), id)
            if (found != null) return found
        }
        return null
    }
}
