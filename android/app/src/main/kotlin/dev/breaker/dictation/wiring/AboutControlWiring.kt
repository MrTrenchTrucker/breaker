package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.port.UpdateChecker
import dev.breaker.dictation.ui.AboutControl

/**
 * The About screen's check row, wired to the app's updater.
 *
 * The class holds the control's interface, never a view, so a plain JVM test drives it with a fake.
 * It holds four facts — [checking], [failed], [upToDate] and [updateAvailable] — and hands the other
 * three of the control's seven as false: the Install button is not wired yet, so the screen is never
 * offered one it cannot tap.
 *
 * Nothing draws before [bind]: `bind()` is the only caller of `control.bind` and the only place the
 * first `show()` is reached, and [onCheck] returns at once while the screen is unbound. A check runs
 * on [background] through the [checker] provider, so the updater is built off the main thread, and
 * its answer comes back on [main].
 */
internal class AboutControlWiring(
    private val control: AboutControl,
    private val background: Background,
    private val main: MainPost,
    private val checker: () -> UpdateChecker,
    private val isAdmin: () -> Boolean,
) {
    private var checking: Boolean = false
    private var failed: Boolean = false
    private var upToDate: Boolean = false
    private var updateAvailable: Boolean = false
    private var bound: Boolean = false

    /** Holds the two row callbacks and draws the first screen. Nothing draws before this call. */
    fun bind() {
        bound = true
        control.bind(::onCheck, ::onInstall)
        show()
    }

    /** Runs one forced check, unless the screen is unbound or a check is already running. */
    fun onCheck() {
        if (!bound || checking) return
        checking = true
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
                        is UpdateCheckResult.Available -> updateAvailable = true
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

    /** The install row is not shown, so this is unreachable; kept because [AboutControl] takes two. */
    fun onInstall() = Unit

    private fun show() {
        control.show(
            admin = isAdmin(),
            updateAvailable = updateAvailable,
            installReady = false,
            checking = checking,
            installing = false,
            failed = failed,
            upToDate = upToDate,
        )
    }
}
