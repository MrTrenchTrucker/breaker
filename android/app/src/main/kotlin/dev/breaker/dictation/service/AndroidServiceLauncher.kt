package dev.breaker.dictation.service

import android.content.Context
import android.content.Intent

/**
 * The [ServiceLauncher] over the platform: starts the microphone service as a foreground service and stops it.
 *
 * The platform can refuse a start (background start rules, a missing permission); every such failure
 * is a [RuntimeException] and is answered as [LaunchResult.Refused], never thrown.
 */
class AndroidServiceLauncher(private val context: Context) : ServiceLauncher {

    override fun launch(): LaunchResult {
        try {
            val started = context.startForegroundService(serviceIntent().setAction(ACTION_ARM))
            return if (started == null) LaunchResult.Refused else LaunchResult.Launched
        } catch (e: RuntimeException) {
            return LaunchResult.Refused
        }
    }

    override fun halt() {
        try {
            context.stopService(serviceIntent())
        } catch (e: RuntimeException) {
            // Nothing is left to stop if the platform refuses.
        }
    }

    private fun serviceIntent(): Intent = Intent(context, DictationForegroundService::class.java)
}
