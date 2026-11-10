package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.JdkHttpTransport
import dev.breaker.dictation.sync.PullTransport

/**
 * Builds the app's [PullTransport]: GET /v1/transcriptions over JdkHttpTransport to the stored
 * server URL, with the user's token from one addressed session per call.
 *
 * It lives in its own file so BreakerApp.kt can wire the pull without ever naming PullHttpTransport
 * (the pull-transport gate test pins that BreakerApp.kt and BreakerCompositionRoot.kt do not name it).
 *
 * The limits mirror the sync uploader's (CallLimits has no defaults): connect 10_000 ms and read
 * 30_000 ms as SyncHttpUploader uses, and a 5_242_880 byte reply cap for a pull page — the server
 * stops a page when its running size passes 4 MiB (ADR-035), so one full page is a little under
 * 4 MiB of text; the cap adds margin and stays under CallLimits.MAX_BYTES_REPLY.
 *
 * The address and the token are read ONCE per call, from [addressedSession], and neither is kept.
 */
internal fun pullTransport(
    settingsStore: SettingsStore,
    addressedSession: () -> AddressedSession?,
    transport: HttpTransport? = null,
): PullTransport =
    PullHttpTransport(
        transport = transport ?: JdkHttpTransport({ settingsStore.load().serverUrl }),
        limits = CallLimits(10_000, 30_000, 5_242_880L),
        addressedSession = addressedSession,
    )
