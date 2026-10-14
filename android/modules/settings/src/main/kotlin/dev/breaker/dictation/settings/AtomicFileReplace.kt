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
 * The real steps of a save live here as well: [write] puts the document into a
 * file, [move] puts that file over the live one, [tempFileFor] names the file
 * in between, and [deleteQuietly] clears it away after a failure.
 */
internal object AtomicFileReplace {
    internal val lock: Any = Any()

    /** Properties comment written at the top of every settings file. */
    private const val HEADER_COMMENT = "Breaker settings"

    /** Appended to the live file's name to make the temp file's name. */
    private const val TEMP_SUFFIX = ".tmp"

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
