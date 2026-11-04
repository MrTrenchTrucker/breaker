package dev.breaker.dictation.host

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.breaker.dictation.BreakerApp
import dev.breaker.dictation.SettingsLauncherActivity

/**
 * The return activity of the phone sign-in. The website sends the phone back to the breaker
 * hand-off link, and this activity takes that link: it shows nothing, hands the link to the
 * return flow of the composition root, brings the launcher back to the front and finishes, on
 * every outcome. It keeps no task of its own and is not kept in recents.
 */
class HandoffReturnActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val app = applicationContext as BreakerApp
            app.compositionRoot.handoffReturnFlow.handle(intent?.action, intent?.dataString)
        } finally {
            startActivity(Intent(this, SettingsLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            finish()
        }
    }
}
