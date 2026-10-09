package dev.breaker.dictation

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.breaker.dictation.wiring.ModelDownloadNotice
import dev.breaker.dictation.service.NotificationRoute
import dev.breaker.dictation.wiring.ACCESSIBILITY_SERVICE_COMPONENT
import dev.breaker.dictation.wiring.BreakerSwitchAdapter
import dev.breaker.dictation.wiring.ModelSentences
import dev.breaker.dictation.wiring.isIconLaunch

class SettingsLauncherActivity : android.app.Activity(), ModelDownloadNotice {

    private lateinit var downloadStatus: TextView
    private lateinit var settingsColumn: LinearLayout
    private var historyShown: Boolean = false
    private var backCallback: android.window.OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as BreakerApp
        val download = Button(this)
        download.text = DOWNLOAD_LABEL
        download.setOnClickListener { app.tileHost.requestDownload() }
        downloadStatus = TextView(this)
        downloadStatus.text = ""
        val onboarding = dev.breaker.dictation.ui.createOnboardingView(
            this,
            BreakerSwitchAdapter(app.armedSwitch),
            ACCESSIBILITY_SERVICE_COMPONENT,
        )
        val history = Button(this)
        history.text = HISTORY_LABEL
        history.setOnClickListener { openHistory() }
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(download)
        column.addView(onboarding)
        column.addView(downloadStatus)
        column.addView(history)
        val settings = dev.breaker.dictation.ui.createSettingsView(this, app.settingsStore)
        column.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        settingsColumn = column
        if (intent?.getStringExtra(NotificationRoute.EXTRA_ROUTE) == NotificationRoute.ROUTE_MODEL) {
            download.isFocusableInTouchMode = true
            download.requestFocus()
        }
        if (savedInstanceState == null && NotificationRoute.opensHistory(intent?.getStringExtra(NotificationRoute.EXTRA_ROUTE))) {
            openHistory()
        }
    }

    override fun onStart() {
        super.onStart()
        val app = applicationContext as BreakerApp
        val launch = intent
        if (isIconLaunch(launch?.action, launch?.categories.orEmpty(), launch?.getStringExtra(NotificationRoute.EXTRA_ROUTE))) {
            app.armedSwitch.switchOn()
        } else {
            app.armedSwitch.armAtStart()
        }
        app.tileHost.onLauncherVisible()
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext as BreakerApp
        app.tileHost.setDownloadListener(this)
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

    private fun openHistory() {
        if (historyShown) return
        historyShown = true
        val app = applicationContext as BreakerApp
        setContentView(dev.breaker.dictation.ui.createHistoryView(this, app.historyStore))
        registerBack()
    }

    private fun closeHistory() {
        if (!historyShown) return
        historyShown = false
        unregisterBack()
        setContentView(settingsColumn)
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

    override fun downloading() {
        downloadStatus.text = ModelSentences.DOWNLOADING
    }

    override fun done() {
        downloadStatus.text = ""
    }

    override fun failed(sentence: String) {
        downloadStatus.text = sentence
    }

    private companion object {
        const val DOWNLOAD_LABEL: String = "Download the speech model"
        const val HISTORY_LABEL: String = "History"
    }
}
