package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo

/**
 * The one rollback offer kept for the admin rollback button, or none. It holds nothing else.
 * The check and the admin button may run on different threads, so the reference is volatile.
 */
internal class HeldRollback {
    @Volatile private var release: ReleaseInfo? = null

    /** Keeps [release] as the held offer, replacing any earlier one. */
    fun hold(release: ReleaseInfo) {
        this.release = release
    }

    /** Drops the held offer, if there is one. */
    fun clear() {
        this.release = null
    }

    /** The held offer, or null when none is held. */
    fun current(): ReleaseInfo? = release
}
