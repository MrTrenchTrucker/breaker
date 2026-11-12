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

/** The most rows the screen will ever ask for from the phone's own store. */
internal const val ROW_CAP = 300

/** The most server rows the screen will show in one visit. */
internal const val SERVER_ROW_CAP = 300

/** How long a deleted row can be undone, in milliseconds. */
internal const val UNDO_WINDOW_MS = 5000L

/** One row as it is drawn: its time already written out, and its text as stored. */
internal data class HistoryRow(
    val id: String,
    val time: String,
    val source: TranscriptionSource,
    val text: String,
    /** True when the row came from the server this visit and is not in the store. */
    val heldForVisitOnly: Boolean = false,
)

/** Which phase the Load more button is in: local, server, cap note, or gone. */
internal enum class LoadMorePhase { LOCAL, SERVER, CAP, GONE }

/**
 * The rows last read, in the order the store gave them, with the ids whose Undo
 * window is open, the number of rows asked for, whether that read came back
 * full, whether the last read failed, the ids of the rows opened in full, and
 * the server-phase facts for Load more phase two.
 */
internal data class HistoryState(
    val rows: List<HistoryRow>,
    val pending: Set<String>,
    val shown: Int,
    val fullPage: Boolean,
    val listFailed: Boolean = false,
    val opened: Set<String> = emptySet(),
    /** Which phase the Load more button is in. */
    val serverPhase: LoadMorePhase = LoadMorePhase.LOCAL,
    /** Whether the app may offer an older-rows read (signed in and sync on). */
    val serverOfferAllowed: Boolean = false,
    /** How many server rows have been shown this visit. */
    val serverRowsShown: Int = 0,
)

/** A message left by one action. It is drawn once, above the rows. */
internal enum class HistoryNotice(val text: String, val color: PaletteSlot) {
    COPIED(HistoryTexts.NOTICE_COPIED, PaletteSlot.STATE_SENT),
    COPY_FAILED(HistoryTexts.NOTICE_COPY_FAILED, PaletteSlot.DANGER),
    DELETE_FAILED(HistoryTexts.NOTICE_DELETE_FAILED, PaletteSlot.DANGER),
    LIST_FAILED(HistoryTexts.NOTICE_LIST_FAILED, PaletteSlot.DANGER),
    SERVER_SIGNED_OUT(HistoryTexts.NOTICE_SIGNED_OUT, PaletteSlot.DANGER),
    SERVER_UNAVAILABLE(HistoryTexts.NOTICE_SERVER_UNAVAILABLE, PaletteSlot.DANGER),
    SERVER_BUSY(HistoryTexts.NOTICE_SERVER_BUSY, PaletteSlot.DANGER),
    SERVER_EMPTY_PAGE(HistoryTexts.NOTICE_SERVER_EMPTY_MORE, PaletteSlot.DANGER),
}
