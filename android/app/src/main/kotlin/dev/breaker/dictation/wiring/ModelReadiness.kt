package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.LocalModelStore

/**
 * Tells whether a speech model is ready to use: installed and unpacked.
 *
 * [isReady] answers for the id the settings select, read each time it is asked, so a changed setting is
 * seen at once. [isReadyFor] answers for the id it is given. Neither throws: a store or a setting that
 * cannot be read answers false.
 */
class StoreModelReady(
    private val store: LocalModelStore,
    private val selectedId: () -> String,
) : ModelReady {
    override fun isReady(): Boolean =
        try {
            isReadyFor(selectedId())
        } catch (e: Exception) {
            false
        }

    /** Tells whether the model [id] is installed and unpacked. */
    fun isReadyFor(id: String): Boolean =
        try {
            store.isInstalled(id) && store.isExtracted(id)
        } catch (e: Exception) {
            false
        }
}
