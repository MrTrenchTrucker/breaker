package dev.breaker.dictation.ui.screen.about

/** The action names a [dev.breaker.dictation.ui.screen.ScreenIntent.About] may carry. */
internal object AboutActions {
    /** Ask the updater whether a newer build exists. */
    const val CHECK = "CHECK"

    /** Install the build the last check offered. */
    const val INSTALL = "INSTALL"
}