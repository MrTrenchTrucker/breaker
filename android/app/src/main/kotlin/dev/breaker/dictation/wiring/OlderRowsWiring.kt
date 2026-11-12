package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.core.port.OlderHistory
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.core.port.SyncService
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.JdkHttpTransport
import dev.breaker.dictation.sync.OlderHistorySyncAdapter
import dev.breaker.dictation.sync.OlderPullTransport

/**
 * Builds the app's older-rows adapter: [OlderHistory] over [OlderRowsTransport], which speaks
 * `GET /v1/transcriptions/older` over JdkHttpTransport to the stored server URL, with the user's
 * token from one addressed session per call.
 *
 * It lives in its own file so BreakerApp.kt can wire older rows without ever naming OlderRowsTransport
 * (the older-rows gate test pins that BreakerApp.kt and BreakerCompositionRoot.kt do not name it).
 *
 * The limits mirror the sync uploader's (CallLimits has no defaults): connect 10_000 ms and read
 * 30_000 ms as SyncHttpUploader uses, and a 5_242_880 byte reply cap for an older-rows page.
 *
 * The address and the token are read ONCE per call, from [addressedSession], and neither is kept.
 * It answers null unless [syncOn] (release builds: sync is not allowed, so no older read and no
 * button). [offered] is true only when an addressed session exists, so available() is true exactly
 * when sync is allowed and a session exists.
 *
 * [deleteRow] enqueues a delete through [syncService] and asks [worker] to drain; it answers true
 * only when both ran, false on any exception or missing service/worker.
 */
internal fun olderRowsTransport(
    syncOn: Boolean,
    settingsStore: SettingsStore,
    addressedSession: () -> AddressedSession?,
    syncService: SyncService,
    worker: dev.breaker.dictation.wiring.SyncWorker,
    transport: HttpTransport? = null,
): OlderHistory? =
    if (!syncOn) null else OlderHistorySyncAdapter(
        transport = OlderRowsTransport(
            transport = transport ?: JdkHttpTransport({ settingsStore.load().serverUrl }),
            limits = CallLimits(10_000, 30_000, 5_242_880L),
            addressedSession = addressedSession,
        ),
        deleteRow = { id ->
            syncService.enqueueDelete(id)
            worker.onSave()
            true
        },
        offered = { addressedSession() != null },
    )
