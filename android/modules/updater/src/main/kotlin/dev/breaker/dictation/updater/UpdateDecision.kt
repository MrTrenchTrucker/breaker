package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.shared.api.UpdateManifest

/** The outcome of comparing an installed version code with an offered manifest. */
internal sealed class UpdateDecision {
    /** Newer: [manifest] offered a strictly greater code and mapped to [release]. */
    data class Newer(val release: ReleaseInfo) : UpdateDecision()

    /** Not newer: equal code or lower code, even with blank fields. */
    object NotNewer : UpdateDecision()

    /** Unusable: strictly greater code but [manifest] cannot be held (a blank field). */
    object Unusable : UpdateDecision()

    companion object {
        /**
         * Compare the codes first: equal or lower is NotNewer. Only then map;
         * a manifest that offers newer but cannot be held is Unusable, not NotNewer.
         */
        fun of(installedCode: Long, manifest: UpdateManifest): UpdateDecision {
            if (!UpdateOrder.isNewer(installedCode, manifest.versionCode)) return NotNewer
            val release = ReleaseMapper.toRelease(manifest) ?: return Unusable
            return Newer(release)
        }
    }
}
