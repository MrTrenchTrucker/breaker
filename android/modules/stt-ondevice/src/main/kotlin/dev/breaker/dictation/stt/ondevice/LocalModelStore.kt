package dev.breaker.dictation.stt.ondevice

import java.io.File
import java.io.IOException

/**
 * Manages the on-disk layout of installed models.
 *
 * The store keeps one directory per model under [root]. Each directory holds
 * the model archive, a marker file recording the last verified digest, and
 * the upstream checksum file used at install time.
 *
 * Staging lives inside the models root so the move into a model directory
 * is a same-filesystem rename, not a copy - a crash can therefore never
 * leave a half-copied archive where the loader reads.
 *
 * Division of responsibility for unsafe ids: [directoryFor] is the one place
 * a bad id must be loud (it `require`s a safe name), because it returns a path
 * that callers will write to. The question-answering calls - [isInstalled],
 * [modelFiles], [lastVerifiedDigest], [storedChecksums], [delete] - answer
 * safely instead of throwing, so an untrusted id can never crash a caller
 * that is merely asking. The writing calls - [markVerified], [storeChecksums] -
 * return quietly without creating anything when the id is unsafe.
 *
 * @param remove deletes a model directory tree for [delete] and reports whether
 *   it is gone; it can be replaced so a test can make that delete fail.
 * @param discard deletes one file for [discardFailedDownload] and reports whether
 *   it is gone; the default does not remove a non-empty directory, and it can be
 *   replaced so a test can make that delete fail.
 */
class LocalModelStore(
    private val root: File,
    private val remove: (File) -> Boolean = { it.deleteRecursively() },
    private val discard: (File) -> Boolean = { it.delete() },
) {

    companion object {
        /** Name of the marker file that records the last verified digest. */
        const val VERIFIED_FILE: String = ".verified"

        /** Name of the file that stores the upstream checksum text. */
        const val CHECKSUMS_FILE: String = ".checksums"

        /** Name of the staging directory (inside the models root). */
        const val STAGING_DIR: String = ".staging"

        /** Name of the model archive file inside each model directory. */
        const val ARCHIVE_NAME: String = "model.archive"

        /**
         * Returns true when [name] is safe to use as a model id.
         *
         * An id from an untrusted source must never become a path. This
         * check rejects path separators, dot-names (which would collide with
         * the staging directory and marker files), and any character outside
         * a conservative safe set.
         *
         * Rules: non-empty; length <= 64; not "." or ".."; no '/' or '\\';
         * does not start with '.'; every character is a letter, digit,
         * '.', '-', or '_'.
         */
        fun isSafeName(name: String): Boolean {
            if (name.isEmpty() || name.length > 64) return false
            if (name == "." || name == "..") return false
            if (name.startsWith('.')) return false
            if (name.contains('/') || name.contains('\\')) return false
            return name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        }
    }

    /**
     * Returns the directory for [modelId].
     *
     * @throws IllegalArgumentException if [modelId] is not a safe name.
     */
    fun directoryFor(modelId: String): File {
        require(isSafeName(modelId)) { "unsafe model id: '$modelId'" }
        return File(root, modelId)
    }

    /**
     * Returns true when [modelId] is installed: its directory exists and the
     * archive is a regular, non-empty file. A directory at the archive path
     * is not an installed model.
     *
     * An unsafe id answers false rather than throwing - a caller asking
     * "is this installed?" must not crash on a bad id.
     */
    fun isInstalled(modelId: String): Boolean {
        if (!isSafeName(modelId)) return false
        val dir = directoryFor(modelId)
        if (!dir.isDirectory) return false
        val archive = archiveFile(modelId)
        return archive.isFile && archive.length() > 0L
    }

    /**
     * Returns the archive file for [modelId]: `<root>/<id>/model.archive`.
     */
    fun archiveFile(modelId: String): File =
        File(directoryFor(modelId), ARCHIVE_NAME)

    /**
     * Returns a map of readable files in the model directory, keyed by
     * filename. Marker files ([VERIFIED_FILE], [CHECKSUMS_FILE]) are excluded.
     *
     * An unsafe id answers with an empty map rather than throwing.
     */
    fun modelFiles(modelId: String): Map<String, File> {
        if (!isSafeName(modelId)) return emptyMap()
        val dir = directoryFor(modelId)
        if (!dir.isDirectory) return emptyMap()

        val excluded = setOf(VERIFIED_FILE, CHECKSUMS_FILE)
        return dir.listFiles()
            ?.filter { it.isFile && it.canRead() && it.name !in excluded }
            ?.associateBy { it.name }
            ?: emptyMap()
    }

    /**
     * Returns the sorted list of installed model ids.
     *
     * Only safe names are considered, and only models that pass
     * [isInstalled] are included.
     */
    fun installedModelIds(): List<String> {
        if (!root.isDirectory) return emptyList()

        return root.listFiles()
            ?.filter { it.isDirectory && isSafeName(it.name) }
            ?.map { it.name }
            ?.filter { isInstalled(it) }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * Record that [modelId] was verified with [digest].
     *
     * Writes the digest to the `.verified` marker file inside the model
     * directory. Creates the directory if needed.
     *
     * An unsafe id returns quietly without creating anything - writing
     * a marker under a path derived from an untrusted id is never safe.
     */
    fun markVerified(modelId: String, digest: String) {
        if (!isSafeName(modelId)) return
        val dir = directoryFor(modelId)
        if (!dir.exists()) dir.mkdirs()
        File(dir, VERIFIED_FILE).writeText(digest)
    }

    /**
     * Returns the last verified digest for [modelId], or null if none
     * has been recorded or the marker file is unreadable.
     *
     * An unsafe id answers null rather than throwing.
     */
    fun lastVerifiedDigest(modelId: String): String? {
        if (!isSafeName(modelId)) return null
        val marker = File(directoryFor(modelId), VERIFIED_FILE)
        if (!marker.isFile || !marker.canRead()) return null
        return try {
            marker.readText().trim().ifEmpty { null }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Store the upstream checksum text for [modelId].
     *
     * Writes [text] to the `.checksums` file inside the model directory.
     * Creates the directory if needed.
     *
     * An unsafe id returns quietly without creating anything.
     */
    fun storeChecksums(modelId: String, text: String) {
        if (!isSafeName(modelId)) return
        val dir = directoryFor(modelId)
        if (!dir.exists()) dir.mkdirs()
        File(dir, CHECKSUMS_FILE).writeText(text)
    }

    /**
     * Returns the stored upstream checksum text for [modelId], or null
     * if none has been stored or the file is unreadable.
     *
     * An unsafe id answers null rather than throwing.
     */
    fun storedChecksums(modelId: String): String? {
        if (!isSafeName(modelId)) return null
        val file = File(directoryFor(modelId), CHECKSUMS_FILE)
        if (!file.isFile || !file.canRead()) return null
        return try {
            file.readText()
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Delete the model directory for [modelId] recursively.
     *
     * An unsafe id answers false rather than throwing - there is nothing
     * to delete for a name that can never have been a directory.
     *
     * @return true if the directory existed and was deleted, false if it
     *   did not exist or could not be removed.
     */
    fun delete(modelId: String): Boolean {
        if (!isSafeName(modelId)) return false
        val dir = directoryFor(modelId)
        if (!dir.exists()) return false
        return remove(dir)
    }

    /**
     * Best-effort cleanup of a failed download.
     *
     * Catches its own failures and returns false, because a cleanup
     * failure must never mask the original reason the download was refused.
     *
     * @return true if the file was deleted, false otherwise.
     */
    fun discardFailedDownload(file: File): Boolean {
        return try {
            if (file.exists()) discard(file) else true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the staging directory: `<root>/.staging`.
     *
     * Staging lives inside the models root so the move into a model
     * directory is a same-filesystem rename, not a copy - a crash can
     * therefore never leave a half-copied archive where the loader reads.
     */
    fun stagingDirectory(): File = File(root, STAGING_DIR)
}
