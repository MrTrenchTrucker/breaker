package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.render.AndroidSetupPlatform
import dev.breaker.dictation.ui.render.FirstRunHostView
import dev.breaker.dictation.ui.screen.firstrun.FirstRunController
import dev.breaker.dictation.ui.screen.firstrun.NoSwitch
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.screen.onboarding.SetupScreen
import dev.breaker.dictation.ui.theme.isNightMode
import dev.breaker.dictation.ui.theme.storedTheme

/**
 * Builds the first-run view for [context]: the welcome, the permission pages and the model
 * download page, in that order, ending when the speech model is on the phone.
 *
 * The phone's own light or dark mode is read from [context] once, here. The first run has no
 * on-off switch, so the set-up handler is given a switch that is never on. What is granted is
 * read from the phone each time a page is drawn, and the download is the app's: [onDownloadModel]
 * starts it, [downloadLine] and [modelDownloadMb] describe it, and [modelReady] says when the
 * model is on the phone. [onFinished] is called once, when that happens on the download page.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param accessibilityServiceComponent the accessibility service's flattened component name, as
 *   the phone lists it among the enabled services. It is compared whole.
 * @param modelReady answers whether the speech model is on the phone right now.
 * @param downloadLine answers the sentence under the download, or null.
 * @param modelDownloadMb answers the size in decimal megabytes the download page shows.
 * @param onDownloadModel asks the app to start the download.
 * @param settings the app's settings store, through which the microphone prompt is recorded.
 * @param onFinished called once, when the first run is over.
 * @return a view that shows the first run and can be put in a layout as it stands.
 */
// The parameter types are written out in full, for the same reason as in the other entries.
fun createFirstRunView(
    context: android.content.Context,
    accessibilityServiceComponent: String,
    modelReady: () -> Boolean,
    downloadLine: () -> String?,
    modelDownloadMb: () -> Int,
    onDownloadModel: () -> Unit,
    settings: dev.breaker.dictation.core.port.SettingsStore,
    onFinished: () -> Unit,
): android.view.View {
    val phoneIsDark = isNightMode(context.resources.configuration.uiMode)
    val theme = storedTheme(settings, phoneIsDark)
    val platform = AndroidSetupPlatform(context, accessibilityServiceComponent)
    val handler = SetupIntentHandler(platform, NoSwitch, modelReady, downloadLine, modelDownloadMb, onDownloadModel, SetupScreen(), settings)
    val controller = FirstRunController(handler, modelReady, downloadLine, onDownloadModel, onFinished)
    return FirstRunHostView(context, controller, theme, phoneIsDark)
}
