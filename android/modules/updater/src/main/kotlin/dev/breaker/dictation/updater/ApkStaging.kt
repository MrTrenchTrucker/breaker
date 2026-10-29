package dev.breaker.dictation.updater

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** The fixed file name of the staged update. A download never takes its name from the URL. */
internal const val STAGED_FILE_NAME: String = "update-candidate.apk"

/**
 * The app-private folder that holds the staged update: one file under [STAGED_FILE_NAME]. Each write truncates it.
 */
internal class ApkStaging(private val dir: File) : StagingSlot {
    private val target: File = File(dir, STAGED_FILE_NAME)

    override fun openForWrite(): OutputStream? = try { target.outputStream() } catch (error: IOException) { null }

    override fun stagedApk(): StagedApk = FileStagedApk(target)

    override fun removeQuietly() {
        stagedApk().discard()
    }
}

/**
 * The staged update file, held until the install ends. [open] gives null when the file cannot be opened. [discard]
 * never throws; a file it cannot remove is recorded in [leftover].
 */
internal class FileStagedApk(private val target: File) : StagedApk {
    /** True when the last [discard] could not remove the file. */
    var leftover: Boolean = false
        private set

    override fun open(): InputStream? = try { Files.newInputStream(target.toPath()) } catch (error: IOException) { null }

    override fun discard() {
        try {
            Files.deleteIfExists(target.toPath())
        } catch (error: Exception) {
            leftover = true
        }
    }
}
