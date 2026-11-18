package dev.breaker.dictation.ui.screen.about

/**
 * What the About screen knows about the updater, as plain facts the app hands in.
 *
 * Every value is false by default, so a default state is the two-line screen exactly as it was
 * before the rows existed: no row and no sentence. The app owns the updater and the sign-in, so
 * nothing here reaches either; the screen only draws what it is told.
 *
 * [updateAvailable] drives the status sentence and [installReady] drives the install row, so a
 * ready build with no update available draws nothing.
 *
 * [updateAvailable] and [installReady] carry no version text. A version belongs to the app that
 * owns the updater, and a string nothing renders is a leak path, so the screen never sees one.
 */
internal data class AboutState(
    /** Whether the person may see the rows at all. */
    val admin: Boolean = false,

    /** Whether an update is available, which drives the status sentence. */
    val updateAvailable: Boolean = false,

    /** Whether the app can install an available update, which drives the install row. */
    val installReady: Boolean = false,

    /** Whether a check is in progress. */
    val checking: Boolean = false,

    /** Whether an install is in progress. */
    val installing: Boolean = false,

    /** Whether the last action failed. */
    val failed: Boolean = false,

    /** Whether a check finished and nothing is offered. */
    val upToDate: Boolean = false,

    /** Whether the last install answer was a refusal, which draws the refusal sentence in place of the failure sentence. */
    val installRefused: Boolean = false,
)