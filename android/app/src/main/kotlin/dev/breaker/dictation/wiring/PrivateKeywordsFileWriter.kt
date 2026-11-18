package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.KeywordsFileWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Puts the text of the keywords file in a private folder of the app and returns the file.
 *
 * The text is written to a temporary file with a fixed name beside the target, made readable and writable
 * by its owner only, and moved over the target in one atomic step, so a reader sees the old text or the
 * new text and never a part of either. A second write replaces the content of the first. When any step
 * fails, the temporary file is removed and the old target is left as it was. The folder is made readable
 * only by its owner too. There is no sync to disk, so a power cut during the move is outside what this
 * guarantees. A step that cannot make a file private fails with an [IOException] with a fixed message.
 *
 * Nothing here downloads the keyword model or turns the spoken send on; this class only writes a text file.
 *
 * @param directory the private folder, under the files folder of the app; it is made when it is missing.
 * @param moveOver moves the first file over the second in one atomic step; a test may replace it.
 */
internal class PrivateKeywordsFileWriter(
    private val directory: File,
    private val moveOver: (File, File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    },
) : KeywordsFileWriter {

    override fun write(text: String): File {
        val folder: File = directory.absoluteFile
        Files.createDirectories(folder.toPath())
        lockDown(folder)
        val target = File(folder, FILE_NAME)
        val temp = File(folder, TEMP_NAME)
        var moved = false
        try {
            Files.write(temp.toPath(), text.toByteArray(Charsets.UTF_8))
            lockDown(temp)
            moveOver(temp, target)
            moved = true
        } finally {
            if (!moved) discard(temp)
        }
        return target
    }

    /** Takes every right of the group and of others from [file] and leaves the owner read, write and, for a folder, search. */
    private fun lockDown(file: File) {
        val closed: Boolean = file.setReadable(false, false) && file.setWritable(false, false) && file.setExecutable(false, false)
        val owned: Boolean = file.setReadable(true, true) && file.setWritable(true, true) && (!file.isDirectory || file.setExecutable(true, true))
        if (!closed || !owned) throw IOException(MESSAGE_NOT_PRIVATE)
    }

    /** Drops a temporary file left by a failed write; safe to call when it was never made. */
    private fun discard(temp: File) {
        try {
            Files.deleteIfExists(temp.toPath())
        } catch (e: IOException) {
            // A leftover temporary file is replaced by the next write.
        }
    }

    companion object {
        /** The name of the keywords file inside the private folder. */
        const val FILE_NAME: String = "keywords.txt"

        /** The one temporary name, in the same folder as the target so the move stays on one file system. */
        const val TEMP_NAME: String = "keywords.txt.tmp"

        /** The fixed text of the refusal when a file or folder cannot be made private. */
        const val MESSAGE_NOT_PRIVATE: String = "the keywords file could not be made private"
    }
}
