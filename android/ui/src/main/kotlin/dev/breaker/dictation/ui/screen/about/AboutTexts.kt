package dev.breaker.dictation.ui.screen.about

/** The words the About screen shows, in plain ASCII. */
internal object AboutTexts {
    /** The screen's title. */
    const val TITLE = "About"

    /** The first line: what Breaker does. */
    const val LINE_1 = "Breaker turns your voice into text, hands free, on this phone."

    /** The second line: the license. */
    const val LINE_2 = "Open source under the Apache License 2.0."

    /** The row that asks the updater whether a newer build exists. */
    const val CHECK_LABEL = "Check for updates"

    /** The row that installs the build the last check offered. */
    const val INSTALL = "Install update"

    /** The sentence while a check is in progress. */
    const val CHECKING = "Checking for updates..."

    /** The sentence when a check finished and nothing is offered. */
    const val UP_TO_DATE = "Breaker is up to date."

    /** The sentence when a check offered a build. */
    const val OFFERED = "An update is available."

    /** The sentence while an install is in progress. */
    const val INSTALLING = "Installing the update..."

    /** The sentence when the last action failed. */
    const val FAILED = "That did not work. Try again."
}
