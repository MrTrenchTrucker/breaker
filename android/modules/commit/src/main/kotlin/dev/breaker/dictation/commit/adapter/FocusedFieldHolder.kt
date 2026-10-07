package dev.breaker.dictation.commit.adapter

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
 * Nothing publishes into this holder yet. `commit/accessibility` (ADR-022) is
 * the mechanism meant to publish into it, once built.
 */
internal object FocusedFieldHolder {
    val registry: FocusedFieldRegistry = FocusedFieldRegistry()
}
