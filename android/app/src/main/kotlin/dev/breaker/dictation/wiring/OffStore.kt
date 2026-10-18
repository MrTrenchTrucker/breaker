package dev.breaker.dictation.wiring

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Remembers one fact across a process death: the user switched dictation off.
 *
 * Only an explicit "off" from the user is kept here. The service being killed or the owner of the
 * dictation parts closing is not the user's word and is never written.
 */
interface OffStore {
    /** True when the user's off choice is stored. Never throws; an unreadable store answers false. */
    fun isOff(): Boolean

    /**
     * Stores the off choice ([off] true) or clears it ([off] false).
     *
     * @throws IOException when the choice could not be written or cleared; the stored value is then unchanged.
     */
    fun setOff(off: Boolean)
}

/**
 * An [OffStore] kept as the presence of one small file: present means off.
 *
 * Switching off writes a temporary file beside the target and renames it over the target in one
 * atomic step, so a kill in the middle leaves either no file or the whole file, never a half-written
 * one. Switching on deletes the file. Plain `java.io` and `java.nio`, so it runs on the test JVM.
 */
class FileOffStore internal constructor(
    private val file: File,
    private val beforeMove: (File) -> Unit,
) : OffStore {

    /** Stores the choice in [file]; its folder is created when it is missing. */
    constructor(file: File) : this(file, {})

    override fun isOff(): Boolean =
        try {
            file.isFile
        } catch (e: Exception) {
            false
        }

    override fun setOff(off: Boolean) {
        if (off) writeMark() else Files.deleteIfExists(file.toPath())
    }

    private fun writeMark() {
        val folder = file.absoluteFile.parentFile ?: throw IOException("the off file has no folder")
        Files.createDirectories(folder.toPath())
        val temp = File(folder, file.name + TEMP_SUFFIX)
        try {
            Files.write(temp.toPath(), MARK.toByteArray(StandardCharsets.UTF_8))
            beforeMove(temp)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            discard(temp)
            throw e
        }
    }

    private fun discard(temp: File) {
        try {
            Files.deleteIfExists(temp.toPath())
        } catch (e: IOException) {
            // The write already failed; a leftover temporary file is replaced by the next write.
        }
    }

    private companion object {
        const val TEMP_SUFFIX: String = ".tmp"
        const val MARK: String = "off\n"
    }
}
