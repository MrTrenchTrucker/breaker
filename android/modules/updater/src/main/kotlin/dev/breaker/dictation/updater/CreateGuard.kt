package dev.breaker.dictation.updater

/**
 * The create-once guard: one facade per ledger file in one process.
 *
 * It is a plain holder with no lock. The app creates the facade on one thread, so the holder needs no synchronization.
 */
internal object CreateGuard {
    private val claimedKeys: MutableSet<String> = mutableSetOf()

    /** Claims [ledgerKey] for one facade. A second claim of the same key throws IllegalStateException. */
    internal fun claim(ledgerKey: String) {
        if (!claimedKeys.add(ledgerKey)) throw IllegalStateException("a facade already exists for this ledger file in this process")
    }

    /** Gives back [ledgerKey], so that a later claim of it succeeds. */
    internal fun release(ledgerKey: String) {
        claimedKeys.remove(ledgerKey)
    }
}
