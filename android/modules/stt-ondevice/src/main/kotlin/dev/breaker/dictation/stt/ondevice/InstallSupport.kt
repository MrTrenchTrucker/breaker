package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import java.io.File

/**
 * Support for [ModelInstaller] that has no reason to sit in the installer class:
 * the stale-staging accounting of the free-space check and the refusal mapping
 * of a rejected unpack.
 */

/** Suffix of the partial model download the fetcher leaves directly in the staging directory. */
internal const val DOWNLOAD_SUFFIX: String = ".download"

/**
 * The reasons that say the archive itself exceeds one of the profile's bounds.
 *
 * They are permanent for a pinned archive: the digest pins the exact bytes, so
 * downloading them again produces the same archive and the same refusal. A
 * "download it again" sentence is therefore untrue for them.
 */
private val OVER_LIMIT_REASONS: Set<ExtractionReason> = setOf(
    ExtractionReason.TOO_MANY_ENTRIES,
    ExtractionReason.ENTRY_TOO_LARGE,
    ExtractionReason.WRITTEN_TOO_LARGE,
    ExtractionReason.STREAM_TOO_LARGE,
)

/** True when [reason] says the archive exceeds one of the profile's bounds (a permanent fault). */
internal fun isOverLimitArchive(reason: ExtractionReason): Boolean = reason in OVER_LIMIT_REASONS

/**
 * The refusal category and fixed sentence for a rejected unpack. An over-limit archive or a
 * local fault gives [ModelInstaller.Refusal.EXTRACT_FAILED] + [ModelMessages.UNPACK_FAILED];
 * no space gives NO_SPACE + [ModelMessages.UNPACK_NO_SPACE]; every other archive fault keeps
 * [ModelInstaller.Refusal.EXTRACT_REFUSED] + [ModelMessages.UNPACK_REFUSED].
 */
internal fun unpackRefusalFor(reason: ExtractionReason): Pair<ModelInstaller.Refusal, String> =
    when {
        isOverLimitArchive(reason) || reason.fault == ExtractionFault.LOCAL ->
            ModelInstaller.Refusal.EXTRACT_FAILED to ModelMessages.UNPACK_FAILED
        reason.fault == ExtractionFault.SPACE ->
            ModelInstaller.Refusal.NO_SPACE to ModelMessages.UNPACK_NO_SPACE
        else ->
            ModelInstaller.Refusal.EXTRACT_REFUSED to ModelMessages.UNPACK_REFUSED
    }

/** Bytes held by stale partial downloads (`<id>.download`) directly inside [stagingDir]. */
internal fun staleStagingBytes(stagingDir: File): Long =
    stagingDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(DOWNLOAD_SUFFIX) }
        ?.sumOf { it.length() }
        ?: 0L

/**
 * Deletes the stale partial downloads directly inside [stagingDir] and returns the BYTES actually
 * removed: each file's length is captured before its delete, and a file whose delete fails
 * contributes nothing. A failed delete is reported to [debug] (technical text only). The caller
 * re-checks the room against these bytes, so a stale file that cannot be removed never turns a
 * refusal into a pass.
 */
internal fun sweepStaleStaging(
    stagingDir: File,
    debug: ModelDebugSink,
    delete: (File) -> Boolean = { it.delete() },
): Long {
    val stale = stagingDir.listFiles()?.filter { it.isFile && it.name.endsWith(DOWNLOAD_SUFFIX) } ?: return 0L
    var removedBytes = 0L
    for (file in stale) {
        val length = file.length()
        try {
            if (delete(file) || !file.exists()) {
                removedBytes += length
            } else {
                debug.debug("stale staging cannot be removed: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            debug.debug("stale staging cannot be removed (${e.javaClass.simpleName}): ${file.absolutePath}")
        }
    }
    return removedBytes
}

/**
 * Build a refusal: [userSentence] is what the result carries, [technical] goes to the debug sink
 * once, before the result is returned. When [leftOnDisk] is true the result carries [sentenceIfLeft]
 * (by default the sentence plus the note that the file could not be deleted) and the sink text says
 * the delete failed.
 */
internal fun refuseInstall(
    debug: ModelDebugSink,
    refusal: ModelInstaller.Refusal,
    userSentence: String,
    technical: String,
    leftOnDisk: Boolean = false,
    sentenceIfLeft: String = userSentence + ModelMessages.COULD_NOT_DELETE,
): ModelInstaller.InstallResult.Refused {
    val note = if (leftOnDisk) "; delete failed" else ""
    debug.debug("install refused (${refusal.name}): $technical$note")
    val sentence = if (leftOnDisk) sentenceIfLeft else userSentence
    return ModelInstaller.InstallResult.Refused(refusal, sentence, leftOnDisk)
}

/**
 * The free-space pre-check, run before any byte is fetched. The disk holds the archive and the
 * files unpacked from it at the same moment, so the check needs the pinned archive size plus the
 * most the unpack profile lets it write. Stale partial downloads already in staging are
 * reclaimable: the first refusal counts them as room and deletes nothing; the sweep then removes
 * them and the recheck refuses NO_SPACE unless the bytes actually freed cover the shortfall, so a
 * stale file whose delete failed never turns a refusal into a pass.
 *
 * Returns the NO_SPACE refusal, or null when the install may fetch.
 */
internal fun freeSpaceRefusal(
    stagingDir: File,
    entry: ModelEntry,
    unpackBytes: Long,
    usableSpace: (File) -> Long,
    sweepDelete: (File) -> Boolean,
    debug: ModelDebugSink,
): ModelInstaller.InstallResult.Refused? {
    val needBytes = entry.sizeBytes + unpackBytes
    val freeBytes = usableSpace(stagingDir)
    val reclaimableBytes = staleStagingBytes(stagingDir)
    if (freeBytes + reclaimableBytes < needBytes) {
        val reclaimed = if (reclaimableBytes > 0L) ", reclaimable $reclaimableBytes" else ""
        return refuseInstall(
            debug,
            ModelInstaller.Refusal.NO_SPACE,
            ModelMessages.DOWNLOAD_NO_SPACE,
            "need $needBytes bytes (archive ${entry.sizeBytes} + unpacked $unpackBytes), " +
                "free $freeBytes in ${stagingDir.absolutePath}$reclaimed",
        )
    }
    val freedBytes = sweepStaleStaging(stagingDir, debug, sweepDelete)
    if (freeBytes + freedBytes < needBytes) {
        return refuseInstall(
            debug,
            ModelInstaller.Refusal.NO_SPACE,
            ModelMessages.DOWNLOAD_NO_SPACE,
            "need $needBytes bytes (archive ${entry.sizeBytes} + unpacked $unpackBytes), " +
                "free $freeBytes + swept $freedBytes in ${stagingDir.absolutePath}",
        )
    }
    return null
}

/**
 * The unpack profile of [entry], the one source for support, the free-space figure, the unpack and the
 * stamp. An entry that carries its own files and limits is read as given ([ExtractionProfiles.forEntry]);
 * an entry missing either is resolved by its id through [lookup] ([ExtractionProfiles.forModel]).
 * Null means no profile.
 */
internal fun profileOf(entry: ModelEntry, lookup: (String) -> ModelEntry?): ExtractionProfile? =
    if (entry.files != null && entry.limits != null) {
        ExtractionProfiles.forEntry(entry)
    } else {
        ExtractionProfiles.forModel(entry.id, lookup)
    }
