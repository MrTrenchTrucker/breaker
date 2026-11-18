package dev.breaker.dictation.wiring

import dev.breaker.dictation.updater.UpdateTimes
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Keeps the two update times in two small files in one folder: the time of the last update check and the
 * time of the last update install, each as its decimal milliseconds with no newline.
 *
 * A value is read only when it is a plain whole number of one to eighteen ASCII digits. A missing, empty,
 * unreadable, signed or longer file reads as null. A write goes to a temporary file beside the target and is
 * then moved over it in one step, so a failed write leaves the old value in place. The record calls never throw.
 *
 * It is the app's [UpdateTimes] port, which the updater reads and writes the two times through.
 */
class UpdateTimesStore(private val folder: File) : UpdateTimes {

    /** The time of the last update check, or null when none is stored. Never throws. */
    override fun lastCheckAtMs(): Long? = readTime(CHECK_FILE)

    /** Stores the time of an update check. Never throws. */
    override fun recordCheckAtMs(atMs: Long) {
        writeTime(CHECK_FILE, atMs)
    }

    /** The time of the last update install, or null when none is stored. Never throws. */
    fun lastUpdateAtMs(): Long? = readTime(INSTALL_FILE)

    /** Stores the time of an update install. Never throws. */
    override fun recordUpdateAtMs(atMs: Long) {
        writeTime(INSTALL_FILE, atMs)
    }

    private fun readTime(name: String): Long? =
        try {
            val path: Path = File(folder, name).toPath()
            if (Files.size(path) > MAX_FILE_BYTES) {
                null
            } else {
                parseTime(String(Files.readAllBytes(path), StandardCharsets.US_ASCII))
            }
        } catch (e: Exception) {
            null
        }

    private fun parseTime(text: String): Long? {
        if (text.isEmpty() || text.length > MAX_DIGITS) return null
        if (!text.all { it in '0'..'9' }) return null
        return text.toLongOrNull()
    }

    private fun writeTime(name: String, atMs: Long) {
        val temp: File = File(folder, name + TEMP_SUFFIX)
        try {
            Files.createDirectories(folder.toPath())
            Files.write(temp.toPath(), atMs.toString().toByteArray(StandardCharsets.US_ASCII))
            Files.move(temp.toPath(), File(folder, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            discard(temp)
        }
    }

    private fun discard(temp: File) {
        try {
            Files.deleteIfExists(temp.toPath())
        } catch (e: Exception) {
            // the write already failed; a leftover temporary file is replaced by the next write
        }
    }

    private companion object {
        const val CHECK_FILE: String = "update-last-check"
        const val INSTALL_FILE: String = "update-last-install"
        const val TEMP_SUFFIX: String = ".tmp"
        const val MAX_DIGITS: Int = 18
        const val MAX_FILE_BYTES: Long = 64L
    }
}
