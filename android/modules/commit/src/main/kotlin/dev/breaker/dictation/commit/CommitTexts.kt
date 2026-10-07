package dev.breaker.dictation.commit

/**
 * Every fixed string and number the text commit uses.
 *
 * None of these can carry the committed text: they are constants, so a detail
 * shown to the user can never leak what the user dictated.
 */
internal object CommitTexts {
    /** What the user sees when the text went to the clipboard. */
    const val COPIED_TOAST: String = "Copied to clipboard."

    /** Detail when the focused field refused the text and the clipboard took it. */
    const val COPIED_AFTER_REFUSAL: String = "The field did not accept the text, so it was copied instead."

    /** Detail when neither the field nor the clipboard took the text. */
    const val FAILED_NOWHERE: String = "The text could not be put anywhere."

    /** Detail when the main-thread hop gave up before the steps ran. */
    const val FAILED_NOT_RESPONDING: String = "The screen was not responding, so the text was not sent."

    /**
     * Last API level (12L) that gives no copy confirmation of its own. From the
     * next level on the system shows one, so our own toast would be a duplicate.
     */
    const val LAST_SDK_WITHOUT_SYSTEM_CONFIRMATION: Int = 32

    /** Label attached to the clip, as the system lists it. */
    const val CLIP_LABEL: String = "Breaker"
}
