package dev.breaker.dictation

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import dev.breaker.dictation.service.NotificationRoute
import dev.breaker.dictation.wiring.isIconLaunch

/**
 * The launcher activity; it hosts the settings view and one button of its own.
 *
 * The screen is a vertical column: the button that downloads the speech model, then the ui module's
 * settings view, which takes the rest of the height. The button asks [BreakerApp.tileHost] for the
 * download and shows nothing itself; the download reports through its own notification. A launch that
 * carries [NotificationRoute.ROUTE_MODEL] puts the button in focus, also while the screen is in touch
 * mode. The route is read when the activity is created.
 *
 * The store comes from [BreakerApp] via `[settingsStore]` and is passed, with `this`, to the ui
 * module's public entry. The window itself belongs to the app: the activity is simply the container
 * the views are placed into. The ui module owns no window of its own, and neither does this launcher.
 */
class SettingsLauncherActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as BreakerApp
        val download = Button(this)
        download.text = DOWNLOAD_LABEL
        download.setOnClickListener { app.tileHost.requestDownload() }
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(download)
        val settings = dev.breaker.dictation.ui.createSettingsView(this, app.settingsStore)
        column.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        if (intent?.getStringExtra(NotificationRoute.EXTRA_ROUTE) == NotificationRoute.ROUTE_MODEL) {
            download.isFocusableInTouchMode = true
            download.requestFocus()
        }
    }

    /**
     * Opening the app from its launcher icon switches the microphone service on again, also after the
     * user switched it off; any other start (a notification tap, the tile) switches it on unless the
     * user switched it off. The activity is visible here, which is the condition the platform sets for
     * starting a microphone service. The answer is not shown yet: the switch-on screen is another
     * module's work. The tile host is told too, so a tile that could not be shown before the user
     * allowed "display over other apps" is shown now.
     *
     * An activity created by the launcher icon switches dictation on at every start, including a return
     * from recents or a rotation; an activity created by a notification or the tile does not. This has
     * not been verified on a device.
     */
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

    private companion object {
        /** The label of the download button, in plain words for the user. */
        const val DOWNLOAD_LABEL: String = "Download the speech model"
    }
}
