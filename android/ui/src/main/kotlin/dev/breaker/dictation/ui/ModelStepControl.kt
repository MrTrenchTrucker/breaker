package dev.breaker.dictation.ui

/**
 * The model step of the set-up walk-through, as the app drives it.
 *
 * The speech model is the first step of the walk-through. Whether it is on the
 * phone is not a fact this module keeps: the app passes a provider to
 * [createOnboardingView], and the answer is read again on every drawing. But the
 * app is the one that starts the download and hears from it as it runs, so it
 * needs two ways to reach back into the view: to say "draw the step again now"
 * after a download makes progress or fails, and to put focus on the step's
 * download button when the user is sent to it.
 *
 * The view [createOnboardingView] returns answers this interface; the app casts
 * that view to it. Both calls come from the main thread.
 */
interface ModelStepControl {
    /**
     * Re-reads the model providers and draws the walk-through again.
     *
     * The app calls this on resume and on every download callback, so a step that
     * read "Not done yet" reads "Done" once the download finishes, without the view
     * being rebuilt. The line under the button is re-read the same way.
     */
    fun refresh()

    /**
     * Puts focus on the model step's download button.
     *
     * Answers true when the button was there and was focused, and false when the
     * step has no button because the model is already ready.
     */
    fun focusDownload(): Boolean
}
