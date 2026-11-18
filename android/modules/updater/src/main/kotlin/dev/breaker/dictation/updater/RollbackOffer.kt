package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.shared.api.UpdateManifest

/** Recognises an offer that rolls the app back to an older release with a higher version code. */
internal object RollbackOffer {
    /**
     * A rollback is an offer whose code is strictly greater than the installed code and whose
     * name sorts before the installed name. Returns the release for such an offer, or null for any
     * other offer, including one whose names are not comparable. Never throws.
     */
    fun recognise(installedCode: Long, installedName: String, offered: UpdateManifest): ReleaseInfo? {
        if (offered.versionCode <= installedCode) return null
        if (VersionName.compare(offered.version, installedName) != -1) return null
        return ReleaseMapper.toRelease(offered)
    }
}
