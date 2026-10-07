package dev.breaker.dictation.commit.adapter

import dev.breaker.dictation.commit.FocusedFieldRegistry

/**
 * The one process-wide holder in this module.
 *
 * The framework creates the keyboard service itself, so the service cannot be
 * handed the registry through a constructor, and the commit service built by
 * the app has to read the very same registry. Both reach it here. Nothing else
 * in the module is global, and no test touches this object: every test builds
 * its own registry.
 */
internal object ImeHolder {
    val registry: FocusedFieldRegistry = FocusedFieldRegistry()
}
