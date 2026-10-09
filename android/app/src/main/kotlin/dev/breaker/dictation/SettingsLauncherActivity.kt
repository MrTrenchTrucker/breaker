package dev.breaker.dictation

import android.os.Bundle

/**
 * The launcher activity; it hosts the settings view.
 *
 * It obtains the store from [BreakerApp] via `[settingsStore]` and hands that,
 * with `this`, to the ui module's public entry so the whole screen is built in
 * one call. The window itself belongs to the app — the activity is simply the
 * container the view is placed into; the ui module owns no window of its own,
 * and neither does this launcher.
 */
class SettingsLauncherActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (applicationContext as BreakerApp).settingsStore
        setContentView(dev.breaker.dictation.ui.createSettingsView(this, store))
    }
}
