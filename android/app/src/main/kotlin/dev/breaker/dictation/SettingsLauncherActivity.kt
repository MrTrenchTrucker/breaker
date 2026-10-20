package dev.breaker.dictation

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.host.MainLooperPost
import dev.breaker.dictation.host.addHomeLevelSink
import dev.breaker.dictation.host.copyToClip
import dev.breaker.dictation.host.createHomeMic
import dev.breaker.dictation.host.downloadControlOf
import dev.breaker.dictation.host.selectedModelMb
import dev.breaker.dictation.host.windowHeightAfterBarsDp
import dev.breaker.dictation.service.NotificationRoute
import dev.breaker.dictation.ui.createHomeView
import dev.breaker.dictation.wiring.ACCESSIBILITY_SERVICE_COMPONENT
import dev.breaker.dictation.wiring.BreakerSwitchAdapter
import dev.breaker.dictation.wiring.FirstRunDoneStore
import dev.breaker.dictation.wiring.FirstRunGate
import dev.breaker.dictation.wiring.HomeTakeWiring
import dev.breaker.dictation.wiring.IconLaunchOnce
import dev.breaker.dictation.wiring.InfoScreensWiring
import dev.breaker.dictation.wiring.ModelDownloadNotice
import dev.breaker.dictation.wiring.ModelSentences
import dev.breaker.dictation.wiring.StoreModelReady
import dev.breaker.dictation.wiring.TakePortAdapter
import dev.breaker.dictation.wiring.appTakenClipboard
import dev.breaker.dictation.wiring.applyInsets
import dev.breaker.dictation.wiring.paintWindowBackground
import dev.breaker.dictation.wiring.formatTime
import dev.breaker.dictation.wiring.isIconLaunch
import dev.breaker.dictation.wiring.swapInColumn
import java.io.File

class SettingsLauncherActivity : android.app.Activity(), ModelDownloadNotice {

    private lateinit var settingsColumn: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private var homeView: android.view.View? = null
    private var historyShown: Boolean = false
    private var settingsShown: Boolean = false
    private var firstRunShown: Boolean = false
    private var firstRunView: android.view.View? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var homeLevelSink: dev.breaker.dictation.wiring.LevelSink? = null
    private var downloadSentence: String? = null
    private var pendingRefusal: String? = null
    private lateinit var iconLaunch: IconLaunchOnce
    private lateinit var takeWiring: HomeTakeWiring
    private lateinit var homeRebuilt: dev.breaker.dictation.wiring.Rebuilt<android.view.View>
    private lateinit var settingsRebuilt: dev.breaker.dictation.wiring.Rebuilt<android.view.View>
    private lateinit var infoScreens: InfoScreensWiring
    private lateinit var settingsView: android.view.View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        iconLaunch = IconLaunchOnce(savedInstanceState?.getBoolean(ICON_LAUNCH_DONE_KEY, false) ?: false)
        val app = applicationContext as BreakerApp
        paintWindowBackground(this, app.settingsStore.load().themeMode)
        val modelReady = StoreModelReady(app.compositionRoot.modelStore) { app.settingsStore.load().modelSize }
        val firstRunDone = FirstRunDoneStore(File(app.filesDir, FirstRunDoneStore.FILE_NAME))
        when (FirstRunGate.decide(firstRunDone.isDone(), modelReady.isReady())) {
            FirstRunGate.Decision.FIRST_RUN -> { showFirstRun(app, modelReady, firstRunDone); return }
            FirstRunGate.Decision.MARK_DONE_QUIETLY -> firstRunDone.markDone()
            FirstRunGate.Decision.NORMAL -> Unit
        }
        takeWiring = HomeTakeWiring(
            take = { TakePortAdapter(app.compositionRoot.dictation.runner) },
            history = { app.historyStore },
            clipboard = appTakenClipboard(this),
            switch = app.armedSwitch,
            owner = app.tileHost.takeOwner,
            background = app.tileHost.takeWorker,
            main = MainLooperPost(),
            modelReady = { modelReady.isReady() },
        )
        homeRebuilt = dev.breaker.dictation.wiring.Rebuilt { createHomeView(
            this,
            BreakerSwitchAdapter(app.armedSwitch),
            modelReady = { modelReady.isReady() },
            lastDictationAt = { app.historyStore.list(1).firstOrNull()?.createdAt },
            lastTranscript = { app.historyStore.list(1).firstOrNull()?.text },
            transcriptTime = { app.historyStore.list(1).firstOrNull()?.createdAt?.let { formatTime(it) } },
            onCopy = { sentence -> copyToClip(this, sentence) },
            onHistory = { openHistory() },
            onSettings = { openSettings() },
            onHowItWorks = { openHowItWorks() },
            onAbout = { openAbout() },
            onTap = { takeWiring.onTap() },
            micView = createHomeMic(this, takeWiring).view,
            usableHeightDp = windowHeightAfterBarsDp(this),
            settings = app.settingsStore,
        ) }
        val home = homeRebuilt.current
        homeView = home
        homeLevelSink = addHomeLevelSink(
            takeWiring,
            app.tileHost,
            MainLooperPost(),
            { app.compositionRoot.dictation.runner.sessionState == DictationState.RECORDING },
        )
        infoScreens = InfoScreensWiring(this, app.settingsStore, { homeView }) { setContentView(homeView); applyInsets(homeView!!) }
        takeWiring.attach(home as dev.breaker.dictation.ui.HomeControl)
        setContentView(home)
        applyInsets(home)
        settingsRebuilt = dev.breaker.dictation.wiring.Rebuilt { dev.breaker.dictation.ui.createSettingsView(
            this,
            app.settingsStore,
            modelReady = { modelReady.isReady() },
            downloadLine = { downloadSentence },
            onDownloadModel = { app.tileHost.requestDownload() },
        ) }
        settingsView = settingsRebuilt.current
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(settingsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this)
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        settingsColumn = column
        settingsScroll = scroll
        if (intent?.getStringExtra(NotificationRoute.EXTRA_ROUTE) == NotificationRoute.ROUTE_MODEL) {
            openSettings()
            (settingsView as? dev.breaker.dictation.ui.DownloadControl)?.focusDownload()
        }
        if (savedInstanceState == null && NotificationRoute.opensHistory(intent?.getStringExtra(NotificationRoute.EXTRA_ROUTE))) {
            openHistory()
        }
    }

    private fun showFirstRun(app: BreakerApp, modelReady: StoreModelReady, done: FirstRunDoneStore) {
        val view = dev.breaker.dictation.ui.createFirstRunView(
            this,
            ACCESSIBILITY_SERVICE_COMPONENT,
            modelReady = modelReady::isReady,
            downloadLine = { downloadSentence },
            modelDownloadMb = { selectedModelMb(app) },
            onDownloadModel = app.tileHost::requestDownload,
            settings = app.settingsStore,
            onFinished = { done.markDone(); recreate() },
        )
        firstRunView = view
        firstRunShown = true
        setContentView(view)
        applyInsets(view)
    }

    override fun onStart() {
        super.onStart()
        val app = applicationContext as BreakerApp
        val launch = intent
        iconLaunch.onStart(
            isIconLaunch(launch?.action, launch?.categories.orEmpty(), launch?.getStringExtra(NotificationRoute.EXTRA_ROUTE)),
            { app.armedSwitch.switchOn() },
            { app.armedSwitch.armAtStart() },
            { sentence -> pendingRefusal = sentence },
        )
        app.tileHost.onLauncherVisible()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ICON_LAUNCH_DONE_KEY, iconLaunch.isDone)
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext as BreakerApp
        app.tileHost.setDownloadListener(this)
        when {
            firstRunShown -> downloadControlOf(true, firstRunView, settingsView)?.refresh()
            settingsShown -> downloadControlOf(false, firstRunView, settingsView)?.refresh()
            else -> (homeView as? dev.breaker.dictation.ui.HomeControl)?.refresh()
        }
        val refusal = pendingRefusal ?: app.refusalHold.take()
        if (refusal != null) {
            pendingRefusal = null
            (homeView as? dev.breaker.dictation.ui.HomeControl)?.showLine(refusal)
        }
    }

    override fun onPause() {
        super.onPause()
        val app = applicationContext as BreakerApp
        app.tileHost.setDownloadListener(null)
    }

    override fun onDestroy() {
        homeLevelSink?.let { (applicationContext as BreakerApp).tileHost.removeHomeLevelSink(it) }
        homeLevelSink = null
        super.onDestroy()
    }

    // Older Back path; Android 13 and newer Back goes through the callback registered while an overlay shows.
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        when {
            this::infoScreens.isInitialized && infoScreens.isInfoShown() -> infoScreens.closeInfo()
            historyShown -> closeHistory()
            settingsShown -> closeSettings()
            else -> if ((homeView as? dev.breaker.dictation.ui.HomeControl)?.closeMenu() != true) super.onBackPressed()
        }
    }

    private fun openHistory() {
        if (historyShown) return
        historyShown = true
        val app = applicationContext as BreakerApp
        val historyView = dev.breaker.dictation.ui.createHistoryView(this, app.historyStore, app.settingsStore)
        setContentView(historyView)
        applyInsets(historyView)
        registerBack()
    }

    /** One back callback serves both overlays. History returns to where it was opened from. */
    private fun closeHistory() {
        if (!historyShown) return
        historyShown = false
        if (settingsShown) {
            setContentView(settingsScroll)
            applyInsets(settingsScroll)
        } else {
            unregisterBack()
            setContentView(homeView)
            applyInsets(homeView!!)
        }
    }

    private fun openSettings() {
        if (settingsShown) return
        settingsShown = true
        setContentView(settingsScroll)
        applyInsets(settingsScroll)
        registerBack()
    }

    private fun closeSettings() {
        if (!settingsShown) return
        settingsShown = false
        if (!historyShown) unregisterBack()
        homeView = homeRebuilt.again()
        takeWiring.attach(homeView as dev.breaker.dictation.ui.HomeControl)
        settingsView = swapInColumn(settingsColumn, settingsView, settingsRebuilt.again())
        setContentView(homeView)
        applyInsets(homeView!!)
    }
    private fun openHowItWorks() = infoScreens.openHowItWorks()
    private fun openAbout() = infoScreens.openAbout()
    private fun registerBack() {
        if (backCallback != null) return
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback {
                when {
                    infoScreens.isInfoShown() -> infoScreens.closeInfo()
                    historyShown -> closeHistory()
                    settingsShown -> closeSettings()
                }
            }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback,
            )
        }
    }

    private fun unregisterBack() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }
    override fun downloading(sentence: String) {
        downloadSentence = sentence
        downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadProgress(0, 0)
    }

    override fun done() {
        downloadSentence = null
        downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadStopped()
    }

    override fun failed(sentence: String) {
        downloadSentence = sentence
        downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadStopped()
    }

    override fun progress(sentence: String, doneMb: Int, totalMb: Int) {
        downloadSentence = sentence
        val control = downloadControlOf(firstRunShown, firstRunView, settingsView)
        if (sentence == ModelSentences.CHECKING) {
            control?.downloadChecking()
        } else {
            control?.downloadProgress(doneMb, totalMb)
        }
    }
    private companion object {
        const val ICON_LAUNCH_DONE_KEY: String = "icon-launch-done"
    }
}
