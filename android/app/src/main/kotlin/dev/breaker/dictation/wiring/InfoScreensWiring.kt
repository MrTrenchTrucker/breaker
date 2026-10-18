package dev.breaker.dictation.wiring

import android.app.Activity
import android.os.Build
import android.view.View
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.ui.createAboutView
import dev.breaker.dictation.ui.createHowItWorksView

/**
 * The wiring for the How it works and About screens.
 *
 * It shows either screen as the activity's content view, replacing whatever the
 * host was showing. Back from a screen is routed through this class: it clears
 * the shown flag and returns the host to Home, so closing a screen and closing
 * the activity are the same gesture.
 *
 * On API 33 and later it registers a back callback while a screen is shown and
 * unregisters it when the screen closes.
 */
internal class InfoScreensWiring(
    private val activity: Activity,
    private val settings: SettingsStore,
    private val homeView: () -> View?,
    private val onReturnHome: () -> Unit,
) {
    private var howItWorksShown: Boolean = false
    private var aboutShown: Boolean = false
    private var backCallback: android.window.OnBackInvokedCallback? = null

    /** Shows the How it works screen, or does nothing when it is already shown. */
    fun openHowItWorks() {
        if (howItWorksShown) return
        howItWorksShown = true
        val view = createHowItWorksView(activity, settings)
        activity.setContentView(view)
        applyInsets(view)
        registerBack()
    }

    /** Shows the About screen, or does nothing when it is already shown. */
    fun openAbout() {
        if (aboutShown) return
        aboutShown = true
        val view = createAboutView(activity, settings)
        activity.setContentView(view)
        applyInsets(view)
        registerBack()
    }

    /** Called when an info screen is closed by Back. Returns to Home. */
    fun closeInfo() {
        howItWorksShown = false
        aboutShown = false
        unregisterBack()
        onReturnHome()
    }

    /** Whether either info screen is shown, so the activity's back knows to route here. */
    fun isInfoShown(): Boolean = howItWorksShown || aboutShown

    private fun registerBack() {
        if (backCallback != null) return
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback {
                closeInfo()
            }
            backCallback = callback
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
        }
    }

    private fun unregisterBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    private fun applyInsets(view: View) {
        dev.breaker.dictation.wiring.applyInsets(view)
    }
}
