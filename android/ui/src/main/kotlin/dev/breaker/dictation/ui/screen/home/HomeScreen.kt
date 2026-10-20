package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.render.dropFinalPeriod
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.BottomStripes
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Dot
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.MenuIcon
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Rule
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.Spacer
import dev.breaker.dictation.ui.screen.Switch
import dev.breaker.dictation.ui.screen.TextAlign
import dev.breaker.dictation.ui.screen.TouchGap
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import dev.breaker.dictation.ui.screen.history.transcriptCard
import dev.breaker.dictation.ui.screen.history.transcriptCardBox
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
internal class HomeScreen(private val format: TimestampFormat = TimestampFormat { it.toString() }) {

    /**
     * Builds the tree for [state]: the page background, the top bar and its rule,
     * the on/off card, the big mic with its words, the last-transcript box and the
     * page stripes, then the drawer's nodes while the menu is open.
     */
    fun render(state: HomeState): Screen = Screen(
        id = HomeNodeIds.SCREEN,
        title = HomeTexts.LOGO,
        nodes = buildList {
            add(Box(HomeNodeIds.BACKGROUND, PaletteSlot.BACKGROUND))
            add(topBar())
            add(Rule(HomeNodeIds.TOP_BAR_TRIM, PaletteSlot.ACCENT))
            add(Spacer(HomeNodeIds.GAP_TOP, Space.M))
            add(card(state))
            add(Spacer(HomeNodeIds.GAP_MIC, Space.XL))
            add(mic(state))
            add(Spacer(HomeNodeIds.GAP_WORDS, Space.S))
            add(Label(HomeNodeIds.MIC_WORD, HomeTexts.TAP_TO_TALK, TypeRole.DISPLAY, PaletteSlot.TEXT, align = TextAlign.CENTER, caps = true))
            add(Label(HomeNodeIds.MIC_HINT, HomeTexts.TAP_HINT, TypeRole.BODY, PaletteSlot.TEXT_MUTED, align = TextAlign.CENTER))
            add(Spacer(HomeNodeIds.GAP_TRANSCRIPT, Space.L))
            add(transcript(state))
            state.notice?.let { add(Label(HomeNodeIds.NOTICE, it, TypeRole.BODY, PaletteSlot.TEXT)) }
            state.line?.let { add(Label(HomeNodeIds.LINE, it, TypeRole.BODY, PaletteSlot.TEXT)) }
            add(BottomStripes(HomeNodeIds.STRIPES))
            if (state.menuOpen) addAll(drawer())
        },
    )

    /** The top bar, one row: the menu icon at the left and the wordmark centred in the rest of the row. */
    private fun topBar(): Box = Box(
        id = HomeNodeIds.TOP_BAR,
        background = PaletteSlot.BACKGROUND,
        horizontal = true,
        children = listOf(
            MenuIcon(
                id = HomeNodeIds.MENU,
                intent = ScreenIntent.Home(HomeActions.MENU),
                description = HomeTexts.MENU_BUTTON,
            ),
            Label(HomeNodeIds.TOP_BAR_WORD, HomeTexts.LOGO, TypeRole.DISPLAY, PaletteSlot.TEXT, align = TextAlign.CENTER),
            TouchGap(HomeNodeIds.TOP_BAR_GAP),
        ),
    )

    /** The on/off card: the accent edge, a title row with the state word and the switch, and the three status rows. */
    private fun card(state: HomeState): Box {
        val card = HomeNodeIds.CARD
        return Box(
            id = card,
            background = PaletteSlot.SURFACE,
            edge = PaletteSlot.ACCENT,
            pad = Space.L,
            gap = Space.S,
            children = buildList {
                add(
                    Box(
                        id = "$card.title",
                        background = PaletteSlot.SURFACE,
                        horizontal = true,
                        children = listOf(
                            Label(id = "$card.word", text = dropFinalPeriod(cardWord(state)), role = TypeRole.DISPLAY, color = PaletteSlot.TEXT, caps = true),
                            Switch(
                                id = HomeNodeIds.SWITCH,
                                on = state.switchOn,
                                intent = ScreenIntent.Home(HomeActions.SWITCH),
                                description = cardWord(state),
                            ),
                        ),
                    ),
                )
                add(statusRow("$card.row.model", HomeTexts.HEADING_MODEL, modelValue(state.modelReady), TypeRole.BODY, dot = true))
                // the app cannot know reachability: no probe exists, so the server row's one fixed word is always drawn
                add(statusRow("$card.row.server", HomeTexts.TAG_SERVER, HomeTexts.STATUS_NOT_SET, TypeRole.BODY, dot = true))
                add(statusRow("$card.row.dictation", HomeTexts.STATUS_LAST_DICTATION, state.lastDictationAt?.let { format.format(it) }, TypeRole.MONO))
            },
        )
    }

    /** The card's word: the on/off state the ruled words name. */
    private fun cardWord(state: HomeState): String = if (state.switchOn) HomeTexts.SWITCH_ON else HomeTexts.SWITCH_OFF

    /** The model row's value: the kit's badge word when it is ready, the ruled not-downloaded word when not. */
    private fun modelValue(modelReady: Boolean): String =
        if (modelReady) HomeTexts.STATUS_MODEL_READY else HomeTexts.STATUS_MODEL_MISSING

    /**
     * One status row: a horizontal row with the key in the left half, muted, and the value in the
     * right half when there is a ruled word for it. A row with [dot] shows its value as a small
     * accent square and then the word. The dictation value is the time of the last dictation, in
     * the mono face, with no square.
     */
    private fun statusRow(row: String, key: String, value: String?, valueRole: TypeRole, dot: Boolean = false): Box = Box(
        id = row,
        background = PaletteSlot.SURFACE,
        horizontal = true,
        children = buildList {
            add(Label("$row.key", key, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
            if (value != null) {
                add(if (dot) valueCell(row, value, valueRole) else Label("$row.value", value, valueRole, PaletteSlot.TEXT))
            }
        },
    )

    /** The value cell of a dotted row: the accent square, then the word, in a horizontal cell of its own. */
    private fun valueCell(row: String, value: String, valueRole: TypeRole): Box = Box(
        id = "$row.cell",
        background = PaletteSlot.SURFACE,
        horizontal = true,
        gap = Space.XS,
        children = listOf(
            Dot("$row.dot", PaletteSlot.ACCENT),
            Label("$row.value", value, valueRole, PaletteSlot.TEXT),
        ),
    )

    /**
     * The big mic: a slot the host fills with the overlay-built mic view (ADR-026).
     * The box carries no background and no drawing of its own; the host places
     * [micView] inside it and gives the box the mic view's height. The words are
     * their own nodes below it.
     */
    private fun mic(state: HomeState): Box = Box(
        id = HomeNodeIds.MIC,
    )

    /**
     * The last-transcript box: the shared outlined card, with a header row (the
     * heading at the left and the time at the right), the text, and the Copy and Toggle buttons.
     * The Copy button is drawn only while the text is not blank, so an empty box has none.
     */
    private fun transcript(state: HomeState): Box {
        val box = HomeNodeIds.TRANSCRIPT
        val text = state.lastTranscript ?: ""
        val card = transcriptCard(
            prefix = box,
            text = text,
            blankText = HomeTexts.EMPTY_BOX,
            opened = state.opened,
            copyIntent = if (text.isBlank()) null else ScreenIntent.Home(HomeActions.COPY),
            toggleIntent = ScreenIntent.Home(if (state.opened) HomeActions.HIDE else HomeActions.SHOW_ALL),
            deleteIntent = null,
        )
        val header = Box(
            id = "$box.header",
            background = PaletteSlot.SURFACE,
            horizontal = true,
            spread = true,
            children = buildList {
                add(Label("$box.word", HomeTexts.LAST_TRANSCRIPT, TypeRole.BODY, PaletteSlot.TEXT_MUTED, caps = true))
                state.transcriptTime?.let { add(Label("$box.time", it, TypeRole.MONO, PaletteSlot.TEXT_MUTED)) }
            },
        )
        return transcriptCardBox(id = box, header = header, card = card)
    }

    /**
     * The menu drawer: the dim first, which takes a tap and closes the menu, then the panel with the
     * wordmark, its accent rule, the four items as text rows and the three stripes. The host draws
     * the two as an overlay over the page.
     */
    private fun drawer(): List<Node> = listOf(
        Box(
            id = HomeNodeIds.DRAWER_DIM,
            background = PaletteSlot.BACKGROUND,
            onClick = ScreenIntent.Home(HomeActions.CLOSE_MENU),
        ),
        Box(
            id = HomeNodeIds.DRAWER_PANEL,
            background = PaletteSlot.SURFACE,
            children = buildList {
                add(
                    Box(
                        id = HomeNodeIds.DRAWER_HEADER,
                        background = PaletteSlot.SURFACE,
                        pad = Space.L,
                        children = listOf(Label(HomeNodeIds.DRAWER_WORD, HomeTexts.LOGO, TypeRole.DISPLAY, PaletteSlot.TEXT)),
                    ),
                )
                add(Rule(HomeNodeIds.DRAWER_RULE, PaletteSlot.ACCENT))
                add(Action(HomeNodeIds.DRAWER_HISTORY, HomeTexts.MENU_HISTORY, intent = ScreenIntent.Home(HomeActions.HISTORY), kit = KitLook.TEXT))
                add(Action(HomeNodeIds.DRAWER_SETTINGS, HomeTexts.MENU_SETTINGS, intent = ScreenIntent.Home(HomeActions.SETTINGS), kit = KitLook.TEXT))
                add(Action(HomeNodeIds.DRAWER_HOW, HomeTexts.MENU_HOW_IT_WORKS, intent = ScreenIntent.Home(HomeActions.HOW_IT_WORKS), kit = KitLook.TEXT))
                add(Action(HomeNodeIds.DRAWER_ABOUT, HomeTexts.MENU_ABOUT, intent = ScreenIntent.Home(HomeActions.ABOUT), kit = KitLook.TEXT))
                add(BottomStripes(HomeNodeIds.DRAWER_STRIPES, plain = true))
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
    const val TOP_BAR_GAP = "home.topbar.gap"
    const val GAP_TOP = "home.gap.top"
    const val GAP_MIC = "home.gap.mic"
    const val GAP_WORDS = "home.gap.words"
    const val GAP_TRANSCRIPT = "home.gap.transcript"
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
    const val TRANSCRIPT_BUTTONS = "home.transcript.buttons"
    const val COPY = "home.transcript.copy"
    const val TOGGLE = "home.transcript.toggle"
    const val NOTICE = "home.notice"
    const val LINE = "home.line"
    const val STRIPES = "home.stripe"
    const val DRAWER_DIM = "home.drawer.dim"
    const val DRAWER_PANEL = "home.drawer.panel"
    const val DRAWER_RULE = "home.drawer.rule"
    const val DRAWER_HEADER = "home.drawer.header"
    const val DRAWER_WORD = "home.drawer.word"
    const val DRAWER_HISTORY = "home.drawer.item.history"
    const val DRAWER_SETTINGS = "home.drawer.item.settings"
    const val DRAWER_HOW = "home.drawer.item.how"
    const val DRAWER_ABOUT = "home.drawer.item.about"
    const val DRAWER_STRIPES = "home.drawer.stripe"
}
