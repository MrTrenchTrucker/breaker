package dev.breaker.dictation

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Remembers the two instants the start-up purge guard needs: the wall-clock
 * instant the last purge actually RAN ([lastPurgeAt]), and, once a forward clock
 * jump is suspected, the instant the capped deferral began
 * ([deferralStartedAt]). Both are `0` when unset.
 *
 * The store is one small two-line file in the app's files folder, written the
 * same way as [FirstRunDoneStore]: a temporary file beside it, renamed over it
 * in one atomic step, and no exception ever escapes; a bookkeeping write that
 * failed only means the guard re-derives its decision next start, which is safe
 * because the decision is conservative. The two longs are read back together,
 * so a partial or unreadable file answers unset for both rather than one.
 *
 * It keys the retention overrun on [lastPurgeAt], the last purge that ran, and
 * never on the newest record: a row stamped under a wrong clock must not end the
 * protection.
 */
class PurgeBookkeepingStore(private val file: File) {

    /** The instant the last purge ran, or [UNSET] when none has run yet. Never throws. */
    fun lastPurgeAt(): Long = readPair().first

    /** The instant the capped deferral began, or [UNSET] when no jump is being deferred. Never throws. */
    fun deferralStartedAt(): Long = readPair().second

    /** Record that a purge ran at [at], leaving the deferral as it was. Returns false when the write failed. */
    fun recordLastPurgeAt(at: Long): Boolean = write(at, readPair().second)

    /** Record that a deferral began at [at], leaving the last purge as it was. Returns false when the write failed. */
    fun recordDeferralStartedAt(at: Long): Boolean = write(readPair().first, at)

    /** Clear the deferral, leaving the last purge as it was. Returns false when the write failed. */
    fun clearDeferralStartedAt(): Boolean = write(readPair().first, UNSET)

    /** Both instants at once; an unreadable or absent file answers unset for both. Never throws. */
    private fun readPair(): Pair<Long, Long> =
        try {
            val lines = Files.readAllLines(file.toPath())
            val first = lines.getOrElse(0) { "" }.toLongOrNull() ?: UNSET
            val second = lines.getOrElse(1) { "" }.toLongOrNull() ?: UNSET
            first to second
        } catch (e: Exception) {
            UNSET to UNSET
        }

    /** Writes both instants to a temporary file and renames it over the store in one step. */
    private fun write(first: Long, second: Long): Boolean =
        try {
            writeMark("$first\n$second\n")
            true
        } catch (e: Exception) {
            false
        }

    private fun writeMark(text: String) {
        val folder = file.absoluteFile.parentFile ?: throw IOException("the purge bookkeeping has no folder")
        Files.createDirectories(folder.toPath())
        val temp = File(folder, file.name + TEMP_SUFFIX)
        try {
            Files.write(temp.toPath(), text.toByteArray(StandardCharsets.UTF_8))
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
        const val FILE_NAME: String = "purge-bookkeeping"

        /** The value an unset instant holds: no purge has run, or no deferral has begun. */
        const val UNSET: Long = 0L

        private const val TEMP_SUFFIX: String = ".tmp"
    }
}
