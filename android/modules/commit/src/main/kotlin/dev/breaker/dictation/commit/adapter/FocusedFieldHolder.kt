package dev.breaker.dictation.commit.adapter

import dev.breaker.dictation.commit.FocusedField
import dev.breaker.dictation.commit.FocusedFieldRegistry

/**
 * The one process-wide holder in this module.
 *
 * A text-insert mechanism is built and owned by its own sub-module, not by
 * this one, so it cannot be handed the registry through a constructor, and the
 * commit service built by the app has to read the very same registry. Both
 * reach it here. Nothing else in the module is global, and no test touches
 * this object: every test builds its own registry.
 *
 * This object is public, and [publish] is its one public member, because
 * Kotlin `internal` does not cross a Gradle module boundary: a text-insert
 * mechanism built as its own module (`commit/accessibility`, ADR-022) needs a
 * real seam to reach this holder. [registry] itself stays internal, and there
 * is no public `current()` or `clearAll()`: nothing outside this module can
 * read the published field, so text can only be inserted through the commit
 * service, on an explicit send, never read from outside it.
 */
object FocusedFieldHolder {
    internal val registry: FocusedFieldRegistry = FocusedFieldRegistry()

    /**
     * Publish [field] as the focused field until the returned handle is
     * closed.
     *
     * Closing the handle clears this publish only while it is still current:
     * a stale close (an older handle, after a newer field replaced this one)
     * or a double close does nothing either time. See
     * [FocusedFieldRegistry.publishScoped].
     */
    fun publish(field: FocusedField): AutoCloseable = registry.publishScoped(field)
}
