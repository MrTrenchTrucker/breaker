package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot

/**
 * The home screen, described as nodes and nothing else.
 *
 * The same shape as SetupScreen: one pure render from the state to a tree of
 * nodes, each node an id, a slot and, for the words, a HomeTexts constant.
 * The tree carries no colour and no geometry: a slot names a palette role and
 * the host draws it, so the whole screen is checkable in a plain JVM test.
 *
 * The mic's slot follows the switch while no take is recording, as the agreed rule for
 * the two faces says: the on mic is the art's accent glow and the off mic the flat
 * not-ready face, and the tree tells the two apart by the slot alone. While recording
 * the slot is the primary face.
 */
internal class HomeScreen {

    /**
     * Builds the tree for [state]: the page background, the top bar, the on/off
     * card, the big mic, the last-transcript box and the four stripes, then the
     * drawer's nodes while the menu is open.
     */
    fun render(state: HomeState): Screen = Screen(
        id = HomeNodeIds.SCREEN,
        title = HomeTexts.LOGO,
        nodes = buildList {
            add(Box(HomeNodeIds.BACKGROUND, PaletteSlot.BACKGROUND))
            add(topBar())
            add(card(state))
            add(mic(state))
            add(transcript(state))
            state.notice?.let { add(Label(HomeNodeIds.NOTICE, it, TypeRole.BODY, PaletteSlot.TEXT)) }
            state.line?.let { add(Label(HomeNodeIds.LINE, it, TypeRole.BODY, PaletteSlot.TEXT)) }
            stripeNodes().forEach { add(it) }
            if (state.menuOpen) addAll(drawer())
        },
    )

    /** The top bar: the menu button, the wordmark and the art's accent rule under it. */
    private fun topBar(): Box = Box(
        id = HomeNodeIds.TOP_BAR,
        background = PaletteSlot.BACKGROUND,
        children = listOf(
            Action(
                id = HomeNodeIds.MENU,
                text = HomeTexts.MENU_BUTTON,
                intent = ScreenIntent.Home(HomeActions.MENU),
            ),
            Label(HomeNodeIds.TOP_BAR_WORD, HomeTexts.LOGO, TypeRole.DISPLAY, PaletteSlot.TEXT),
            Box(HomeNodeIds.TOP_BAR_TRIM, PaletteSlot.ACCENT),
        ),
    )

    /** The on/off card: the accent lip, the state word, the switch and the three status rows. */
    private fun card(state: HomeState): Box {
        val card = HomeNodeIds.CARD
        return Box(
            id = card,
            background = PaletteSlot.SURFACE,
            children = buildList {
                add(Box("$card.lip", PaletteSlot.ACCENT))
                add(
                    Label(
                        id = "$card.word",
                        text = cardWord(state),
                        role = TypeRole.BODY,
                        color = PaletteSlot.TEXT,
                    ),
                )
                add(
                    Action(
                        id = HomeNodeIds.SWITCH,
                        text = cardWord(state),
                        intent = ScreenIntent.Home(HomeActions.SWITCH),
                    ),
                )
                addAll(statusRow("$card.row.model", HomeTexts.HEADING_MODEL, modelValue(state.modelReady), TypeRole.BODY))
                // the app cannot know reachability: no probe exists, so the server row's one fixed word is always drawn
                addAll(statusRow("$card.row.server", HomeTexts.TAG_SERVER, HomeTexts.STATUS_NOT_AVAILABLE, TypeRole.BODY))
                addAll(statusRow("$card.row.dictation", HomeTexts.STATUS_LAST_DICTATION, state.lastDictation, TypeRole.MONO))
            },
        )
    }

    /** The card's and the switch's word: the on/off state the ruled words name. */
    private fun cardWord(state: HomeState): String = if (state.switchOn) HomeTexts.SWITCH_ON else HomeTexts.SWITCH_OFF

    /** The model row's value: the kit's badge word when it is ready, the ruled not-downloaded word when not. */
    private fun modelValue(modelReady: Boolean): String =
        if (modelReady) HomeTexts.STATUS_MODEL_READY else HomeTexts.STATUS_MODEL_MISSING

    /**
     * One status row: the key always, the value when there is a ruled word for it.
     * The key is muted and the value the plainer text, both as the art draws them;
     * the dictation value is the text of the last dictation, not a time, in the mono face.
     */
    private fun statusRow(row: String, key: String, value: String?, valueRole: TypeRole): List<Node> = buildList {
        add(Label("$row.key", key, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
        if (value != null) {
            add(Label("$row.value", value, valueRole, PaletteSlot.TEXT))
        }
    }

    /**
     * The big mic: the slot follows the switch, so the tree carries the two
     * faces apart: the on mic is the accent glow, the off mic the muted face.
     * While a take is recording the mic takes the primary face instead.
     */
    private fun mic(state: HomeState): Box = Box(
        id = HomeNodeIds.MIC,
        background = micSlot(state),
        children = listOf(
            Label(HomeNodeIds.MIC_WORD, HomeTexts.TAP_TO_TALK, TypeRole.DISPLAY, PaletteSlot.TEXT),
            Label(HomeNodeIds.MIC_HINT, HomeTexts.TAP_HINT, TypeRole.BODY, PaletteSlot.TEXT_MUTED),
        ),
    )

    /** The mic's slot: the primary face while recording, else the switch's accent or muted face. */
    private fun micSlot(state: HomeState): PaletteSlot = when {
        state.recording -> PaletteSlot.PRIMARY
        state.switchOn -> PaletteSlot.ACCENT
        else -> PaletteSlot.TEXT_MUTED
    }

    /**
     * The last-transcript box: the heading, the time when there is one, the text
     * or the ruled empty word, and the Copy button while there is a transcript.
     */
    private fun transcript(state: HomeState): Box {
        val box = HomeNodeIds.TRANSCRIPT
        return Box(
            id = box,
            background = PaletteSlot.SURFACE,
            children = buildList {
                add(Label("$box.word", HomeTexts.LAST_TRANSCRIPT, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
                state.transcriptTime?.let { add(Label("$box.time", it, TypeRole.MONO, PaletteSlot.TEXT_MUTED)) }
                add(Label("$box.text", state.lastTranscript ?: HomeTexts.EMPTY_BOX, TypeRole.BODY, PaletteSlot.TEXT))
                if (state.lastTranscript != null) {
                    add(
                        Action(
                            id = HomeNodeIds.COPY,
                            text = HomeTexts.BUTTON_COPY,
                            emphasis = Emphasis.PRIMARY,
                            intent = ScreenIntent.Home(HomeActions.COPY),
                        ),
                    )
                }
            },
        )
    }

    /**
     * The four bottom stripes. The art alternates accent and its darker stripe
     * green; the tree names the two greens the palette holds, accent and primary,
     * so the art's alternation survives.
     */
    private fun stripeNodes(): List<Box> = listOf(
        Box("${HomeNodeIds.STRIPES}.1", PaletteSlot.ACCENT),
        Box("${HomeNodeIds.STRIPES}.2", PaletteSlot.PRIMARY),
        Box("${HomeNodeIds.STRIPES}.3", PaletteSlot.ACCENT),
        Box("${HomeNodeIds.STRIPES}.4", PaletteSlot.PRIMARY),
    )

    /**
     * The menu drawer: the dim face first: the host dims it the art's 55%, and
     * the tree carries no percentage, then the panel with the wordmark, the four
     * items and the three stripes.
     */
    private fun drawer(): List<Node> = listOf(
        Box(HomeNodeIds.DRAWER_DIM, PaletteSlot.BACKGROUND),
        Box(
            id = HomeNodeIds.DRAWER_PANEL,
            background = PaletteSlot.SURFACE,
            children = buildList {
                add(Label(HomeNodeIds.DRAWER_WORD, HomeTexts.LOGO, TypeRole.DISPLAY, PaletteSlot.TEXT))
                add(Action(HomeNodeIds.DRAWER_HISTORY, HomeTexts.MENU_HISTORY, intent = ScreenIntent.Home(HomeActions.HISTORY)))
                add(Action(HomeNodeIds.DRAWER_SETTINGS, HomeTexts.MENU_SETTINGS, intent = ScreenIntent.Home(HomeActions.SETTINGS)))
                add(Action(HomeNodeIds.DRAWER_HOW, HomeTexts.MENU_HOW_IT_WORKS, intent = ScreenIntent.Home(HomeActions.HOW_IT_WORKS)))
                add(Action(HomeNodeIds.DRAWER_ABOUT, HomeTexts.MENU_ABOUT, intent = ScreenIntent.Home(HomeActions.ABOUT)))
                add(Box("${HomeNodeIds.DRAWER_STRIPES}.1", PaletteSlot.ACCENT))
                add(Box("${HomeNodeIds.DRAWER_STRIPES}.2", PaletteSlot.PRIMARY))
                add(Box("${HomeNodeIds.DRAWER_STRIPES}.3", PaletteSlot.ACCENT))
            },
        ),
    )
}

/** The ids of the home screen's nodes, and the prefixes its stripes share. */
internal object HomeNodeIds {
    const val SCREEN = "home"
    const val BACKGROUND = "home.background"
    const val TOP_BAR = "home.topbar"
    const val MENU = "home.topbar.menu"
    const val TOP_BAR_WORD = "home.topbar.word"
    const val TOP_BAR_TRIM = "home.topbar.trim"
    const val CARD = "home.card"
    const val SWITCH = "home.card.switch"
    const val ROW_MODEL = "home.card.row.model"
    const val ROW_SERVER = "home.card.row.server"
    const val ROW_DICTATION = "home.card.row.dictation"
    const val MIC = "home.mic"
    const val MIC_WORD = "home.mic.word"
    const val MIC_HINT = "home.mic.hint"
    const val TRANSCRIPT = "home.transcript"
    const val TRANSCRIPT_WORD = "home.transcript.word"
    const val TRANSCRIPT_TIME = "home.transcript.time"
    const val TRANSCRIPT_TEXT = "home.transcript.text"
    const val COPY = "home.transcript.copy"
    const val NOTICE = "home.notice"
    const val LINE = "home.line"
    const val STRIPES = "home.stripe"
    const val DRAWER_DIM = "home.drawer.dim"
    const val DRAWER_PANEL = "home.drawer.panel"
    const val DRAWER_WORD = "home.drawer.word"
    const val DRAWER_HISTORY = "home.drawer.item.history"
    const val DRAWER_SETTINGS = "home.drawer.item.settings"
    const val DRAWER_HOW = "home.drawer.item.how"
    const val DRAWER_ABOUT = "home.drawer.item.about"
    const val DRAWER_STRIPES = "home.drawer.stripe"
}
