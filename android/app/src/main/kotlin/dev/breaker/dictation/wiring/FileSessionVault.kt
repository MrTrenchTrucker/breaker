package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.SessionVault
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A [SessionVault] backed by one file in the app's files directory, sealed by a
 * [SessionSeal]. Writes go to a temporary file beside the target and are renamed
 * over it in one atomic step, the same move the first-run marker uses, so a reader
 * never sees a half-written file and a failed write leaves the old value and no
 * temporary file behind.
 */
internal class FileSessionVault(
    private val file: File,
    private val seal: SessionSeal,
) : SessionVault {

    override fun save(record: String) {
        val bytes: ByteArray = seal.seal(record.toByteArray(StandardCharsets.UTF_8))
        if (bytes.size > SIZE_LIMIT) throw IOException("record too large")
        val folder: File = file.absoluteFile.parentFile ?: throw IOException("no folder")
        Files.createDirectories(folder.toPath())
        val temp: File = File(folder, file.name + TEMP_SUFFIX)
        try {
            Files.write(temp.toPath(), bytes)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            discard(temp)
            throw e
        }
    }

    override fun load(): String? {
        if (!file.isFile) return null
        if (file.length() > SIZE_LIMIT) return null
        return try {
            val plain: ByteArray = seal.unseal(file.readBytes())
            String(plain, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun clear() {
        if (!file.delete() && file.exists()) {
            throw IOException("could not delete " + file.name)
        }
    }

    /** Drops a leftover temporary file; safe to call when it was never created. */
    private fun discard(temp: File) {
        try {
            Files.deleteIfExists(temp.toPath())
        } catch (e: IOException) {
            // A leftover temporary file is overwritten by the next write.
        }
    }

    companion object {
        private const val TEMP_SUFFIX: String = ".tmp"
        /** 64 KiB cap: a file larger than this is unreadable, untouched, never read into memory. */
        private const val SIZE_LIMIT: Long = 65536L
    }
}
