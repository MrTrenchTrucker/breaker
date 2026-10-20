package dev.breaker.dictation.ui.render

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.breaker.dictation.ui.BreakerSwitch
import dev.breaker.dictation.ui.HomeControl
import dev.breaker.dictation.ui.screen.CollapsibleText
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.home.HomeActions
import dev.breaker.dictation.ui.screen.home.HomeGeometry
import dev.breaker.dictation.ui.screen.home.HomeIntentHandler
import dev.breaker.dictation.ui.screen.home.HomeNodeIds
import dev.breaker.dictation.ui.screen.home.HomeState
import dev.breaker.dictation.ui.screen.home.HomeTexts
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/*
 * The view the app puts on the display for the home screen.
 *
 * It does the job the first-run host does for the first-run steps, with one difference:
 * where the screen's facts come from. The first-run screen's are the handler's own,
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
 * @param lastDictationAt answers the epoch-millis time of the last dictation, or null.
 * @param lastTranscript answers the last transcript to copy, or null.
 * @param transcriptTime answers the phone's own short time for the transcript.
 * @param onCopy asks the app to copy the transcript on display to the clipboard.
 * @param onHistory asks the app to show the history screen.
 * @param onHowItWorks asks the app to show the How it works screen.
 * @param onAbout asks the app to show the About screen.
 * @param onSettings asks the app to show the settings screen.
 * @param onTap asks the app to start a take from the big mic on the screen.
 * @param theme the colours and sizes the screen is drawn in.
 * @param micView the overlay-built mic view the host places in the mic slot (ADR-026).
 * @param usableHeight the window height left after the system bars, in dp, that the mic slot is
 *   sized from.
 * @param renderer draws a described screen into views.
 */
internal class HomeHostView(
    context: Context,
    private val handler: HomeIntentHandler,
    private val switch: BreakerSwitch,
    private val modelReady: () -> Boolean,
    private val lastDictationAt: () -> Long?,
    private val lastTranscript: () -> String?,
    private val transcriptTime: () -> String?,
    private val onCopy: (String) -> Unit,
    private val onHistory: () -> Unit,
    private val onHowItWorks: () -> Unit,
    private val onAbout: () -> Unit,
    private val onSettings: () -> Unit,
    private val onTap: () -> Unit,
    private val theme: Theme,
    private val micView: android.view.View,
    private val usableHeight: Float,
    private val renderer: ScreenRenderer,
) : FrameLayout(context), HomeControl {
    /** The page: the screen's rows and their bleed layers, scrolled as one. Made in init. */
    private var page: ScrollView? = null

    /** The drawer's overlay: the dim and the panel, over the page only while the menu is open. */
    private val overlay = FrameLayout(context)

    /** The top inset the host was padded by, kept for the top of the drawer's panel. */
    private var panelTop: Int = 0

    /** The drawer's own actions: the four items and the dim. Any other tap while the menu is open only closes it. */
    private val drawerActions = setOf(
        HomeActions.HISTORY,
        HomeActions.SETTINGS,
        HomeActions.HOW_IT_WORKS,
        HomeActions.ABOUT,
        HomeActions.CLOSE_MENU,
    )

    /** The screen drawn last, so the drawer's state can be carried into the next drawing. */
    private var drawn: Screen? = null

    /** The app's facts as they were last pulled, so Copy copies the transcript on display. */
    private var state: HomeState = HomeState()

    /** The line the app last gave, drawn below the last-transcript box, or null for none. */
    private var line: String? = null

    /** Whether the big mic shows the recording look. */
    private var recording: Boolean = false

    /** The Back callback held while the menu is open, or null when it is not held. */
    private var backCallback: android.window.OnBackInvokedCallback? = null

    /** The dispatcher the callback went to, kept so the callback can be taken off after the view leaves its window. */
    private var backDispatcher: android.window.OnBackInvokedDispatcher? = null

    init {
        val scroll = ScrollView(context)
        // A screen is taller than a short window in landscape, and without this
        // the background of the child would stop short of the visible area.
        scroll.openForBleed()
        page = scroll
        addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        draw(merged(null))
    }

    /** The host's padding is the page's, the one padding place for the screen; the top inset is kept for the panel. */
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        panelTop = top
        page?.setPadding(left, top, right, bottom)
    }

    /**
     * Replaces the rows with [screen], keeping the position on the screen.
     *
     * The position is read before the child goes away and asked for again
     * afterwards, and the platform limits it to the height of the new content.
     */
    fun show(screen: Screen) {
        val content = checkNotNull(page)
        val held = content.scrollY
        val split = screen.nodes.partition { it.id == HomeNodeIds.DRAWER_DIM || it.id == HomeNodeIds.DRAWER_PANEL }
        content.removeAllViews()
        val rendered = renderer.render(Screen(screen.id, screen.title, split.second), theme, ::onIntent)
        applyBrandArt(rendered, context, theme)
        attachMicSlot(rendered)
        content.addView(rendered)
        content.setBackgroundColor(theme.color(PaletteSlot.BACKGROUND).argb)
        content.scrollTo(0, held)
        findTagged(rendered, HomeNodeIds.MIC)?.let { attachMic(it) }
        showOverlay(split.first, screen)
        menuBack(split.first.isNotEmpty())
    }

    /**
     * Draws the drawer's [nodes] over the page. The dim covers the whole host at the dim's alpha, and
     * the panel stands at the left at the panel's width, its top moved down by the top inset. The
     * overlay is on the host only while there are nodes for it, that is while the menu is open.
     */
    private fun showOverlay(nodes: List<Node>, screen: Screen) {
        overlay.removeAllViews()
        if (nodes.isEmpty()) {
            if (overlay.parent === this) removeView(overlay)
            return
        }
        val dimRoot = renderer.render(Screen(screen.id, screen.title, nodes.filter { it.id == HomeNodeIds.DRAWER_DIM }), theme, ::onIntent)
        val panelRoot = renderer.render(Screen(screen.id, screen.title, nodes.filter { it.id == HomeNodeIds.DRAWER_PANEL }), theme, ::onIntent)
        applyBrandArt(panelRoot, context, theme)
        findTagged(dimRoot, HomeNodeIds.DRAWER_DIM)?.let { dim ->
            dim.alpha = HomeGeometry.DRAWER_DIM
            dim.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        findTagged(panelRoot, HomeNodeIds.DRAWER_PANEL)?.let { panel ->
            panel.layoutParams = LinearLayout.LayoutParams((HomeGeometry.PANEL_W_DP * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
            panel.setPadding(0, panelTop, 0, 0)
        }
        overlay.addView(layerOf(dimRoot))
        overlay.addView(layerOf(panelRoot))
        if (overlay.parent !== this) addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** A layer over the page: the root takes the host's size and no background of its own, so the page shows through. */
    private fun layerOf(root: View): View {
        root.background = null
        root.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return root
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
     * drawer as [source] last drew it, and the notice the handler is showing.
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
        lastDictationAt = lastDictationAt(),
        lastTranscript = lastTranscript(),
        transcriptTime = transcriptTime(),
        line = line,
        recording = recording,
        menuOpen = source?.nodes?.flatMap { it.flatten() }?.any { it.id == HomeNodeIds.DRAWER_PANEL } ?: false,
        opened = source?.nodes?.flatMap { it.flatten() }?.filterIsInstance<CollapsibleText>()?.any { it.opened } ?: false,
        notice = handler.notice(),
    )

    /**
     * Acts on [intent] and draws the result. While the menu is open, a tap that is not a drawer item
     * only closes the menu: the check comes before any action, and nothing else happens.
     */
    private fun onIntent(intent: ScreenIntent) {
        if (intent is ScreenIntent.Home && state.menuOpen && intent.action !in drawerActions) {
            draw(merged(handler.handle(ScreenIntent.Home(HomeActions.CLOSE_MENU))))
            return
        }
        if (intent is ScreenIntent.Home) act(intent.action)
        val handled = handler.handle(intent)
        draw(merged(handled))
    }

    /**
     * The app's own actions. The drawer and its items are the handler's to act on;
     * the switch and the copy and the six destinations are the app's.
     * While the menu is open, a tap on the mic closes the menu instead of starting a take.
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
            HomeActions.HOW_IT_WORKS -> onHowItWorks()
            HomeActions.ABOUT -> onAbout()
            HomeActions.MIC -> if (state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else onTap()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Drawn again on attachment: a take may have finished while this view
        // was off screen, and the transcript it left is the app's to show.
        draw(merged(drawn))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseMenuBack()
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

    /** Re-reads the app's facts and draws the screen again. */
    override fun refresh() {
        draw(merged(drawn))
    }

    /** Closes the menu when it is open, through the same intent the dim sends, and answers whether it was open. */
    override fun closeMenu(): Boolean {
        val wasOpen = state.menuOpen
        if (wasOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU))
        return wasOpen
    }

    /** Takes the Back callback on while the menu is open, and takes it off when the menu shuts. */
    private fun menuBack(open: Boolean) {
        if (open) registerMenuBack() else releaseMenuBack()
    }

    /** Registers the Back callback on the view's own dispatcher, once, and keeps the dispatcher for taking it off. */
    private fun registerMenuBack() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val dispatcher = findOnBackInvokedDispatcher() ?: return
        if (backCallback != null) return
        val callback = android.window.OnBackInvokedCallback { closeMenu() }
        dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        backCallback = callback
        backDispatcher = dispatcher
    }

    /** Takes the Back callback off the dispatcher it went to, when it is held. */
    private fun releaseMenuBack() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val callback = backCallback ?: return
        backDispatcher?.unregisterOnBackInvokedCallback(callback)
        backCallback = null
        backDispatcher = null
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

    /**
     * Places the overlay-built [micView] in the mic slot: the view takes the slot's
     * width and the ruled slot height, so the mic and the page's non-mic rows share
     * the window height left after the system bars (ADR-026).
     */
    private fun attachMicSlot(root: View) {
        val slot = findTagged(root, HomeNodeIds.MIC) ?: return
        val parent = slot.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(slot)
        val mic = micView
        // A redraw renders a new tree; the mic still sits in the old one, and a view cannot have two parents.
        (mic.parent as? ViewGroup)?.removeView(mic)
        mic.tag = HomeNodeIds.MIC
        // The mic measures to its own width (tile-square when idle), so the slot must centre it.
        mic.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (HomeGeometry.slotHeightDp(usableHeight) * resources.displayMetrics.density).toInt(),
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        parent.removeViewAt(index)
        parent.addView(mic, index)
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
