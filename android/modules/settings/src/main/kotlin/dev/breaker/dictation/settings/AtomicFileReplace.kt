package dev.breaker.dictation.settings

import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The one lock every save takes, shared by all store instances in the process.
 *
 * The real steps of a save live here as well: [readIfPresent] takes the
 * previous bytes of the live file before anything is written, [write] puts the
 * document into a file, [move] puts that file over the live one, [tempFileFor]
 * names the file in between, [deleteQuietly] clears it away after a failure,
 * and [putBack] restores the previous file after a failed keystore call, through
 * [restoreTempFileFor], a second temp name that never equals [tempFileFor].
 */
internal object AtomicFileReplace {
    internal val lock: Any = Any()

    /** Properties comment written at the top of every settings file. */
    private const val HEADER_COMMENT = "Breaker settings"

    /** Appended to the live file's name to make the temp file's name. */
    private const val TEMP_SUFFIX = ".tmp"

    /** Appended to the live file's name to make the restore temp file's name. It differs from [TEMP_SUFFIX]. */
    private const val RESTORE_SUFFIX = ".restore"

    /** The settings document, written in full to [target] as UTF-8. */
    internal fun write(target: File, properties: Properties) {
        OutputStreamWriter(
            target.outputStream(),
            StandardCharsets.UTF_8,
        ).use { writer ->
            properties.store(writer, HEADER_COMMENT)
        }
    }

    /** One atomic step: [to] ends up as the old file or the new one, never a mix. */
    internal fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * The temp file for [target]: the same folder, and the target's name with a
     * fixed suffix. The name is fixed, never random, so a stale copy left by a
     * crash is simply overwritten by the next save.
     */
    internal fun tempFileFor(target: File): File =
        File(target.absoluteFile.parentFile, target.name + TEMP_SUFFIX)

    /**
     * The temp file a restore writes the previous document into: the same folder,
     * and the target's name with [RESTORE_SUFFIX]. That suffix is not [TEMP_SUFFIX],
     * so this name can never be the one [tempFileFor] gives. A stale copy left by a
     * crash is simply overwritten by the next restore.
     */
    internal fun restoreTempFileFor(target: File): File =
        File(target.absoluteFile.parentFile, target.name + RESTORE_SUFFIX)

    /**
     * The bytes of [target] as they are now, or null when there is no such file.
     * A failure to read propagates; nothing has been changed at that point.
     */
    internal fun readIfPresent(target: File): ByteArray? =
        if (Files.exists(target.toPath())) Files.readAllBytes(target.toPath()) else null

    /**
     * Puts the previous file back over [target]. When [previous] is null there was no
     * file before the save, so [target] is deleted if it is there. Otherwise [previous]
     * is written to [restoreTempFileFor] and moved over [target] with [move], the same
     * move the save uses, so the swap is atomic in the same way. The restore temp file
     * is deleted if the move did not happen.
     */
    internal fun putBack(target: File, previous: ByteArray?, move: (File, File) -> Unit) {
        if (previous == null) {
            Files.deleteIfExists(target.toPath())
            return
        }
        val restoreTemp = restoreTempFileFor(target)
        var moved = false
        try {
            Files.write(restoreTemp.toPath(), previous)
            move(restoreTemp, target)
            moved = true
        } finally {
            if (!moved) deleteQuietly(restoreTemp)
        }
    }

    /**
     * Removes [file] if it is there, and ignores a failure to do so. The caller
     * is already reporting a failed save, and that exception must not be hidden.
     */
    internal fun deleteQuietly(file: File) {
        try {
            Files.deleteIfExists(file.toPath())
        } catch (ignored: IOException) {
            // The next save overwrites a leftover temp file, so nothing is lost.
        }
    }
}
