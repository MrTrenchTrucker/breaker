package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.model.UpdateInstallResult

/**
 * Looks for a newer release and installs a verified one.
 *
 * Implemented by the updater module, which owns the daily schedule, the
 * admin-only force check, and the verification of what it downloads. The phone
 * checks the Local Server and nothing else.
 *
 * Expected outcomes are values, not exceptions. A refused or failed install comes
 * back as an [UpdateInstallResult]; an implementation does not throw for either.
 */
interface UpdateChecker {
    /**
     * Check now. [force] is the user's explicit request, which only an admin
     * may make; the automatic daily check passes false. Returns null when the
     * check is not due yet and was not forced.
     */
    fun check(force: Boolean): UpdateCheckResult?

    /**
     * Install a verified [release]. Returns [UpdateInstallResult.Installed] when the
     * installer accepted the file, [UpdateInstallResult.Refused] when the release was
     * refused on purpose, and [UpdateInstallResult.Failed] when something broke.
     */
    fun install(release: ReleaseInfo): UpdateInstallResult
}
