package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult

/**
 * Looks for a newer release.
 *
 * Implemented by the updater module, which owns the daily schedule, the
 * admin-only force check, and the verification of what it downloads. The phone
 * checks the Local Server and nothing else.
 */
interface UpdateChecker {
    /**
     * Check now. [force] is the user's explicit request, which only an admin
     * may make; the automatic daily check passes false. Returns null when the
     * check is not due yet and was not forced.
     */
    fun check(force: Boolean): UpdateCheckResult?

    /** Install a verified [release], keeping the current version for rollback. */
    fun install(release: ReleaseInfo)

    /** Go back to the version kept from before the last update. */
    fun rollback()
}
