package dev.breaker.dictation.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.breaker.dictation.BreakerApp

/**
 * The microphone foreground service that stays on while dictation is switched on.
 *
 * It only holds the foreground notification and the platform calls. What to do with each start
 * request is [ServiceStartHandler], and the switch is the app's [DictationServiceController].
 */
class DictationForegroundService : Service() {

    private val controller: DictationServiceController
        get() = (application as BreakerApp).dictationServiceController

    private val host: ServiceHost = object : ServiceHost {
        override fun enterForeground() {
            startForeground(
                DictationNotification.NOTIFICATION_ID,
                DictationNotification.build(this@DictationForegroundService),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }

        override fun leaveForegroundAndStop() {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        override fun stopWithoutForeground() {
            stopSelf()
        }
    }

    // Built on first use: the application is not reachable before the service is attached.
    private val handler: ServiceStartHandler by lazy(LazyThreadSafetyMode.NONE) {
        ServiceStartHandler(controller, host)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.onStart(intent?.action)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.serviceEnded()
        super.onDestroy()
    }
}
