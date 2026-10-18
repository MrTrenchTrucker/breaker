package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import dev.breaker.shared.models.ModelRegistry
import java.io.File
import java.io.IOException

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
 * A load trusts the unpacked files through the install stamp (see
 * [UnpackedStamp]) when one exists for the pinned model: sizes and modified
 * times decide, and the four files are hashed only when a modified time
 * differs. A changed file is deleted and refused before the engine opens.
 * Without a stamp the archive is verified once, and the stamp is written from
 * the files as they are.
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
    /** The archive check. The default is the real one; a test may replace it to count calls. */
    private val verify: (File, String, UpstreamChecksums.Checksums, String?) -> ModelIntegrity.Verdict =
        { file, pin, checksums, archiveName -> ModelIntegrity.verify(file, pin, checksums, archiveName) },
    /** Digests one unpacked file. The default is the real hash; a test may replace it. */
    private val digestOf: (File) -> String? = ModelIntegrity::sha256,
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
     * checksum parse, archive verification, unpacked-files check, engine creation. Each step has
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
     * A verified archive whose unpacked files are missing is refused as
     * [Refusal.NOT_INSTALLED] and kept: nothing about it was judged wrong, and
     * installing again unpacks it.
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

        // Step 5: the unpacked files and the archive digest. An install stamp for
        // this pin decides by sizes and modified times, so the archive is not
        // hashed. Without one, the archive is verified here, once, and the stamp
        // is written in step 5b after the unpacked files were found.
        val profile = ExtractionProfiles.forModel(modelId)
        val stamp = store.readStamp(modelId)?.takeIf { profile != null && it.isFor(entry.sha256, profile.files) }
        val digest: String
        if (stamp != null) {
            if (!store.isExtracted(modelId)) {
                return refuse(
                    Refusal.NOT_INSTALLED,
                    ModelMessages.MODEL_NOT_INSTALLED,
                    "model '$modelId' has no unpacked files",
                )
            }
            stampRefusal(modelId, stamp)?.let { return it }
            digest = stamp.archiveDigest
        } else {
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
            digest = (verdict as ModelIntegrity.Verdict.Verified).digest

            // Step 5b: the engine opens the unpacked files, not the archive. A
            // verified archive without them (an install that stopped before the
            // unpack) is kept, because nothing about it was wrong; installing again
            // unpacks it. This comes after the archive check, so a tampered archive
            // is still judged and deleted first. The stamp is written only after
            // both passed.
            if (!store.isExtracted(modelId)) {
                return refuse(
                    Refusal.NOT_INSTALLED,
                    ModelMessages.MODEL_NOT_INSTALLED,
                    "model '$modelId' is verified but has no unpacked files",
                )
            }
            writeFirstStamp(modelId, profile, digest)
        }

        // Step 6: engine creation. Any Exception from the factory (checked or
        // not, including SherpaTranscriptionException) is a refusal. The real
        // factory turns a native library that fails to link into a
        // SherpaTranscriptionException, so that case arrives here as a
        // refusal. An OutOfMemoryError from the engine creation is refused the
        // same way. Any other Error is not caught: it is not an engine refusal
        // and must reach the caller.
        val model = SherpaModel(modelId, store.extractedDirectory(modelId), digest)
        val recognizer: SherpaRecognizer = try {
            factory.create(model)
        } catch (e: Exception) {
            return refuse(
                Refusal.ENGINE_UNUSABLE,
                ModelMessages.ENGINE_COULD_NOT_START,
                "engine failed: ${e.message}",
            )
        } catch (e: OutOfMemoryError) {
            return refuse(
                Refusal.ENGINE_UNUSABLE,
                ModelMessages.ENGINE_COULD_NOT_START,
                "engine failed: out of memory",
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
        return verify(archive, entry.sha256, checksums, entry.archiveName)
    }

    /**
     * The stamp path of step 5: null when the unpacked files pass, else the
     * refusal. Sizes and modified times decide without hashing. When only the
     * modified times differ, the four files are hashed against the recorded
     * digests (never the archive), and a stamp that still matches is refreshed.
     */
    private fun stampRefusal(modelId: String, stamp: UnpackedStamp): LoadResult.Refused? {
        val directory = store.extractedDirectory(modelId)
        return when (val check = stamp.check(directory)) {
            is UnpackedStamp.Check.Mismatch -> refuseUnpacked(
                modelId,
                check.name,
                "an unpacked file changed since install (${check.name})",
            )
            UnpackedStamp.Check.Match -> null
            UnpackedStamp.Check.NeedsHash -> {
                val bad = stamp.hashesMatch(directory, digestOf)
                if (bad != null) {
                    return refuseUnpacked(modelId, bad, "an unpacked file does not match its recorded digest ($bad)")
                }
                refreshStamp(modelId, stamp, directory)
                null
            }
        }
    }

    /** Deletes the one unpacked file that failed and builds the refusal; the file stays only when the delete failed. */
    private fun refuseUnpacked(modelId: String, name: String, technical: String): LoadResult.Refused {
        val removed = store.deleteUnpackedFile(modelId, name)
        return refuse(
            Refusal.NOT_INSTALLED,
            ModelMessages.MODEL_NOT_INSTALLED,
            technical,
            leftOnDisk = !removed,
        )
    }

    /** Rewrites the stamp with the current modified times; a failed write costs only the refresh. */
    private fun refreshStamp(modelId: String, stamp: UnpackedStamp, directory: File) {
        stamp.refreshed(directory)?.let {
            try {
                store.writeStamp(modelId, it)
            } catch (e: IOException) {
                debug.debug("could not refresh the unpacked-file stamp")
            }
        }
    }

    /**
     * The missing-stamp path, after the archive verified and the unpacked files
     * were found: writes a stamp from the files as they are. A failed measure or
     * write costs only the stamp; it never fails the load.
     */
    private fun writeFirstStamp(modelId: String, profile: ExtractionProfile?, digest: String) {
        val directory = store.extractedDirectory(modelId)
        val fresh = profile?.let { UnpackedStamp.measure(directory, it.files, digest, digestOf) } ?: return
        try {
            store.writeStamp(modelId, fresh)
        } catch (e: IOException) {
            debug.debug("could not write the unpacked-file stamp")
        }
    }
}
