package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.SetupState

/*
 * What the first-run pages are drawn from: plain data, one value for each drawing.
 *
 * Nothing here asks the phone, the model or the app anything. The controller reads those
 * and hands the answers over in these values.
 */

/** The download page as it is drawn: the phase, the numbers and the sentence the app gave. */
internal data class DownloadShown(
    val phase: DownloadPhase,
    val doneMb: Int,
    val totalMb: Int,
    val line: String?,
)

/** A whole first-run page as it is drawn. */
internal data class FirstRunShown(
    val page: FirstRunPage,
    val step: Int,
    val state: SetupState,
    val notice: String?,
    val missing: List<MissingItem>,
    val download: DownloadShown,
)
