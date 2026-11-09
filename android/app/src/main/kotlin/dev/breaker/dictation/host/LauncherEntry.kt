package dev.breaker.dictation.host

import android.app.Activity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.breaker.dictation.BreakerApp
import dev.breaker.dictation.ui.createHomeView
import dev.breaker.dictation.ui.createSettingsView
import dev.breaker.dictation.wiring.BreakerSwitchAdapter
import dev.breaker.dictation.wiring.HomeScreenMic
import dev.breaker.dictation.wiring.HomeTakeWiring
import dev.breaker.dictation.wiring.Rebuilt
import dev.breaker.dictation.wiring.StoreModelReady
import dev.breaker.dictation.wiring.formatTime
import dev.breaker.dictation.wiring.signOutAction

/**
 * The launcher's Home and settings entry, pulled out of SettingsLauncherActivity so the activity
 * keeps headroom under its line cap. The functions build the pieces the launcher assigns to its
 * own fields, in the same order as before: [buildHomeEntry] the Home [Rebuilt], [buildSettingsRebuilt]
 * the settings [Rebuilt], [buildSettingsColumn] the column, the scroll and the sign-in start.
 * `homeView` and `downloadLine` are suppliers the launcher reads when the pieces are rebuilt, because
 * the fields they name change after these functions return.
 */
internal data class SettingsColumnParts(
    val column: LinearLayout,
    val scroll: ScrollView,
    val signInStart: SignInStart,
)

internal fun buildHomeEntry(
    activity: Activity,
    app: BreakerApp,
    takeWiring: HomeTakeWiring,
    modelReady: StoreModelReady,
    mic: HomeScreenMic,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onHowItWorks: () -> Unit,
    onAbout: () -> Unit,
    onSignIn: () -> Unit,
    homeView: () -> android.view.View?,
): Rebuilt<android.view.View> {
    return Rebuilt {
        createHomeView(
            activity,
            BreakerSwitchAdapter(app.armedSwitch),
            modelReady = { modelReady.isReady() },
            lastDictationAt = { app.historyStore.list(1).firstOrNull()?.createdAt },
            lastTranscript = { app.historyStore.list(1).firstOrNull()?.text },
            transcriptTime = { app.historyStore.list(1).firstOrNull()?.createdAt?.let { formatTime(it) } },
            onCopy = { sentence -> copyToClip(activity, sentence) },
            onHistory = onHistory,
            onSettings = onSettings,
            onHowItWorks = onHowItWorks,
            onAbout = onAbout,
            onTap = { takeWiring.onTap() },
            micView = mic.view,
            onShownMode = { mode -> mic.setTheme(mode) },
            usableHeightDp = windowHeightAfterBarsDp(activity),
            settings = app.settingsStore,
            sessionLive = { app.compositionRoot.sessionLive.liveNow() },
            onSignIn = onSignIn,
            onSignOut = signOutAction(app.compositionRoot.authService, app.compositionRoot.sessionLive) {
                (homeView() as? dev.breaker.dictation.ui.HomeControl)?.refresh() ?: Unit
            },
        )
    }
}

internal fun buildSettingsRebuilt(
    activity: Activity,
    app: BreakerApp,
    modelReady: StoreModelReady,
    downloadLine: () -> String?,
): Rebuilt<android.view.View> {
    return Rebuilt {
        createSettingsView(
            activity,
            app.settingsStore,
            modelReady = { modelReady.isReady() },
            downloadLine = downloadLine,
            onThemeChanged = dev.breaker.dictation.wiring.repaintOnThemeChange(activity, app.settingsStore),
            onDownloadModel = { app.tileHost.requestDownload() },
        )
    }
}

internal fun buildSettingsColumn(
    activity: Activity,
    app: BreakerApp,
    settingsView: android.view.View,
): SettingsColumnParts {
    val column = LinearLayout(activity)
    column.orientation = LinearLayout.VERTICAL
    column.addView(settingsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val signInStart = mountSignInStart(activity, app)
    val signInView = signInStart.view
    column.addView(signInView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val scroll = ScrollView(activity)
    scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return SettingsColumnParts(column, scroll, signInStart)
}
