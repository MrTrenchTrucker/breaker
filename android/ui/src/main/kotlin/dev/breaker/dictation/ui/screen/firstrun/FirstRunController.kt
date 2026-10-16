package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.onboarding.SETUP_NOTICE_ID
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.screen.onboarding.SetupNotice
import dev.breaker.dictation.ui.screen.onboarding.SetupState

/*
 * The first-run controller: the one object that knows which page is shown and what the user did.
 *
 * It keeps the page, the last reading of the phone, the speech model and the download sentence,
 * and the notice of the current page. It asks the set-up handler to act on taps and reads the
 * drawn screen back from it. It holds no Android type and stores nothing.
 *
 * The download itself is the app's. This class only decides whether the app may be asked to
 * start it, so a download that is running is never started a second time, and it reports the
 * first run finished once the model is on the phone while the download page is shown.
 */

/**
 * Drives the first-run pages. Call it from the main thread only; it does no locking.
 *
 * @param handler the set-up handler that acts on taps and holds the phone's permission state.
 * @param modelReady answers whether the speech model is on the phone right now.
 * @param downloadLine answers the sentence the app wants shown on the download page, or null.
 * @param onDownloadModel asks the app to start the download.
 * @param onFinished called once, when the model is on the phone while the download page is shown.
 * @param tracker the memory of the download page; the app shares one instance so that a page
 *   built again still sees a download that is running.
 */
internal class FirstRunController(
    private val handler: SetupIntentHandler,
    private val modelReady: () -> Boolean,
    private val downloadLine: () -> String?,
    private val onDownloadModel: () -> Unit,
    private val onFinished: () -> Unit,
    private val tracker: DownloadTracker = DownloadTracker(),
) {
    /** The page the user is on; starts on the welcome page. */
    var page: FirstRunPage = FirstRunPage.WELCOME
        private set

    private var state: SetupState = handler.lastState()
    private var notice: String? = null
    private var lastModelReady: Boolean = false
    private var lastLine: String? = null
    private var finished: Boolean = false

    init {
        read()
    }

    /** The page as it was last read. Nothing is asked of the phone or the model. */
    fun shown(): FirstRunShown = FirstRunShown(
        page = page,
        step = FirstRunPages.stepOf(page),
        state = state,
        notice = notice,
        missing = FirstRunMissing.of(state),
        download = DownloadShown(tracker.phase, tracker.doneMb, tracker.totalMb, lastLine),
    )

    /** Asks the phone, the model and the download sentence again, then finishes if the model is ready on the download page. */
    fun refresh() {
        read()
        if (page == FirstRunPage.DOWNLOAD && lastModelReady && !finished) {
            finished = true
            tracker.onModelReady()
            onFinished()
        }
    }

    /** Moves to the next page on the welcome and the permission pages, then asks again. Nothing happens on the download page. */
    fun advance() {
        if (page == FirstRunPage.DOWNLOAD) return
        page = FirstRunPages.next(page) ?: return
        refresh()
    }

    /** Does what [action] asks through the set-up handler, and takes the notice and the state from the screen it draws. */
    fun tap(action: String) {
        val screen = handler.handle(ScreenIntent.Setup(action))
        notice = noticeOf(screen)
        state = handler.lastState()
    }

    /** Asks the app to start the download, only when the download page allows a start. A start that throws is stopped and says so. */
    fun tapDownload() {
        if (!tracker.requestStart()) return
        try {
            onDownloadModel()
        } catch (failure: Exception) {
            tracker.onStopped(false, null)
            notice = SetupNotice.COULD_NOT_DOWNLOAD.text
        }
    }

    /** Shows a switch-on that the app refused as the notice of this page. The handler keeps it until a tap or a read that finds the switch on. */
    fun switchRefused(sentence: String) {
        val screen = handler.switchRefused(sentence)
        notice = noticeOf(screen)
        state = handler.lastState()
    }

    /** Records progress reported by the app. */
    fun downloadProgress(doneMb: Int, totalMb: Int) {
        tracker.onProgress(doneMb, totalMb)
    }

    /** Records that the app is checking the downloaded model. */
    fun downloadChecking() {
        tracker.onChecking()
    }

    /** Records that the download has stopped, then asks again. */
    fun downloadStopped() {
        tracker.onStopped(guarded(lastModelReady, modelReady), guarded(lastLine, downloadLine))
        refresh()
    }

    /** Reads the handler's screen, the model and the download sentence; a read that throws keeps its last answer. */
    private fun read() {
        try {
            notice = noticeOf(handler.current())
            state = handler.lastState()
        } catch (failure: Exception) {
            // Keeps the last reading of the screen.
        }
        lastModelReady = guarded(lastModelReady, modelReady)
        lastLine = guarded(lastLine, downloadLine)
    }

    /** The answer of [provider], or [last] when the provider throws. */
    private fun <T> guarded(last: T, provider: () -> T): T =
        try {
            provider()
        } catch (failure: Exception) {
            last
        }

    /** The text of the notice node in [screen], or null when the screen shows none. */
    private fun noticeOf(screen: Screen): String? =
        (screen.nodes.flatMap { it.flatten() }.firstOrNull { it.id == SETUP_NOTICE_ID } as? Label)?.text
}
