package dev.breaker.dictation

import android.util.Log
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.history.SqliteHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The application entry; the only place the concrete modules are built for the
 * running app.
 *
 * It owns a single [dev.breaker.dictation.BreakerCompositionRoot], constructed
 * lazily from the app's files directory and the concrete history store, so
 * wiring happens once and only when a store is first requested. The history
 * store is built here because [SqliteHistoryStore.create] needs an Android
 * context; the composition root itself stays plain JVM and receives the store
 * typed as the core [HistoryStore] port. The app also owns the one start-up
 * purge: it is scheduled on an app-lifetime coroutine scope off the main
 * thread, and a failure is logged — not thrown — so a damaged database can
 * never take the app down with it.
 */
class BreakerApp : android.app.Application() {

    private val purgeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val history: SqliteHistoryStore by lazy {
        SqliteHistoryStore.create(applicationContext, SystemClockAdapter)
    }

    val compositionRoot: dev.breaker.dictation.BreakerCompositionRoot by lazy {
        BreakerCompositionRoot(filesDir, history)
    }

    /** The app's settings, proxied through the lazily-built composition root. */
    val settingsStore: SettingsStore get() = compositionRoot.settingsStore

    /** The app's history: the concrete store the composition root exposes. */
    val historyStore: HistoryStore get() = history

    override fun onCreate() {
        super.onCreate()
        scheduleAppStartPurge(
            purgeScope,
            purge = { history.purgeExpired() },
            onFailure = { e -> Log.w("BreakerApp", "start-up purge failed", e) },
        )
    }
}
