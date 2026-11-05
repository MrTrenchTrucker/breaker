package dev.breaker.dictation.host

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens [url] in the phone's browser. Returns false when no app takes the link or the system
 * refuses it. The url is never logged.
 */
private fun openInBrowser(context: Context, url: String): Boolean {
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (refused: ActivityNotFoundException) {
        false
    } catch (refused: SecurityException) {
        false
    }
}

/** The sign-in start view and the screen that drives it. The launcher adds [view] to its column and keeps [screen]. */
internal class SignInStart(val view: android.view.View, val screen: dev.breaker.dictation.wiring.SignInStartScreen)

/** Builds the sign-in start view and its screen for [activity]; the view is not added to any column here. */
internal fun mountSignInStart(activity: android.app.Activity, app: dev.breaker.dictation.BreakerApp): SignInStart {
    lateinit var screen: dev.breaker.dictation.wiring.SignInStartScreen
    val view: android.view.View = dev.breaker.dictation.ui.createSignInStartView(activity, app.settingsStore, { app.settingsStore.load().serverUrl }, { screen.onTap() })
    screen = dev.breaker.dictation.wiring.SignInStartScreen(
        dev.breaker.dictation.wiring.SignInStartWiring(app.compositionRoot.signInHolder),
        view as dev.breaker.dictation.ui.SignInStartControl,
        { url -> openInBrowser(activity, url) },
        { app.settingsStore.load().serverUrl },
    )
    return SignInStart(view, screen)
}
