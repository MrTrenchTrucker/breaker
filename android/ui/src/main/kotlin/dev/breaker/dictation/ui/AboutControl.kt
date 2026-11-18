package dev.breaker.dictation.ui

/**
 * What the view from `createAboutView` answers to the app: the updater's About-screen rows.
 *
 * The app owns the updater and tells this module what to show through plain values only: whether
 * the person is an admin, whether an update is available, whether the install can run, and the
 * phase of the last check or install. The rows, their words and the status sentence are this
 * module's, so the app never writes one. Every call runs on the main thread; each stores its value
 * and draws the screen again.
 *
 * Before [bind] the two callbacks are absent, so a tap on a row does nothing. Before the first
 * [show] the screen is exactly the two lines it has always shown: no row and no sentence.
 */
interface AboutControl {
    /**
     * Holds the two callbacks a row tap calls: [onCheck] for "Check for updates" and [onInstall] for
     * the install row. Each is called at most once per tap and never while a check or an install is
     * in progress. A tap before this call does nothing.
     */
    fun bind(onCheck: () -> Unit, onInstall: () -> Unit)

    /**
     * Shows the screen for the app's seven plain facts and draws it again. [admin] says whether the
     * person may see the rows at all; [updateAvailable] says an update is available, which can draw
     * the status sentence; [installReady] says the install can run, which draws the Install row only
     * together with [updateAvailable]; [checking],
     * [installing] and [failed] are the phase of the last action; [upToDate] says a check finished
     * and no update is available. The first call replaces the two-line screen; a later call redraws
     * from the facts it is given then.
     */
    fun show(admin: Boolean, updateAvailable: Boolean, installReady: Boolean, checking: Boolean, installing: Boolean, failed: Boolean, upToDate: Boolean)

    /** Redraws the screen from the last facts the app handed in. */
    fun refresh()
}
