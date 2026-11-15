package dev.breaker.dictation

import dev.breaker.dictation.host.MainLooperPost
import dev.breaker.dictation.http.JdkHttpTransport
import dev.breaker.dictation.wiring.ServiceStatusHome
import dev.breaker.dictation.wiring.ServiceStatusProbe
import dev.breaker.dictation.wiring.syncAllowed

/**
 * The one service-status wiring of the process, built from the app on first use.
 *
 * The launcher activity owns no field for it (its file is at the line cap): its `onResume` calls
 * `serviceStatusHome().onResume(...)` with the home view it is showing and its `onPause` calls
 * `serviceStatusHome().onPause()`. The gate is the foreground rule of ADR-044: sync on, a live
 * session and a configured server address, all read afresh on each resume. There is no scheduler,
 * no alarm and no new component.
 *
 * One [ServiceStatusHome] is kept per app, so a pause reaches the same instance the resume armed
 * and the queued ask is really taken back. It is a function and not a `by lazy` extension property:
 * inside the delegate block of a lazy extension property there is no implicit receiver to capture,
 * so the app's own members are reached through the [BreakerApp] this function is called on.
 */
private val homes: MutableMap<BreakerApp, ServiceStatusHome> = HashMap()
private val homesLock: Any = Any()

internal fun BreakerApp.serviceStatusHome(): ServiceStatusHome {
    val app = this
    synchronized(homesLock) {
        return homes.getOrPut(app) {
            ServiceStatusHome(
                asker = ServiceStatusProbe(
                    transport = JdkHttpTransport({ app.settingsStore.load().serverUrl }),
                    addressedSession = app.compositionRoot.addressedSession,
                ),
                asked = {
                    syncAllowed(app.applicationInfo.flags) && app.compositionRoot.sessionLive.liveNow() && app.settingsStore.load().serverUrl.isNotBlank()
                },
                post = MainLooperPost(),
                clock = SystemClockAdapter,
            )
        }
    }
}
