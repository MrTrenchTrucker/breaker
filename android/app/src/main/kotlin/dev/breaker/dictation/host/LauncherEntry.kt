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
import dev.breaker.dictation.wiring.ProbeRefreshingSettingsStore
import dev.breaker.dictation.wiring.Rebuilt
import dev.breaker.dictation.wiring.StoreModelReady
import dev.breaker.dictation.wiring.formatTime
import dev.breaker.dictation.wiring.modelSelectWriterFor
import dev.breaker.dictation.wiring.offeredSpeechModels
import dev.breaker.dictation.wiring.selectOnDone
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
            onSignOut = signOutAction(
                authService = app.compositionRoot.authService,
                sessionLive = app.compositionRoot.sessionLive,
                clearAll = { app.clearQueueAndTombstones() },
                refresh = { (homeView() as? dev.breaker.dictation.ui.HomeControl)?.refresh() ?: Unit },
                pushForSignout = { app.signOutPush() },
            ),
        )
    }
}

internal fun buildSettingsRebuilt(
    activity: Activity,
    app: BreakerApp,
    modelReady: StoreModelReady,
    downloadLine: () -> String?,
    onSignIn: () -> Unit,
): Rebuilt<android.view.View> {
    val refreshing = ProbeRefreshingSettingsStore(
        delegate = app.settingsStore,
        onServerUrlChanged = { app.compositionRoot.refreshProbeInBackground() },
    )
    return Rebuilt {
        createSettingsView(
            activity,
            refreshing,
            modelReady = { modelReady.isReady() },
            downloadLine = downloadLine,
            onThemeChanged = dev.breaker.dictation.wiring.repaintOnThemeChange(activity, app.settingsStore),
            onDownloadModel = { app.tileHost.requestDownload() },
            sessionLive = { app.compositionRoot.sessionLive.liveNow() },
            onSignIn = onSignIn,
            serverAddressStatus = { app.compositionRoot.serverAddressStatus() },
            offeredModels = { offeredSpeechModels() },
            installedModel = { id -> app.compositionRoot.modelStore.isInstalled(id) },
            downloadingModelId = { app.tileHost.downloadingModelId() },
            onDownloadModelId = { id -> app.tileHost.requestDownload(id) },
            onDeleteModel = { id -> app.tileHost.deleteModel(id) },
            onUseModel = { id -> app.tileHost.useModel(id) },
        )
    }
}

/** The first run's download by id: the download makes the model it installed the model in use. */
internal fun firstRunDownloadById(app: BreakerApp): (String) -> Unit = { id ->
    app.tileHost.requestDownload(id, selectOnDone(modelSelectWriterFor(app.settingsStore, app.compositionRoot.modelStore)))
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

/**
 * One bounded probe refresh each time Settings opens; when it settles, [redraw] runs once on the main
 * thread, and only while [stillShown] says the screen is still the one on show.
 */
internal fun redrawSettingsAfterProbe(app: BreakerApp, stillShown: () -> Boolean, redraw: () -> Unit) {
    app.probeStatusRefresh.start { MainLooperPost().post { if (stillShown()) redraw() } }
}

/**
 * The answer to a model delete, once it has come back on the main thread: the settings control is drawn again,
 * whether the delete removed the files or was refused, so the rows show what is on the phone now. [control]
 * reads the settings control at the time of the answer; nothing is drawn when there is none.
 */
internal fun redrawOnDelete(control: () -> dev.breaker.dictation.ui.DownloadControl?): (Boolean) -> Unit = { _ -> control()?.refresh() }
