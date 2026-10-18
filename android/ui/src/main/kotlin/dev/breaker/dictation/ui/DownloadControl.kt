package dev.breaker.dictation.ui

/**
 * The model download surface, as the app drives it.
 *
 * The app is the one that starts the download and hears from it as it runs, so it
 * needs a way to reach back into a view that draws the download: to say "draw it
 * again now" after a download makes progress or fails, and to say what the download
 * is doing. Both calls come from the main thread.
 *
 * The three download calls have empty defaults, so a view that draws no bar still
 * compiles and the calls do nothing to it.
 */
interface DownloadControl {
    /**
     * Re-reads the model providers and draws the screen again.
     *
     * The app calls this on resume and on every download callback, so a screen that
     * read "Not done yet" reads "Done" once the download finishes, without the view
     * being rebuilt.
     */
    fun refresh()

    /**
     * Puts focus on the model step's download button.
     *
     * Answers true when the button was there and was focused, and false when the
     * step has no button because the model is already ready.
     */
    fun focusDownload(): Boolean

    /**
     * Bytes are arriving: [doneMb] is the megabytes the app already shows, and [totalMb] is the total in
     * megabytes, or 0 when the total is not known.
     */
    fun downloadProgress(doneMb: Int, totalMb: Int) {}

    /** Every byte is in and the archive is being checked; the view draws the checking line. */
    fun downloadChecking() {}

    /** The download ended, either done or failed; the view reads the model and the line again. */
    fun downloadStopped() {}
}
