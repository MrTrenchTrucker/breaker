package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import dev.breaker.shared.models.ModelRegistry

/**
 * The port the engine depends on: load a model by id.
 *
 * A `fun interface` so tests can implement it directly without subclassing.
 * The engine only needs `load(modelId)`, and this is the smallest surface
 * that expresses that contract.
 */
fun interface ModelLoaderPort {
    fun load(modelId: String): ModelLoader.LoadResult
}

/**
 * Loads a verified model from the local store and creates a recognizer.
 *
 * The loader verifies the archive on EVERY load. The stored verified digest
 * is a record for diagnostics and change-detection, never a substitute for
 * the check, because the file on disk can be replaced between one load and
 * the next.
 *
 * The family check lives here - there is no separate catalogue type. Only
 * [ModelFamily.SHERPA_ONNX] models are accepted; other families are refused.
 */
class ModelLoader(
    private val store: LocalModelStore,
    private val factory: SherpaRecognizerFactory = UnavailableRecognizerFactory,
    /**
     * Resolves a model id to its registry entry.
     *
     * The registry is the production source of model entries. Taking it as a
     * parameter keeps the loader's decision path exercisable in a plain unit
     * test without reaching into the compiled registry, in the same way the
     * recognizer factory is injected. The default is the real registry, so
     * production behaviour is unchanged unless a caller deliberately supplies
     * something else.
     */
    private val lookup: (String) -> ModelEntry? = ModelRegistry::byId,
    /**
     * Receives the technical text of each refusal (model id, family name,
     * exception text, the integrity check's reason). The default drops it.
     */
    private val debug: ModelDebugSink = NoDebugSink,
) : ModelLoaderPort {

    /**
     * The outcome of a load attempt.
     */
    sealed class LoadResult {
        /** The model is loaded and [recognizer] is ready to decode. */
        data class Ready(val model: SherpaModel, val recognizer: SherpaRecognizer) : LoadResult()

        /**
         * The load was refused; [refusal] categorises why. [detail] is a fixed
         * sentence for the user; the technical text goes to the debug sink.
         *
         * @property leftOnDisk true when the model had to be deleted and the
         *   delete failed, so a rejected file is still on disk.
         */
        data class Refused(
            val refusal: Refusal,
            val detail: String,
            val family: ModelFamily? = null,
            val leftOnDisk: Boolean = false,
        ) : LoadResult()

        /** True when the model is ready. */
        val isReady: Boolean get() = this is Ready
    }

    /**
     * Why a load was refused.
     */
    enum class Refusal {
        /** The model id is not in the registry. */
        UNKNOWN_MODEL,

        /** The model is not a sherpa-onnx model. */
        WRONG_FAMILY,

        /** The model is not installed. */
        NOT_INSTALLED,

        /**
         * The stored checksums are missing or could not be parsed, so the
         * archive could not be checked. The model is kept and checked again
         * on the next load.
         */
        CHECKSUMS_UNREADABLE,

        /**
         * The archive failed integrity verification and was deleted; when the
         * delete failed, [LoadResult.Refused.leftOnDisk] is true.
         */
        VERIFICATION_REFUSED,

        /** The engine could not be initialised for this model. */
        ENGINE_UNUSABLE,
    }

    /**
     * Load the model with [modelId] from the local store.
     *
     * The load order is: registry lookup, family check, install check,
     * checksum parse, archive verification, engine creation. Each step has
     * its own refusal category, and each refusal carries a fixed sentence from
     * [ModelMessages]; the technical text goes to the debug sink.
     *
     * Two cases look alike and are kept apart:
     * - Unreadable checksums (none stored, or stored text that cannot be
     *   parsed): nothing was judged. The archive and its markers are kept, no
     *   recognizer is created, and the result is
     *   [Refusal.CHECKSUMS_UNREADABLE]. The next call checks again.
     * - A failed check (the archive does not match its pin or the upstream
     *   list): the model is deleted at once and the result is
     *   [Refusal.VERIFICATION_REFUSED]. When the delete fails the refusal stays,
     *   its sentence says the file could not be deleted, and
     *   [LoadResult.Refused.leftOnDisk] is true.
     *
     * @return [LoadResult.Ready] on success, [LoadResult.Refused] otherwise.
     */
    override fun load(modelId: String): LoadResult {
        // Step 1: registry lookup
        val entry = lookup(modelId)
            ?: return refuse(
                Refusal.UNKNOWN_MODEL,
                ModelMessages.MODEL_UNKNOWN,
                "no model registered as '$modelId'",
            )

        // Step 2: family check
        if (entry.family != ModelFamily.SHERPA_ONNX) {
            return refuse(
                Refusal.WRONG_FAMILY,
                ModelMessages.MODEL_WRONG_FAMILY,
                "model '$modelId' is ${entry.family.name}, not SHERPA_ONNX",
                entry.family,
            )
        }

        // Step 3: install check
        if (!store.isInstalled(modelId)) {
            return refuse(
                Refusal.NOT_INSTALLED,
                ModelMessages.MODEL_NOT_INSTALLED,
                "model '$modelId' is not installed",
            )
        }

        // Step 4: checksum parse. Unreadable checksums mean the archive was not
        // judged: keep it, create nothing, refuse, and check again next load.
        val checksumsText = store.storedChecksums(modelId)
            ?: return refuse(
                Refusal.CHECKSUMS_UNREADABLE,
                ModelMessages.NOT_VERIFIED_YET,
                "no stored checksums for '$modelId'",
            )

        val checksums: UpstreamChecksums.Checksums = try {
            UpstreamChecksums.parse(checksumsText)
        } catch (e: Exception) {
            return refuse(
                Refusal.CHECKSUMS_UNREADABLE,
                ModelMessages.NOT_VERIFIED_YET,
                "stored checksums unparseable: ${e.message}",
            )
        }

        // Step 5: archive verification
        val verdict = verifyArchive(modelId, entry, checksums)
        if (verdict is ModelIntegrity.Verdict.Refused) {
            val removed = store.delete(modelId)
            return refuse(
                Refusal.VERIFICATION_REFUSED,
                if (removed) ModelMessages.CHECK_FAILED_DELETED else ModelMessages.CHECK_FAILED_NOT_DELETED,
                "${verdict.refusal.name}: ${verdict.detail}",
                leftOnDisk = !removed,
            )
        }
        val digest = (verdict as ModelIntegrity.Verdict.Verified).digest

        // Step 6: engine creation. Any Exception from the factory (checked or
        // not, including SherpaTranscriptionException) is a refusal. An Error
        // (out of memory, a native library that fails to link) is not caught:
        // it is not an engine refusal and must reach the caller.
        val model = SherpaModel(modelId, store.directoryFor(modelId), digest)
        val recognizer: SherpaRecognizer = try {
            factory.create(model)
        } catch (e: Exception) {
            return refuse(
                Refusal.ENGINE_UNUSABLE,
                ModelMessages.ENGINE_COULD_NOT_START,
                "engine failed: ${e.message}",
            )
        }

        return LoadResult.Ready(model, recognizer)
    }

    /**
     * Builds a refusal whose [LoadResult.Refused.detail] is the fixed
     * [userDetail] and sends the [technical] text to the debug sink, once. When
     * [leftOnDisk] is true the sink text also says the delete failed.
     */
    private fun refuse(
        refusal: Refusal,
        userDetail: String,
        technical: String,
        family: ModelFamily? = null,
        leftOnDisk: Boolean = false,
    ): LoadResult.Refused {
        val note = if (leftOnDisk) "; delete failed" else ""
        debug.debug("${refusal.name}: $technical$note")
        return LoadResult.Refused(refusal, userDetail, family, leftOnDisk)
    }

    /**
     * Verify the installed archive for [modelId] against [entry]'s pin and [checksums].
     *
     * This is deliberately the single place the trust decision is made, so a
     * later change to that decision is one deliberate edit rather than a drift.
     *
     * @return [ModelIntegrity.Verdict.Verified] with the digest on success,
     *   [ModelIntegrity.Verdict.Refused] otherwise.
     */
    private fun verifyArchive(
        modelId: String,
        entry: ModelEntry,
        checksums: UpstreamChecksums.Checksums,
    ): ModelIntegrity.Verdict {
        val archive = store.archiveFile(modelId)
        return ModelIntegrity.verify(archive, entry.sha256, checksums)
    }
}
