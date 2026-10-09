package dev.breaker.dictation

import android.os.Bundle

/**
 * The launcher activity; it hosts the settings view.
 *
 * It obtains the store from [BreakerApp] via `[settingsStore]` and passes that,
 * with `this`, to the ui module's public entry so the whole screen is built in
 * one call. The window itself belongs to the app: the activity is simply the
 * container the view is placed into. The ui module owns no window of its own,
 * and neither does this launcher.
 */
class SettingsLauncherActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (applicationContext as BreakerApp).settingsStore
        setContentView(dev.breaker.dictation.ui.createSettingsView(this, store))
    }

    /**
     * Asks to switch the microphone service on. The activity is visible here, which is the
     * condition the platform sets for starting a microphone service. The answer is not shown
     * yet: the switch-on screen is another module's work.
     */
    override fun onStart() {
        super.onStart()
        (applicationContext as BreakerApp).dictationServiceController.arm()
    }
}
