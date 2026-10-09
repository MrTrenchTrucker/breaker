package dev.breaker.dictation

import dev.breaker.dictation.settings.Keystore
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

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
 * **Persistence.** A ref is written as one UTF-8 line, creating parent
 * directories when needed; null or blank clears by deleting the file if it
 * exists. Restart-persistence is exact: a second `FileCredentialRefHolder`
 * over the same file reads back what the first wrote, because [activeRef]
 * simply returns that single trimmed line — there is no state beyond the file
 * to lose on restart.
 *
 * **Pure JVM.** Uses only `java.io`/`java.nio`; it carries no Android type and
 * so can be exercised directly on the test JVM, where reading the value back
 * over two instances proves persistence without an emulator.
 */
class FileCredentialRefHolder(
    private val file: java.io.File,
) : Keystore {

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
            file.parentFile?.mkdirs()
            Files.write(file.toPath(), (ref + "\n").toByteArray(StandardCharsets.UTF_8))
        }
    }

    override fun activeRef(): String? {
        if (!file.exists()) return null
        val text = file.bufferedReader().use { it.readText() }
        // Take the first line, trimmed; blank means no reference remembered.
        val firstLine = text.lines().firstOrNull()?.trim() ?: return null
        return firstLine.ifBlank { null }
    }
}
