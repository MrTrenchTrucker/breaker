package dev.breaker.dictation

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.breaker.dictation.service.NotificationRoute
import dev.breaker.dictation.settings.ModelCatalog
import dev.breaker.dictation.ui.ModelStepControl
import dev.breaker.dictation.wiring.ACCESSIBILITY_SERVICE_COMPONENT
import dev.breaker.dictation.wiring.BreakerSwitchAdapter
import dev.breaker.dictation.wiring.IconLaunchOnce
import dev.breaker.dictation.wiring.ModelDownloadNotice
import dev.breaker.dictation.wiring.ModelSentences
import dev.breaker.dictation.wiring.StoreModelReady
import dev.breaker.shared.models.ModelRegistry
import dev.breaker.dictation.wiring.isIconLaunch
import dev.breaker.dictation.wiring.paddingFor

class SettingsLauncherActivity : android.app.Activity(), ModelDownloadNotice {

    private lateinit var downloadStatus: TextView
    private lateinit var settingsColumn: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private var historyShown: Boolean = false
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var downloadSentence: String? = null
    private lateinit var iconLaunch: IconLaunchOnce

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        iconLaunch = IconLaunchOnce(savedInstanceState?.getBoolean(ICON_LAUNCH_DONE_KEY, false) ?: false)
        val app = applicationContext as BreakerApp
        downloadStatus = TextView(this)
        downloadStatus.text = ""
        val modelReady = StoreModelReady(app.compositionRoot.modelStore) { app.settingsStore.load().modelSize }
        val onboarding = dev.breaker.dictation.ui.createOnboardingView(
            this,
            BreakerSwitchAdapter(app.armedSwitch),
            ACCESSIBILITY_SERVICE_COMPONENT,
            modelReady = { modelReady.isReady() },
            downloadLine = { downloadSentence },
            modelDownloadMb = { ModelCatalog.byId(app.settingsStore.load().modelSize)?.sizeMb?.let(ModelSentences::mibToMb) ?: ModelSentences.mibToMb(ModelRegistry.SMALL.sizeMb) },
            onDownloadModel = { app.tileHost.requestDownload() },
            settings = app.settingsStore,
        )
        onboardingView = onboarding
        val history = Button(this)
        history.text = HISTORY_LABEL
        history.setOnClickListener { openHistory() }
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(onboarding)
        column.addView(downloadStatus)
        column.addView(history)
        val settings = dev.breaker.dictation.ui.createSettingsView(this, app.settingsStore)
        column.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this)
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        settingsColumn = column
        settingsScroll = scroll
        applyInsets(scroll)
        if (intent?.getStringExtra(NotificationRoute.EXTRA_ROUTE) == NotificationRoute.ROUTE_MODEL) {
            (onboarding as? ModelStepControl)?.focusDownload()
        }
        if (savedInstanceState == null && NotificationRoute.opensHistory(intent?.getStringExtra(NotificationRoute.EXTRA_ROUTE))) {
            openHistory()
        }
    }

    override fun onStart() {
        super.onStart()
        val app = applicationContext as BreakerApp
        val launch = intent
        iconLaunch.onStart(
            isIconLaunch(launch?.action, launch?.categories.orEmpty(), launch?.getStringExtra(NotificationRoute.EXTRA_ROUTE)),
            { app.armedSwitch.switchOn() },
            { app.armedSwitch.armAtStart() },
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
        (onboardingView as? ModelStepControl)?.refresh()
    }

    override fun onPause() {
        super.onPause()
        val app = applicationContext as BreakerApp
        app.tileHost.setDownloadListener(null)
    }

    // Older Back path; Android 13 and newer Back goes through the callback registered while history shows.
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (historyShown) closeHistory() else super.onBackPressed()
    }

    private lateinit var onboardingView: android.view.View

    private fun openHistory() {
        if (historyShown) return
        historyShown = true
        val app = applicationContext as BreakerApp
        val historyView = dev.breaker.dictation.ui.createHistoryView(this, app.historyStore)
        setContentView(historyView)
        applyInsets(historyView)
        registerBack()
    }

    private fun closeHistory() {
        if (!historyShown) return
        historyShown = false
        unregisterBack()
        setContentView(settingsScroll)
        applyInsets(settingsScroll)
    }

    private fun registerBack() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback { closeHistory() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
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
        downloadStatus.text = sentence
        (onboardingView as? ModelStepControl)?.refresh()
    }

    override fun done() {
        downloadSentence = null
        downloadStatus.text = ""
        (onboardingView as? ModelStepControl)?.refresh()
    }

    override fun failed(sentence: String) {
        downloadSentence = sentence
        downloadStatus.text = sentence
        (onboardingView as? ModelStepControl)?.refresh()
    }

    override fun progress(sentence: String, doneMb: Int, totalMb: Int) {
        // The launcher line shows the progress sentence the same way as the first one.
        downloading(sentence)
    }

    private fun applyInsets(view: android.view.View) {
        val rootInsets = view.rootWindowInsets
        if (rootInsets != null) {
            applyPadding(view, rootInsets)
        }
        view.setOnApplyWindowInsetsListener { v, insets ->
            applyPadding(v, insets)
            insets
        }
    }

    private fun applyPadding(view: android.view.View, insets: WindowInsets) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
        val padding = paddingFor(
            dev.breaker.dictation.wiring.Insets(bars.left, bars.top, bars.right, bars.bottom),
            dev.breaker.dictation.wiring.Insets(cutout.left, cutout.top, cutout.right, cutout.bottom),
        )
        view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
    }

    private companion object {
        const val HISTORY_LABEL: String = "History"
        const val ICON_LAUNCH_DONE_KEY: String = "icon-launch-done"
    }
}
