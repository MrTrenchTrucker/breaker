package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.shared.api.UpdateManifest

/** Maps an [UpdateManifest] to a core [ReleaseInfo], or null when it cannot hold one. */
internal object ReleaseMapper {
    /**
     * Map the five fields from [manifest] to a [ReleaseInfo]. The signature passes through
     * unchecked: non-blank is all the type needs. Returns null when version, apkUrl, sha256
     * or signature is blank, because [ReleaseInfo.init] refuses blanks (Update.kt:18-21).
     * [notes] may be blank and is passed as is (default "").
     */
    fun toRelease(manifest: UpdateManifest): ReleaseInfo? {
        if (manifest.version.isBlank()) return null
        if (manifest.apkUrl.isBlank()) return null
        if (manifest.sha256.isBlank()) return null
        if (manifest.signature.isBlank()) return null
        return ReleaseInfo(
            version = manifest.version,
            apkUrl = manifest.apkUrl,
            sha256 = manifest.sha256,
            signature = manifest.signature,
            notes = manifest.notes,
        )
    }
}
