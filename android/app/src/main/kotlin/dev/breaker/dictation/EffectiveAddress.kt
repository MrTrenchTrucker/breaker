package dev.breaker.dictation

import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.transport.TcpConnectivityProbe

/**
 * The one effective server address, and the connectivity probe that answers it.
 *
 * The answer source is [TcpConnectivityProbe.cachedAnswer] — the CACHE ONLY, never a dial — wrapped
 * so that null means "not answering": an entry that was never measured, or whose measurement
 * expired, is treated exactly like a measured refusal. A read of the address happens on auth,
 * hand-off, sync, status and the sign-in browser, on whatever thread calls it; only `refresh()`
 * dials, and the composition root runs that on its own background.
 *
 * One instance is built by the composition root and every reader takes [serverUrl] or [probe] from
 * it, so there is exactly one probe and one address decision in the app. The source gate
 * (`EffectiveAddressSourceGateTest`) fails if any other main file reads the stored `serverUrl`.
 */
internal class EffectiveAddress(
    private val settingsStore: SettingsStore,
    clock: Clock,
) {
    /** The effective server address, read on every call. Never dials. */
    val serverUrl: () -> String = {
        effectiveServerUrl(settingsStore.load()) { address -> probe.cachedAnswer(address) == true }
    }

    /** The connectivity probe of the dictation path: one instance, shared by every reader. */
    val probe: TcpConnectivityProbe = TcpConnectivityProbe(
        serverUrlProvider = serverUrl,
        clock = clock,
        serverAddressProvider = { settingsStore.load().serverAddressList() },
        recordLastGood = { persistServerLastGood(settingsStore, it) },
    )
}
