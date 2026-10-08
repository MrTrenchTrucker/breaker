package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import java.io.IOException

/**
 * Installs a model from the registry into the local store.
 *
 * The flow exists to hold one property: a partially written or unverified
 * archive is never visible to the loader. Bytes are verified in staging and
 * only then moved, and they are verified once more where they will be read.
 * Every failure path deletes what it wrote.
 *
 * A download that fails verification is deleted at once rather than kept -
 * a file that failed verification will not pass it on the next attempt, and
 * the next attempt downloads it fresh.
 *
 * After the second check the archive is unpacked into the few files the engine
 * opens (see [ModelExtractor]). The install result is Installed only once that
 * has finished. The store's isInstalled answers for the archive alone, and the
 * loader answers not installed when the unpacked files are missing. An archive
 * that cannot be unpacked is deleted with the rest of the model, and the refusal
 * says whether the archive was at fault, the phone was, or the space ran out. A
 * model with no unpack profile is refused before anything is downloaded.
 *
 * When a delete on a refusal path fails, the refusal is kept, its sentence says
 * that the file could not be deleted, and [InstallResult.Refused.leftOnDisk] is
 * true.
 *
 * A refusal carries one fixed sentence from [ModelMessages] for the user. The
 * technical text (paths, exception messages, fetch reasons, the integrity
 * verdict) goes to [debug] instead and never reaches the user.
 */
class ModelInstaller(
    private val store: LocalModelStore,
    private val fetcher: ModelFetcher,
    private val debug: ModelDebugSink = NoDebugSink,
    private val extractor: ModelExtractor = ModelExtractor(),
) {

    /**
     * The outcome of an install attempt.
     */
    sealed class InstallResult {
        /** The model was installed and verified; [digest] is the verified SHA-256. */
        data class Installed(val modelId: String, val digest: String) : InstallResult()

        /**
         * The install was refused; [refusal] categorises why. [detail] is a
         * fixed sentence for the user; the technical text goes to the debug sink.
         *
         * @property leftOnDisk true when the download or model had to be deleted
         *   and the delete failed, so a rejected file is still on disk.
         */
        data class Refused(
            val refusal: Refusal,
            val detail: String,
            val leftOnDisk: Boolean = false,
        ) : InstallResult()

        /** True when the model was installed. */
        val isInstalled: Boolean get() = this is Installed
    }

    /**
     * Why an install was refused.
     */
    enum class Refusal {
        /** The model archive could not be fetched. */
        FETCH_FAILED,

        /** The checksum file could not be fetched. */
        CHECKSUMS_FAILED,

        /** The checksum file could not be read or parsed. */
        CHECKSUMS_UNREADABLE,

        /** The staged archive failed integrity verification. */
        DIGEST_REFUSED,

        /** The model directory could not be created. */
        STAGING_FAILED,

        /** The staged archive could not be moved to its final location. */
        MOVE_FAILED,

        /** The model has no unpack profile, so it can never be unpacked; refused before any download. */
        UNSUPPORTED_MODEL,

        /** The archive is unsafe or damaged and was not unpacked; the model was deleted. */
        EXTRACT_REFUSED,

        /** The phone could not unpack the archive (a write or the final move failed); the model was deleted. */
        EXTRACT_FAILED,

        /** There was not enough free space to unpack the archive; the model was deleted. */
        NO_SPACE,
    }

    /**
     * Install [entry] into the local store.
     *
     * Each step has its own refusal category so the caller can distinguish
     * a network failure from a verification failure from a filesystem failure.
     *
     * @return [InstallResult.Installed] on success, [InstallResult.Refused] otherwise.
     */
    fun install(entry: ModelEntry): InstallResult {
        // Step 0: ensure the staging directory exists before any fetch.
        // A path that exists but is not a directory cannot receive the staged
        // files, and must be refused here rather than letting a write into it
        // throw later.
        val stagingDir = store.stagingDirectory()
        if (stagingDir.exists() && !stagingDir.isDirectory) {
            return refuse(Refusal.STAGING_FAILED, ModelMessages.SAVE_FAILED, "not a directory: ${stagingDir.absolutePath}")
        }
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            return refuse(Refusal.STAGING_FAILED, ModelMessages.SAVE_FAILED, "cannot create ${stagingDir.absolutePath}")
        }

        // Step 0b: a model with no unpack profile can never be unpacked, so it is
        // refused before anything is downloaded.
        if (!extractor.supports(entry.id)) {
            return refuse(Refusal.UNSUPPORTED_MODEL, ModelMessages.MODEL_WRONG_FAMILY, "no unpack profile for '${entry.id}'")
        }

        // Step 1: fetch checksums. The fetcher writes into staging, so an
        // IOException here is a staging failure, not a network one (a network
        // failure comes back as Result.Failed).
        val checksumsResult = try {
            fetcher.fetchChecksums(stagingDir)
        } catch (e: IOException) {
            return refuse(
                Refusal.STAGING_FAILED,
                ModelMessages.SAVE_FAILED,
                "cannot write into ${stagingDir.absolutePath}: ${e.message}",
            )
        }
        if (checksumsResult is ModelFetcher.Result.Failed) {
            return refuse(Refusal.CHECKSUMS_FAILED, ModelMessages.CHECKSUMS_DOWNLOAD_FAILED, checksumsResult.reason)
        }
        val checksumsFile = (checksumsResult as ModelFetcher.Result.Fetched).file

        val checksumsText: String = try {
            checksumsFile.readText()
        } catch (e: Exception) {
            return refuse(
                Refusal.CHECKSUMS_UNREADABLE,
                ModelMessages.CHECKSUMS_UNREADABLE_DOWNLOADED,
                "checksum file unreadable: ${e.message}",
            )
        }

        val checksums: UpstreamChecksums.Checksums = try {
            UpstreamChecksums.parse(checksumsText)
        } catch (e: Exception) {
            return refuse(
                Refusal.CHECKSUMS_UNREADABLE,
                ModelMessages.CHECKSUMS_UNREADABLE_DOWNLOADED,
                "checksum file unparseable: ${e.message}",
            )
        }

        // Step 2: fetch model archive. Same staging-write reasoning as step 1.
        val modelResult = try {
            fetcher.fetchModel(entry, stagingDir)
        } catch (e: IOException) {
            return refuse(
                Refusal.STAGING_FAILED,
                ModelMessages.SAVE_FAILED,
                "cannot write into ${stagingDir.absolutePath}: ${e.message}",
            )
        }
        if (modelResult is ModelFetcher.Result.Failed) {
            return refuse(Refusal.FETCH_FAILED, ModelMessages.MODEL_DOWNLOAD_FAILED, modelResult.reason)
        }
        val staged = (modelResult as ModelFetcher.Result.Fetched).file

        // Step 3: verify in staging
        val stagingVerdict = ModelIntegrity.verify(staged, entry.sha256, checksums)
        if (stagingVerdict is ModelIntegrity.Verdict.Refused) {
            val removed = store.discardFailedDownload(staged)
            return refuse(
                Refusal.DIGEST_REFUSED,
                ModelMessages.DOWNLOAD_FAILED_CHECK,
                "${stagingVerdict.refusal.name}: ${stagingVerdict.detail}",
                leftOnDisk = !removed,
                sentenceIfLeft = ModelMessages.DOWNLOAD_FAILED_CHECK_NOT_DELETED,
            )
        }
        val digest = (stagingVerdict as ModelIntegrity.Verdict.Verified).digest

        // Step 4: create model directory
        val modelDir = store.directoryFor(entry.id)
        if (!modelDir.mkdirs() && !modelDir.isDirectory) {
            val removed = store.discardFailedDownload(staged)
            return refuse(
                Refusal.STAGING_FAILED,
                ModelMessages.SAVE_FAILED,
                "cannot create ${modelDir.absolutePath}",
                leftOnDisk = !removed,
            )
        }

        // Step 5: move staged archive to final location
        val archiveFile = store.archiveFile(entry.id)
        // A directory at the archive path is a corrupt state. delete() would
        // silently remove an EMPTY directory and let the install "succeed"
        // into a broken store, so refuse a non-file destination instead.
        if (archiveFile.exists() && !archiveFile.isFile) {
            val removed = store.discardFailedDownload(staged)
            return refuse(
                Refusal.MOVE_FAILED,
                ModelMessages.SAVE_FAILED,
                "destination is not a file: ${archiveFile.absolutePath}",
                leftOnDisk = !removed,
            )
        }
        // Files unpacked from an earlier install must not outlive the archive they
        // came from, so they are removed before the archive is replaced.
        if (!store.removeExtracted(entry.id)) {
            val removed = store.discardFailedDownload(staged)
            return refuse(
                Refusal.MOVE_FAILED,
                ModelMessages.SAVE_FAILED,
                "cannot remove the earlier unpacked files of '${entry.id}'",
                leftOnDisk = !removed,
            )
        }
        // Delete any existing file at the destination - renameTo fails on
        // some filesystems when the target already exists, and re-installing
        // over a previously installed model must not fail with MOVE_FAILED.
        if (archiveFile.exists()) archiveFile.delete()
        if (!staged.renameTo(archiveFile)) {
            val removed = store.discardFailedDownload(staged)
            return refuse(
                Refusal.MOVE_FAILED,
                ModelMessages.SAVE_FAILED,
                "cannot move ${staged.absolutePath} to ${archiveFile.absolutePath}",
                leftOnDisk = !removed,
            )
        }

        // Step 6: verify at final location
        val finalVerdict = ModelIntegrity.verify(archiveFile, entry.sha256, checksums)
        if (finalVerdict is ModelIntegrity.Verdict.Refused) {
            val removed = store.delete(entry.id)
            return refuse(
                Refusal.DIGEST_REFUSED,
                ModelMessages.DOWNLOAD_FAILED_CHECK,
                "${finalVerdict.refusal.name}: ${finalVerdict.detail}",
                leftOnDisk = !removed,
                sentenceIfLeft = ModelMessages.DOWNLOAD_FAILED_CHECK_NOT_DELETED,
            )
        }

        // Step 7: record checksums and mark verified. A write failure here is
        // the same staging-write shape as steps 1-2 - return it, never let it
        // escape. The archive is deleted so a model that cannot record its
        // provenance is not left looking installed.
        try {
            store.storeChecksums(entry.id, checksumsText)
            store.markVerified(entry.id, digest)
        } catch (e: IOException) {
            val removed = store.delete(entry.id)
            return refuse(
                Refusal.STAGING_FAILED,
                ModelMessages.SAVE_FAILED,
                "cannot record model metadata: ${e.message}",
                leftOnDisk = !removed,
            )
        }

        // Step 8: unpack. Only now is the model complete: the archive has been
        // verified twice, its metadata is recorded, and its files appear in one
        // move. A refusal deletes the whole model so nothing half-installed stays.
        return when (val unpacked = extractor.extract(archiveFile, entry.id, store)) {
            is ExtractionOutcome.Extracted -> InstallResult.Installed(entry.id, digest)
            is ExtractionOutcome.Rejected -> refuseUnpack(entry.id, unpacked)
        }
    }

    /**
     * Delete the whole model after [rejected] and refuse with the category and
     * sentence that match who was at fault: the archive, the phone, or the space.
     */
    private fun refuseUnpack(modelId: String, rejected: ExtractionOutcome.Rejected): InstallResult.Refused {
        val removed = store.delete(modelId)
        val (refusal, sentence) = when (rejected.reason.fault) {
            ExtractionFault.ARCHIVE -> Refusal.EXTRACT_REFUSED to ModelMessages.UNPACK_REFUSED
            ExtractionFault.LOCAL -> Refusal.EXTRACT_FAILED to ModelMessages.UNPACK_FAILED
            ExtractionFault.SPACE -> Refusal.NO_SPACE to ModelMessages.UNPACK_NO_SPACE
        }
        return refuse(refusal, sentence, "${rejected.reason.name}: ${rejected.detail}", leftOnDisk = !removed)
    }

    /**
     * Build a refusal: [userSentence] is what the result carries, [technical]
     * goes to the debug sink once, before the result is returned. When
     * [leftOnDisk] is true the result carries [sentenceIfLeft] (by default the
     * sentence plus the note that the file could not be deleted) and the sink
     * text says the delete failed.
     */
    private fun refuse(
        refusal: Refusal,
        userSentence: String,
        technical: String,
        leftOnDisk: Boolean = false,
        sentenceIfLeft: String = userSentence + ModelMessages.COULD_NOT_DELETE,
    ): InstallResult.Refused {
        val note = if (leftOnDisk) "; delete failed" else ""
        debug.debug("install refused (${refusal.name}): $technical$note")
        val sentence = if (leftOnDisk) sentenceIfLeft else userSentence
        return InstallResult.Refused(refusal, sentence, leftOnDisk)
    }
}
