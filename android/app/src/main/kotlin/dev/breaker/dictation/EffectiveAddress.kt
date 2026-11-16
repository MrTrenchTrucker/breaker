package dev.breaker.dictation

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.transport.ProbeSelection
import dev.breaker.dictation.transport.TcpConnectivityProbe
import dev.breaker.dictation.ui.screen.settings.ServerAddressStatus

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
 *
 * [serverAddressStatus] is the settings screen's reading of the same cache: ONE snapshot of
 * [TcpConnectivityProbe.cachedAnswer] per entry, never a dial. The pure decision is
 * [serverAddressStatusOf]; the property reads the store fresh on every call, so an edited list
 * takes effect at the next drawing.
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

    /**
     * The one probe as the per-entry answer source the status reads; the dial paths delegate too, so
     * a test double can stand here and fail loudly if a dial is ever asked for.
     */
    private val answerSource: ServerAddressProbe = object : ServerAddressProbe {
        override fun cachedAnswer(address: String): Boolean? = probe.cachedAnswer(address)
        override fun isServerReachable(): Boolean = probe.isServerReachable()
        override fun refresh(): Boolean = probe.refresh()
    }

    /**
     * The status of the stored addresses the settings screen draws, read on every call.
     *
     * One snapshot per entry over the freshly loaded list. Never dials: only the cache-only
     * [TcpConnectivityProbe.cachedAnswer] is read, so a cold cache can never put socket work on
     * the thread that draws.
     */
    val serverAddressStatus: () -> ServerAddressStatus = {
        serverAddressStatusOf(settingsStore.load(), answerSource)
    }
}

/**
 * The per-entry cache read the status needs.
 *
 * [isServerReachable] and [refresh] are the dial paths. The status never calls them; they are on
 * the seam so a test double can fail the test if that promise is ever broken.
 */
internal interface ServerAddressProbe {
    /** The measured answer for [address] inside the cache window, or null when none was measured. */
    fun cachedAnswer(address: String): Boolean?

    /** A dial path the status must never call. */
    fun isServerReachable(): Boolean

    /** The other dial path the status must never call. */
    fun refresh(): Boolean
}

/**
 * Answers what the stored addresses were last seen to be doing, from ONE snapshot of [probe]'s
 * cached answers over [settings].
 *
 * The list is read once and every entry is asked once, in list order; nothing is re-read to choose
 * the winner. [ServerAddressStatus.InUse] only when some entry measured true, and the winner is the
 * first such entry in list order ([ProbeSelection.select]; its last-good fallback is never reached here, because some entry answered). Only when EVERY
 * entry measured false — each non-null — is it [ServerAddressStatus.NoneAnswered]; a single
 * unmeasured (null) entry leaves it [ServerAddressStatus.Checking], because a measurement has not
 * been made yet. An empty list is [ServerAddressStatus.Checking]: there is no entry and therefore
 * no answer.
 */
internal fun serverAddressStatusOf(settings: AppSettings, probe: ServerAddressProbe): ServerAddressStatus {
    val entries = settings.serverAddressList()
    if (entries.isEmpty()) return ServerAddressStatus.Checking
    val snapshot = entries.associateWith { probe.cachedAnswer(it) }
    if (snapshot.values.any { it == true }) {
        return ServerAddressStatus.InUse(ProbeSelection.select(entries, entries.first()) { snapshot[it] == true })
    }
    return if (snapshot.values.all { it == false }) ServerAddressStatus.NoneAnswered else ServerAddressStatus.Checking
}
