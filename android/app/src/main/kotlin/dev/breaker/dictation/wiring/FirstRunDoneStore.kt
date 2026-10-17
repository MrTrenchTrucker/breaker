package dev.breaker.dictation.wiring

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Remembers that the first run is finished: the download page saw the speech model ready.
 *
 * The marker is one small file in the app's files folder. Its presence is the whole fact; its text
 * is never read.
 */
class FirstRunDoneStore(private val file: File) {

    /** True when the marker file is there. Never throws; a marker that cannot be inspected answers false. */
    fun isDone(): Boolean =
        try {
            file.isFile
        } catch (e: Exception) {
            false
        }

    /**
     * Writes the marker, creating its folder when it is missing. Returns true when the marker was written
     * and false when the write failed. It never throws.
     */
    fun markDone(): Boolean =
        try {
            writeMark()
            true
        } catch (e: Exception) {
            false
        }

    /** Writes the marker to a temporary file beside it and renames it over the marker in one step. */
    private fun writeMark() {
        val folder = file.absoluteFile.parentFile ?: throw IOException("the first-run marker has no folder")
        Files.createDirectories(folder.toPath())
        val temp = File(folder, file.name + TEMP_SUFFIX)
        try {
            Files.write(temp.toPath(), MARK.toByteArray(StandardCharsets.UTF_8))
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

    companion object {
        const val FILE_NAME: String = "first-run-done"
        private const val TEMP_SUFFIX: String = ".tmp"
        private const val MARK: String = "done\n"
    }
}

/** The first-run decision for the two facts the launcher can read at start. */
object FirstRunGate {
    enum class Decision { FIRST_RUN, MARK_DONE_QUIETLY, NORMAL }

    /**
     * A marker that is there ends the first run for good. Without it, a ready model means an older install
     * that only needs the marker written, and a missing model means the first run is still to be shown.
     */
    fun decide(done: Boolean, modelReady: Boolean): Decision =
        when {
            done -> Decision.NORMAL
            modelReady -> Decision.MARK_DONE_QUIETLY
            else -> Decision.FIRST_RUN
        }
}
