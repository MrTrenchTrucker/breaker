package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo

/**
 * The fields of an accepted manifest: the release (its version is the display name), the integer release
 * code that decides whether it is newer, and the key id when the manifest named one.
 */
internal data class ParsedManifest(val release: ReleaseInfo, val versionCode: Int, val keyId: String?)

/** How the manifest reader judged one document. This is a reading result, not the outcome of an install. */
internal sealed class ManifestOutcome {
    /** The document is a valid manifest. */
    internal data class Accepted(val manifest: ParsedManifest) : ManifestOutcome()

    /** The document is refused; [reason] is a fixed plain sentence that never repeats the document. */
    internal data class Refused(val reason: String) : ManifestOutcome()
}
