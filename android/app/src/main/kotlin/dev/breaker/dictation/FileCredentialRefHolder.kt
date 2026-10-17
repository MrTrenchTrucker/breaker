package dev.breaker.dictation

import dev.breaker.dictation.settings.Keystore
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A [Keystore] backed by a single text file rather than a device keystore.
 *
 * **What this holds.** The credential *reference* the settings point at —
 * nothing more. This class never reads, stores or returns a secret; it only
 * records which reference is currently active so that survives a restart. A
 * caller may legitimately log its contents, compare it for equality or persist
 * it elsewhere without leaking anything sensitive. The secret this reference
 * resolves against — the platform `Keystore` referenced by
 * [dev.breaker.dictation.core.model.AppSettings] — is **not built and not
 * verified**, so nothing here should be read as a claim that a key is kept
 * safe on the device. That proof comes with the work that would implement it.
 *
 * **Persistence.** A reference is written as one UTF-8 line to a temp file
 * named after the live file (`<name>.tmp`) in the same folder, then moved over
 * the live file in a single atomic move (ATOMIC_MOVE). Those two steps run under
 * one process-wide lock (the companion [lock]), so two holders over the same file
 * never interleave and never share the temp. A move that is refused throws the
 * `IOException` `Files.move` throws, naming the target; the temp file is removed
 * and the old reference is left byte-identical. A failed write likewise leaves the
 * old file byte-identical while throwing the same exception. There is no fsync, so
 * a power cut during the move is outside what this guarantees — the same limit as
 * `SettingsFileStore` (see its KDoc). Restart-persistence is exact: a second
 * `FileCredentialRefHolder` over the same file reads back what the first wrote,
 * because [activeRef] simply returns that single trimmed line — there is no state
 * beyond the file to lose on restart.
 *
 * **Pure JVM.** Uses only `java.io`/`java.nio`; it carries no Android type and
 * so can be exercised directly on the test JVM, where reading the value back
 * over two instances proves persistence without an emulator.
 */
class FileCredentialRefHolder internal constructor(
    private val file: File,
    private val writeLine: (File, String) -> Unit,
    private val moveOver: (File, File) -> Unit,
) : Keystore {

    constructor(file: File) :
        this(file, FileCredentialRefHolder::writeLineTo, FileCredentialRefHolder::moveOverFile)

    override fun setActiveRef(ref: String?) {
        if (ref.isNullOrBlank()) {
            // A null or blank reference clears whatever was remembered; only a
            // non-blank ref is stored. Deleting the absent-if-clear file first is
            // idempotent, so a second clear over an already-empty store costs no
            // more than nothing.
            if (file.exists()) {
                // Check the delete result: a failed delete that leaves the file
                // in place would keep the old reference active while the caller
                // believes it was cleared. Fail loudly, naming the file.
                if (!file.delete() && file.exists()) {
                    throw IOException("could not clear the credential ref file: ${file.absolutePath}")
                }
            }
        } else {
            // The write and the move run under one process-wide lock so two holders
            // over the same file never interleave, and never share the temp. A failed
            // step removes the temp; a moved step leaves nothing to clean up.
            synchronized(lock) {
                file.parentFile?.mkdirs()
                val temp = tempFileFor(file)
                var moved = false
                try {
                    writeLine(temp, ref)
                    moveOver(temp, file)
                    moved = true
                } finally {
                    if (!moved) deleteQuietly(temp)
                }
            }
        }
    }

    override fun activeRef(): String? {
        if (!file.exists()) return null
        val text = file.bufferedReader().use { it.readText() }
        // Take the first line, trimmed; blank means no reference remembered.
        val firstLine = text.lines().firstOrNull()?.trim() ?: return null
        return firstLine.ifBlank { null }
    }

    companion object {
        /** The one lock every write takes, shared by all holders in the process. */
        private val lock: Any = Any()

        /** Appended to the live file's name to make the temp file's name. */
        private const val TEMP_SUFFIX = ".tmp"

        /** The temp file for [target]: the same folder, with the fixed `.tmp` suffix. */
        private fun tempFileFor(target: File): File =
            File(target.absoluteFile.parentFile, target.name + TEMP_SUFFIX)

        /** The reference written as one UTF-8 line to [target]. */
        private fun writeLineTo(target: File, ref: String) {
            Files.write(target.toPath(), (ref + "\n").toByteArray(StandardCharsets.UTF_8))
        }

        /** One atomic step: [to] ends up as the moved temp or the old file, never a mix. */
        private fun moveOverFile(from: File, to: File) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }

        /**
         * Removes [file] if it is there, and ignores a failure to do so. The caller
         * is already reporting a failed write; that exception must not be hidden.
         */
        private fun deleteQuietly(file: File) {
            try {
                Files.deleteIfExists(file.toPath())
            } catch (ignored: IOException) {
                // Nothing more to do; the failure already reported stands.
            }
        }
    }
}
