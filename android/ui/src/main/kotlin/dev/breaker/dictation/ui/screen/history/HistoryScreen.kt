package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.render.dropFinalPeriod
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.BottomStripes
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.Spacer
import dev.breaker.dictation.ui.screen.TagPill
import dev.breaker.dictation.ui.screen.TextAlign
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.titleBar
import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * The history screen, described rather than drawn.
 *
 * The tree is built from a [HistoryState] and a notice and from nothing else: no
 * phone, no store and no clock. The screen opens with the title block, then the
 * notice, then the rows, then the note or the Load more button, and it ends with the
 * brand stripes. Each row is one outlined card: its time and source, its text and
 * its actions. A row whose Undo window is open is replaced, in the same place, by a
 * block that offers Undo.
 */

private const val SCREEN_ID = "history"
private const val TITLE_RULE_ID = "history.rule"
private const val NOTICE_BOX_ID = "history.notice.box"
private const val NOTICE_ID = "history.notice"
private const val EMPTY_ID = "history.empty"
private const val EMPTY_HINT_ID = "history.empty.hint"
private const val LIMIT_ID = "history.limit"
private const val MORE_ID = "history.more"
private const val STRIPES_ID = "history.stripes"

/** The id of the empty state's brand mic art: a placeholder that the host draws the art into. */
internal const val EMPTY_ART_ID = "history.empty.art"

/** The id of a notice carried over from a read whose draw was dropped, so it can be told from the notice. */
internal const val CARRIED_NOTICE_ID = "history.notice.carried"

/**
 * The first notice this screen shows, or null when it has none: the primary notice or the carried one,
 * whichever the tree holds first. The primary notice may sit inside the Copied box, so the whole tree is
 * read in order, not only its top level. The callers that decide what to carry forward (AsyncHistory,
 * withCarriedNotice) see only raw screens, which never hold a carried node, so broadening changes nothing for them.
 */
internal fun Screen.noticeLabel(): Label? =
    nodes.flatMap { it.flatten() }.firstOrNull { it.id == NOTICE_ID || it.id == CARRIED_NOTICE_ID } as? Label

/**
 * This screen with [carried] added once: right after the notice when there is one, else right after the
 * title block (the title bar and its rule). A screen whose notice already has the same text is returned as it is.
 */
internal fun Screen.withCarriedNotice(carried: Label): Screen {
    if (noticeLabel()?.text == carried.text) return this
    val notice = nodes.indexOfFirst { node -> node.flatten().any { it.id == NOTICE_ID } }
    val rule = nodes.indexOfFirst { it.id == TITLE_RULE_ID }
    val at = when {
        notice >= 0 -> notice + 1
        rule >= 0 -> rule + 1
        else -> minOf(1, nodes.size)
    }
    val added = Label(CARRIED_NOTICE_ID, carried.text, carried.role, carried.color)
    return copy(nodes = nodes.take(at) + added + nodes.drop(at))
}

/** Draws the history screen. */
internal class HistoryScreen {
    /** The whole tree for [state], with [notice] above the rows when there is one. */
    fun render(state: HistoryState, notice: HistoryNotice?): Screen =
        Screen(
            id = SCREEN_ID,
            title = HistoryTexts.TITLE,
            nodes = buildList {
                addAll(titleBar("history", HistoryTexts.TITLE))
                // A failed first read has no rows to show, so the sentence stands in for the list.
                val failedFirst = state.listFailed && state.rows.isEmpty()
                val message = notice ?: if (failedFirst) HistoryNotice.LIST_FAILED else null
                // Each block below the title rule is set apart from the one before it by a gap of M,
                // numbered in order. The empty state sits in the open space and keeps its own art gap.
                var gaps = 0
                fun gapBefore() {
                    add(Spacer("$SCREEN_ID.gap.$gaps", Space.M))
                    gaps++
                }
                if (message != null) {
                    gapBefore()
                    add(noticeBox(message))
                }
                if (state.rows.isEmpty() && !state.listFailed) {
                    add(Spacer(EMPTY_ART_ID, Space.XL))
                    add(
                        Label(
                            EMPTY_ID,
                            dropFinalPeriod(HistoryTexts.EMPTY_HEADING),
                            TypeRole.DISPLAY,
                            PaletteSlot.TEXT,
                            align = TextAlign.CENTER,
                            caps = true,
                        ),
                    )
                    add(
                        Label(
                            EMPTY_HINT_ID,
                            HistoryTexts.EMPTY_HINT,
                            TypeRole.BODY,
                            PaletteSlot.TEXT_MUTED,
                            align = TextAlign.CENTER,
                        ),
                    )
                }
                state.rows.forEach { row ->
                    gapBefore()
                    add(if (row.id in state.pending) undoBlock(row.id) else rowBlock(row, row.id in state.opened))
                }
                if (state.fullPage && state.shown >= ROW_CAP) {
                    gapBefore()
                    add(Label(LIMIT_ID, HistoryTexts.LIMIT_NOTE, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
                } else if (state.fullPage) {
                    gapBefore()
                    add(
                        Action(
                            id = MORE_ID,
                            text = HistoryTexts.BUTTON_LOAD_MORE,
                            intent = ScreenIntent.History(HistoryActions.MORE),
                            kit = KitLook.OUTLINED,
                        ),
                    )
                }
                add(BottomStripes(STRIPES_ID, plain = true))
            },
        )

    /** The notice above the rows: a surface box with an accent bar down its left edge, holding the notice text. */
    private fun noticeBox(message: HistoryNotice): Box = Box(
        id = NOTICE_BOX_ID,
        background = PaletteSlot.SURFACE,
        edge = PaletteSlot.ACCENT,
        pad = Space.M,
        children = listOf(Label(NOTICE_ID, message.text, TypeRole.BODY, message.color)),
    )

    /**
     * One saved transcription: the shared outlined card, its header row (time at the left, source
     * tag at the right), its text and the actions on it. The toggle is always in the tree; the view
     * shows it only when the collapse decision asks for a middle button.
     */
    private fun rowBlock(row: HistoryRow, opened: Boolean): Box {
        val prefix = "history.row.${row.id}"
        val card = transcriptCard(
            prefix = prefix,
            text = row.text,
            blankText = HistoryTexts.BLANK_TEXT,
            opened = opened,
            copyIntent = ScreenIntent.History(HistoryActions.COPY, row.id),
            toggleIntent = ScreenIntent.History(
                if (opened) HistoryActions.HIDE else HistoryActions.SHOW_ALL,
                row.id,
            ),
            deleteIntent = ScreenIntent.History(HistoryActions.DELETE, row.id),
            compact = true,
        )
        return transcriptCardBox(prefix, headerOf(prefix, row.time, row.source), card)
    }

    /** The header row of a row: the time in the mono role at the left, the source tag at the right end. */
    private fun headerOf(prefix: String, time: String, source: TranscriptionSource): Box = Box(
        id = "$prefix.header",
        background = PaletteSlot.SURFACE,
        horizontal = true,
        children = listOf(
            Label("$prefix.time", time, TypeRole.MONO, PaletteSlot.TEXT_MUTED),
            TagPill("$prefix.tag", tagOf(source)),
        ),
    )

    /** What a row becomes while its Undo window is open: the deleted message at the left and the one action at the right. */
    private fun undoBlock(id: String): Box {
        val prefix = "history.undo.$id"
        return Box(
            id = prefix,
            background = PaletteSlot.SURFACE,
            horizontal = true,
            pad = Space.L,
            spread = true,
            children = listOf(
                Label("$prefix.label", HistoryTexts.NOTICE_DELETED, TypeRole.BODY, PaletteSlot.TEXT_MUTED),
                Action(
                    id = "$prefix.button",
                    text = HistoryTexts.BUTTON_UNDO,
                    emphasis = Emphasis.PRIMARY,
                    intent = ScreenIntent.History(HistoryActions.UNDO, id),
                    kit = KitLook.RAISED,
                ),
            ),
        )
    }

    private fun tagOf(source: TranscriptionSource): String = when (source) {
        TranscriptionSource.LOCAL -> HistoryTexts.TAG_LOCAL
        TranscriptionSource.SERVER -> HistoryTexts.TAG_SERVER
    }
}
