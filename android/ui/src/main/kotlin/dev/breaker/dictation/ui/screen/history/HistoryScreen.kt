package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * The history screen, described rather than drawn.
 *
 * The tree is built from a [HistoryState] and a notice and from nothing else: no
 * phone, no store and no clock. Each row is one block with its time, its source,
 * its text and two actions. A row whose Undo window is open is replaced, in the
 * same place, by a block that offers Undo.
 */

private const val SCREEN_ID = "history"
private const val TITLE_ID = "history.title"
private const val NOTICE_ID = "history.notice"
private const val EMPTY_ID = "history.empty"
private const val LIMIT_ID = "history.limit"
private const val MORE_ID = "history.more"

/** The id of a notice carried over from a read whose draw was dropped, so it can be told from the notice. */
internal const val CARRIED_NOTICE_ID = "history.notice.carried"

/** The notice label of this screen, or null when it has none. */
internal fun Screen.noticeLabel(): Label? = nodes.firstOrNull { it.id == NOTICE_ID } as? Label

/**
 * This screen with [carried] added once: right after the notice, or right after the title when
 * there is no notice. A screen whose notice already has the same text is returned as it is.
 */
internal fun Screen.withCarriedNotice(carried: Label): Screen {
    if (noticeLabel()?.text == carried.text) return this
    val notice = nodes.indexOfFirst { it.id == NOTICE_ID }
    val at = if (notice >= 0) notice + 1 else minOf(1, nodes.size)
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
                add(Label(TITLE_ID, HistoryTexts.TITLE, TypeRole.DISPLAY, PaletteSlot.TEXT))
                // A failed first read has no rows to show, so the sentence stands in for the list.
                val failedFirst = state.listFailed && state.rows.isEmpty()
                val message = notice ?: if (failedFirst) HistoryNotice.LIST_FAILED else null
                if (message != null) add(Label(NOTICE_ID, message.text, TypeRole.BODY, message.color))
                if (state.rows.isEmpty() && !state.listFailed) {
                    add(Label(EMPTY_ID, HistoryTexts.EMPTY, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
                }
                state.rows.forEach { row ->
                    add(if (row.id in state.pending) undoBlock(row.id) else rowBlock(row))
                }
                if (state.fullPage && state.shown >= ROW_CAP) {
                    add(Label(LIMIT_ID, HistoryTexts.LIMIT_NOTE, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
                } else if (state.fullPage) {
                    add(
                        Action(
                            id = MORE_ID,
                            text = HistoryTexts.BUTTON_LOAD_MORE,
                            intent = ScreenIntent.History(HistoryActions.MORE),
                        ),
                    )
                }
            },
        )

    /** One saved transcription: its time, its source, its text and the two actions on it. */
    private fun rowBlock(row: HistoryRow): Box {
        val prefix = "history.row.${row.id}"
        val blank = row.text.isBlank()
        return Box(
            id = prefix,
            background = PaletteSlot.SURFACE,
            children = listOf(
                Label("$prefix.time", row.time, TypeRole.MONO, PaletteSlot.TEXT_MUTED),
                Label("$prefix.tag", tagOf(row.source), TypeRole.BODY, PaletteSlot.TEXT_MUTED),
                Label(
                    "$prefix.text",
                    if (blank) HistoryTexts.BLANK_TEXT else row.text,
                    TypeRole.BODY,
                    if (blank) PaletteSlot.TEXT_MUTED else PaletteSlot.TEXT,
                ),
                Action(
                    id = "$prefix.copy",
                    text = HistoryTexts.BUTTON_COPY,
                    intent = ScreenIntent.History(HistoryActions.COPY, row.id),
                ),
                Action(
                    id = "$prefix.delete",
                    text = HistoryTexts.BUTTON_DELETE,
                    emphasis = Emphasis.DESTRUCTIVE,
                    intent = ScreenIntent.History(HistoryActions.DELETE, row.id),
                ),
            ),
        )
    }

    /** What a row becomes while its Undo window is open: the deleted message and the one action. */
    private fun undoBlock(id: String): Box {
        val prefix = "history.undo.$id"
        return Box(
            id = prefix,
            background = PaletteSlot.SURFACE,
            children = listOf(
                Label("$prefix.label", HistoryTexts.NOTICE_DELETED, TypeRole.BODY, PaletteSlot.TEXT_MUTED),
                Action(
                    id = "$prefix.button",
                    text = HistoryTexts.BUTTON_UNDO,
                    emphasis = Emphasis.PRIMARY,
                    intent = ScreenIntent.History(HistoryActions.UNDO, id),
                ),
            ),
        )
    }

    private fun tagOf(source: TranscriptionSource): String = when (source) {
        TranscriptionSource.LOCAL -> HistoryTexts.TAG_LOCAL
        TranscriptionSource.SERVER -> HistoryTexts.TAG_SERVER
    }
}
