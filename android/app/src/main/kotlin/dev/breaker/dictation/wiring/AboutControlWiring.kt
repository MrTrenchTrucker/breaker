package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.port.UpdateChecker
import dev.breaker.dictation.ui.AboutControl
import dev.breaker.dictation.updater.InstallResult

/**
 * The About screen's check and install rows, wired to the app's updater.
 *
 * The class holds the control's interface, never a view, so a plain JVM test drives it with a fake.
 * It holds the check facts ([checking], [failed], [upToDate] and [updateAvailable]), the release that the
 * last available check gave ([held]) and whether an install is running ([installing]). The control's seven
 * facts come from those. The install row is offered only when a release is held and nothing is running.
 *
 * Nothing draws before [bind]: `bind()` is the only caller of `control.bind` and the only place the first
 * `show()` is reached, and [onCheck] and [onInstall] return at once while the screen is unbound. A check or
 * an install runs on [background] through the [checker] provider, so the updater is built off the main
 * thread, and its result comes back on [main]. The updater's answer to an install, and the end of its
 * session, arrive through [installAnswers] while the screen is open; [release] stops that listening when
 * the screen closes. A cancelled session frees the install row, so the row is offered again.
 */
internal class AboutControlWiring(
    private val control: AboutControl,
    private val background: Background,
    private val main: MainPost,
    private val checker: () -> UpdateChecker,
    private val isAdmin: () -> Boolean,
    private val installAnswers: InstallAnswerRelay,
) {
    private companion object {
        /** The installer's status for a session that succeeded: the process is replaced, so nothing is drawn. */
        const val STATUS_SUCCESS: Int = 0

        /** The installer's status for a session the user cancelled at the system confirmation. */
        const val STATUS_ABORTED: Int = 3
    }

    private var checking: Boolean = false
    private var failed: Boolean = false
    private var upToDate: Boolean = false
    private var updateAvailable: Boolean = false
    private var installing: Boolean = false
    private var held: ReleaseInfo? = null
    private var bound: Boolean = false

    /** Holds the row callbacks, the install answers and the finished session, and draws the first screen. Nothing draws before this call. */
    fun bind() {
        bound = true
        installAnswers.listen(::onInstallAnswer)
        installAnswers.listenFinished(::onInstallFinished)
        control.bind(::onCheck, ::onInstall)
        show()
    }

    /** Stops listening for the install answer and the finished session. The screen calls this when it closes; an answer or a finish after that is dropped. */
    fun release() {
        installAnswers.release()
    }

    /** Runs one forced check, unless the screen is unbound or a check is already running. */
    fun onCheck() {
        if (!bound || checking) return
        checking = true
        held = null
        installing = false
        updateAvailable = false
        failed = false
        upToDate = false
        show()
        try {
            background.submit {
                val result = try {
                    checker().check(true)
                } catch (e: Exception) {
                    null
                }
                main.post {
                    checking = false
                    when (result) {
                        is UpdateCheckResult.UpToDate -> upToDate = true
                        is UpdateCheckResult.Failed -> failed = true
                        is UpdateCheckResult.Available -> {
                            updateAvailable = true
                            held = result.release
                        }
                        null -> failed = true
                    }
                    show()
                }
            }
        } catch (e: Exception) {
            checking = false
            failed = true
            show()
        }
    }

    /** Starts the install of the held release on [background]. A no-op while unbound, with nothing held, or while a check or an install runs. */
    fun onInstall() {
        val offer: ReleaseInfo = held ?: return
        if (!bound || checking || installing) return
        installing = true
        show()
        try {
            background.submit { runInstall(offer) }
        } catch (e: Exception) {
            installFailed()
        }
    }

    /** Runs on [background]: hands the release to the checker, and posts a failure back if the checker throws. */
    private fun runInstall(offer: ReleaseInfo) {
        try {
            checker().install(offer)
        } catch (e: Exception) {
            main.post { installFailed() }
        }
    }

    /** Runs on the main thread when the updater answers. A refusal ends the attempt; a start keeps the row installing. */
    private fun onInstallAnswer(result: InstallResult) {
        if (!installing) return
        if (result is InstallResult.Refused) installFailed()
    }

    /**
     * Runs on the main thread when the installer's session ends. Success is left to the process replacement;
     * a cancel frees the row and offers it again without a failure; any other status is a failure.
     */
    fun onInstallFinished(status: Int) {
        if (!installing) { return }
        if (status == STATUS_SUCCESS) return
        if (status == STATUS_ABORTED) {
            installing = false
            failed = false
            show()
        } else {
            installFailed()
        }
    }

    /** Ends a failed install: the row is free again and shows the failure. */
    private fun installFailed() {
        installing = false
        failed = true
        show()
    }

    private fun show() {
        control.show(
            admin = isAdmin(),
            updateAvailable = updateAvailable,
            installReady = held != null && !checking && !installing,
            checking = checking,
            installing = installing,
            failed = failed,
            upToDate = upToDate,
        )
    }
}
