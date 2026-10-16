package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent

/**
 * The strings a tap on the home screen reports: the contract with the host.
 *
 * One per control the screen draws: the menu, the switch, the copy, the four
 * drawer items and the big mic. Every Action in the tree carries [ScreenIntent.Home]
 * with one of these, and the handler answers the same string for the control's id.
 * The big mic is not an Action: the host attaches its tap and reports this string.
 */
internal object HomeActions {
    const val MENU = "MENU"
    const val SWITCH = "SWITCH"
    const val COPY = "COPY"
    const val HISTORY = "HISTORY"
    const val SETTINGS = "SETTINGS"
    const val HOW_IT_WORKS = "HOW_IT_WORKS"
    const val ABOUT = "ABOUT"
    const val MIC = "MIC"
}

/**
 * The home screen's memory, and the one place its taps are turned into intents.
 *
 * A tap arrives as the id of the control that was chosen. [intentFor] turns it
 * into the [ScreenIntent.Home] the tap reports: the same string the control in
 * the tree carries, and [tap] acts on it and hands back the screen to draw next.
 *
 * The drawer is this screen's own state: the menu toggles it and a drawer item
 * closes it. The switch and the copy change nothing here: the host acts on them
 * and pushes the new state back through [show].
 *
 * Call it from one thread at a time; it does no locking.
 */
internal class HomeIntentHandler(private val screen: HomeScreen) {

    private var state: HomeState = HomeState()

    /** The screen as the state stands now. */
    fun current(): Screen = screen.render(state)

    /** The state the host pushed: the screen to draw next. */
    fun show(state: HomeState): Screen {
        this.state = state
        return screen.render(state)
    }

    /** The notice this screen is showing: the answer to a HOW IT WORKS or ABOUT tap. */
    fun notice(): String? = state.notice

    /**
     * Turns a tap on [nodeId] into the intent it reports, acts on it, and returns
     * the screen to draw next. A tap this screen does not act on: a label, a
     * stripe, the background, is drawn again from the state held.
     */
    fun tap(nodeId: String): Screen {
        val intent = intentFor(nodeId)
        return if (intent == null) screen.render(state) else handle(intent)
    }

    /** The intent a tap on [nodeId] reports, or null when the screen does not act on it. */
    fun intentFor(nodeId: String): ScreenIntent? = when (nodeId) {
        HomeNodeIds.MENU -> ScreenIntent.Home(HomeActions.MENU)
        HomeNodeIds.SWITCH -> ScreenIntent.Home(HomeActions.SWITCH)
        HomeNodeIds.COPY -> ScreenIntent.Home(HomeActions.COPY)
        HomeNodeIds.MIC -> ScreenIntent.Home(HomeActions.MIC)
        HomeNodeIds.DRAWER_HISTORY -> ScreenIntent.Home(HomeActions.HISTORY)
        HomeNodeIds.DRAWER_SETTINGS -> ScreenIntent.Home(HomeActions.SETTINGS)
        HomeNodeIds.DRAWER_HOW -> ScreenIntent.Home(HomeActions.HOW_IT_WORKS)
        HomeNodeIds.DRAWER_ABOUT -> ScreenIntent.Home(HomeActions.ABOUT)
        else -> null
    }

    /**
     * Acts on [intent] and returns the screen as it stands afterwards. An intent
     * this screen does not own is drawn again from the state held, without a call
     * to anything above.
     */
    fun handle(intent: ScreenIntent): Screen = when (intent) {
        is ScreenIntent.Home -> act(intent.action)
        else -> screen.render(state)
    }

    /**
     * The screen-local half of a tap: the menu toggles the drawer, a drawer item
     * closes it, and a HOW IT WORKS or ABOUT tap answers in place with the
     * not-built notice: the host does nothing for those two. The switch and the
     * copy are acted on above, which pushes the new state back through [show].
     */
    private fun act(action: String): Screen {
        when (action) {
            HomeActions.MENU -> state = state.copy(menuOpen = !state.menuOpen)
            HomeActions.HISTORY,
            HomeActions.SETTINGS,
            -> state = state.copy(menuOpen = false)
            HomeActions.HOW_IT_WORKS,
            HomeActions.ABOUT,
            -> state = state.copy(menuOpen = false, notice = HomeTexts.MENU_NOT_BUILT)
        }
        return screen.render(state)
    }
}
