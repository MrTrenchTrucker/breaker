package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * What the history screen is drawn from, and the limits its handler keeps to.
 *
 * The state is plain data. The handler writes each row's time out before it gets
 * here, so the screen never needs a formatter or a clock.
 */

/** How many rows a first page holds, and how many each Load more adds. */
internal const val PAGE_SIZE = 50

/** The most rows the screen will ever ask for. */
internal const val ROW_CAP = 300

/** How long a deleted row can be undone, in milliseconds. */
internal const val UNDO_WINDOW_MS = 5000L

/** One row as it is drawn: its time already written out, and its text as stored. */
internal data class HistoryRow(
    val id: String,
    val time: String,
    val source: TranscriptionSource,
    val text: String,
)

/**
 * The rows last read, in the order the store gave them, with the ids whose Undo
 * window is open, the number of rows asked for, whether that read came back
 * full, and whether the last read failed.
 */
internal data class HistoryState(
    val rows: List<HistoryRow>,
    val pending: Set<String>,
    val shown: Int,
    val fullPage: Boolean,
    val listFailed: Boolean = false,
)

/** A message left by one action. It is drawn once, above the rows. */
internal enum class HistoryNotice(val text: String, val color: PaletteSlot) {
    COPIED(HistoryTexts.NOTICE_COPIED, PaletteSlot.STATE_SENT),
    COPY_FAILED(HistoryTexts.NOTICE_COPY_FAILED, PaletteSlot.DANGER),
    DELETE_FAILED(HistoryTexts.NOTICE_DELETE_FAILED, PaletteSlot.DANGER),
    LIST_FAILED(HistoryTexts.NOTICE_LIST_FAILED, PaletteSlot.DANGER),
}
