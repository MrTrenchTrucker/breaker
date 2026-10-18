package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.screen.history.HistoryTexts

/**
 * The words the controls kit shows, in the sentence case the ledger approved. The kit draws them
 * in capitals itself (see drawnLabel), so the stored form is the sentence. The
 * destructive button's word already exists - the history screen's delete - so the kit refers to
 * it instead of holding a second copy.
 */
internal object ControlTexts {
    const val BUTTON_CANCEL = "Cancel"
    const val BUTTON_OPEN_SETTINGS = "Open settings"
    const val BADGE_READY = "Ready"
    const val BADGE_PHONE_ONLY = "Phone only"
    const val BADGE_FAILED = "Failed"

    /** The words a screen reader says for a back arrow, such as the one in a title bar. */
    const val BACK_ARROW = "Back"

    /** The destructive button's word, already the history screen's. */
    val BUTTON_DELETE: String get() = HistoryTexts.BUTTON_DELETE
}
