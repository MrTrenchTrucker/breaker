package dev.breaker.dictation.stt.ondevice

import java.io.File
import java.io.IOException

/**
 * The staged swap of one user keyword install.
 *
 * In order: sweep the leftovers of an earlier install; stage the archive bytes and extract them into a new folder;
 * stamp that folder; move the installed folder aside; move the new folder in; delete the old one. Every step before the
 * first move leaves the installed folder alone. A failed move is undone when it can be, and every failure before the
 * move in deletes what this install made.
 *
 * [hook] is called before each step; an IOException it throws is that step's failure. [phrases] is the list the extracted
 * token table must cover; the store passes the same list its equal-digest no-op checks against.
 */
internal class UserKeywordSwap(
    private val root: File,
    private val extractor: ModelExtractor,
    private val digestOf: (File) -> String?,
    private val files: UserStoreFiles,
    private val hook: (UserStoreStage) -> Unit,
    private val phrases: List<KeywordLine>,
) {
    private val current = File(root, UserKeywordStore.CURRENT)
    private val next = File(root, UserKeywordStore.NEXT)
    private val aside = File(root, UserKeywordStore.ASIDE)
    private val work = File(root, UserKeywordStore.WORK)
    private val incoming = File(root, UserKeywordStore.INCOMING)
    private val profile = checkNotNull(ExtractionProfiles.forModel(KEYWORD_MODEL_ID))

    /** Runs the swap for [bytes], whose digest [digest] the caller has already checked; returns the result of the install. */
    fun run(bytes: ByteArray, digest: String): UserInstall {
        if (!sweep()) return UserInstall.Failed(UserKeywordReason.STAGE_FAILED)
        extract(bytes)?.let { return it }
        stamp(digest)?.let { return it }
        return swap(digest)
    }

    /** Removes the leftovers of an earlier install; false when one of them could not be removed. */
    private fun sweep(): Boolean = listOf(work, next, aside, incoming).map { files.deleteTree(it) }.all { it }

    /** Stages the archive and extracts it into [next]; null when the new folder is ready. */
    private fun extract(bytes: ByteArray): UserInstall? {
        try {
            hook(UserStoreStage.STAGE_WRITE)
            files.writeBytes(incoming, bytes)
            hook(UserStoreStage.EXTRACT)
        } catch (e: IOException) {
            return abandonFailed(UserKeywordReason.STAGE_FAILED)
        }
        return when (val outcome = extractor.extract(incoming, profile, work, next)) {
            is ExtractionOutcome.Extracted -> {
                if (!UserKeywordTokens.fits(File(next, profile.tokens), phrases)) {
                    discardNew()
                    UserInstall.Refused(UserKeywordReason.TOKENS_MISSING)
                } else {
                    files.deleteTree(incoming)
                    null
                }
            }
            is ExtractionOutcome.Rejected -> if (outcome.reason.fault == ExtractionFault.ARCHIVE) {
                discardNew()
                UserInstall.Refused(UserKeywordReason.EXTRACT_REFUSED)
            } else {
                abandonFailed(UserKeywordReason.STAGE_FAILED)
            }
        }
    }

    /** Writes the stamp into [next]; null when it is written. */
    private fun stamp(digest: String): UserInstall? {
        try {
            hook(UserStoreStage.STAMP_WRITE)
            val stamp = UnpackedStamp.measure(next, profile.files, digest, digestOf)
                ?: return abandonFailed(UserKeywordReason.STAMP_FAILED)
            files.writeText(File(next, UnpackedStamp.FILE_NAME), stamp.encode())
        } catch (e: IOException) {
            return abandonFailed(UserKeywordReason.STAMP_FAILED)
        }
        return null
    }

    /** Moves the installed folder aside, the new folder in, and deletes the old one. */
    private fun swap(digest: String): UserInstall {
        val hadCurrent = current.exists()
        if (hadCurrent && !moveAside()) return abandonFailed(UserKeywordReason.SWAP_FAILED)
        if (!moveIn()) {
            if (hadCurrent) files.move(aside, current)
            return abandonFailed(UserKeywordReason.SWAP_FAILED)
        }
        if (hadCurrent) deleteOld()
        return UserInstall.Installed(digest, false)
    }

    private fun moveAside(): Boolean = try {
        hook(UserStoreStage.ASIDE)
        files.move(current, aside)
    } catch (e: IOException) {
        false
    }

    private fun moveIn(): Boolean = try {
        hook(UserStoreStage.MOVE_IN)
        files.move(next, current)
    } catch (e: IOException) {
        false
    }

    /** The old model is already out of the way; when this fails, the leftover waits aside until a later swap or remove sweeps it, and the result stays as it is. */
    private fun deleteOld() {
        try {
            hook(UserStoreStage.DELETE_OLD)
            files.deleteTree(aside)
        } catch (e: IOException) {
            // the leftover stays aside until a later swap or remove sweeps it
        }
    }

    private fun abandonFailed(reason: UserKeywordReason): UserInstall {
        discardNew()
        return UserInstall.Failed(reason)
    }

    /** Removes what this install made. The installed folder is never touched here. */
    private fun discardNew() {
        files.deleteTree(next)
        files.deleteTree(work)
        files.deleteTree(incoming)
    }
}
