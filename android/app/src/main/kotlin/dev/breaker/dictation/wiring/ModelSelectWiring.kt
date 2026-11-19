package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.settings.ModelCatalog
import dev.breaker.dictation.stt.ondevice.LocalModelStore

/** Removes the files of one speech model. Answers false when it refuses or removes nothing. */
fun interface ModelDeletePort {
    fun delete(id: String): Boolean
}

/**
 * Deletes through [store]. The model in use ([heldId]) and the model being downloaded ([downloadingId])
 * are refused: nothing is touched and the answer is false. Nothing here throws.
 */
class StoreModelDelete(
    private val store: LocalModelStore,
    private val heldId: () -> String,
    private val downloadingId: () -> String?,
) : ModelDeletePort {
    override fun delete(id: String): Boolean =
        try {
            id != heldId() && id != downloadingId() && store.delete(id)
        } catch (e: Exception) {
            false
        }
}

/**
 * Runs a delete on [background] and answers [onFinished] on [main] with whether files were removed. The
 * caller never runs the delete itself: nothing is removed until the background drains. A delete shows no
 * notice; a delete of a model that is not installed is a quiet no-op that answers false.
 */
class ModelDeletion(
    private val port: ModelDeletePort,
    private val background: Background,
    private val main: MainPost,
) {
    fun request(id: String, onFinished: (Boolean) -> Unit = {}) {
        try {
            background.submit {
                val removed = port.delete(id)
                try {
                    main.post { onFinished(removed) }
                } catch (e: Exception) {
                    // The answer could not be posted; the delete itself is already over.
                }
            }
        } catch (e: Exception) {
            // The background refused the job; nothing was removed and nothing is shown.
        }
    }
}

/**
 * Makes an installed model the model in use by saving its id as the settings' model size. Only an id the
 * registry names, and that [ready] accepts, is saved; any other id answers false. The settings store
 * refuses an id the registry does not name when it saves.
 */
class ModelSelectWriter(
    private val settings: SettingsStore,
    private val ready: (String) -> Boolean,
) {
    /** Saves [id] as the model in use and answers true, or answers false when nothing was saved. */
    fun select(id: String): Boolean =
        try {
            if (ModelCatalog.byId(id) == null || !ready(id)) {
                false
            } else {
                settings.save(settings.load().copy(modelSize = id))
                true
            }
        } catch (e: Exception) {
            false
        }
}

/** The writer over [settings], which saves only an id that [store] holds installed and unpacked. */
fun modelSelectWriterFor(settings: SettingsStore, store: LocalModelStore): ModelSelectWriter =
    ModelSelectWriter(settings) { id -> store.isInstalled(id) && store.isExtracted(id) }

/** The download callback that makes the downloaded model the model in use; any other outcome changes nothing. */
fun selectOnDone(writer: ModelSelectWriter): (DownloadOutcome) -> Unit = { outcome ->
    if (outcome is DownloadOutcome.Done) writer.select(outcome.modelId)
}
