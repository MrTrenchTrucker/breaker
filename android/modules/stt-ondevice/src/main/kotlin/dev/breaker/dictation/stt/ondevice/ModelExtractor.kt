package dev.breaker.dictation.stt.ondevice

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Unpacks a verified model archive (tar inside bzip2) into the few files the
 * engine opens.
 *
 * This is the only file of the module that imports the archive library.
 *
 * What it does, in order:
 *  - refuses when the target directory already exists, before any work;
 *  - removes a work directory left by an earlier run, checks the free space
 *    against the written-bytes bound, then creates the work directory;
 *  - reads the archive through a 64 KiB buffer, the bzip2 decoder, a counter
 *    of decompressed bytes ([BoundedStream]) and the tar reader;
 *  - checks every entry (also the ones it will skip) against the counters and
 *    [EntryRules], then writes only the files named by the profile, flat into
 *    the work directory, under the profile's own names (never an archive name);
 *  - after the tar end reads the stream to its very end, so a missing
 *    compression trailer or bytes after it are noticed;
 *  - checks that every profile file arrived with content, then moves the work
 *    directory into place with ONE rename.
 *
 * Every refusal closes the streams, deletes the work directory, creates no
 * target and never touches the archive. Archive content and I/O trouble are
 * reported as [ExtractionOutcome.Rejected]; nothing is thrown for them.
 * Call [supports] first: a model id without a profile is rejected too.
 *
 * @param usableSpace free bytes at a directory; replaceable for tests.
 * @param open opens the archive for reading; replaceable for tests.
 * @param openOutput opens one output file; replaceable for tests.
 * @param syncFile forces one written file to storage before it is closed.
 * @param rename moves the finished work directory to the target.
 */
class ModelExtractor(
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val open: (File) -> InputStream = { it.inputStream() },
    private val openOutput: (File) -> OutputStream = { FileOutputStream(it) },
    private val syncFile: (OutputStream) -> Unit = { (it as? FileOutputStream)?.fd?.sync() },
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {

    /** True when [modelId] has an unpack profile. */
    fun supports(modelId: String): Boolean = ExtractionProfiles.forModel(modelId) != null

    /**
     * Unpacks [archive] for [modelId] into the store's work directory and moves
     * it to the store's extracted directory.
     */
    fun extract(archive: File, modelId: String, store: LocalModelStore): ExtractionOutcome {
        val profile = ExtractionProfiles.forModel(modelId)
            ?: return ExtractionOutcome.Rejected(ExtractionReason.MISSING_FILE, "no unpack profile for this model")
        return extract(archive, profile, store.extractionWorkDirectory(modelId), store.extractedDirectory(modelId))
    }

    /** The same with explicit places and limits; tests pass small limits and temporary directories. */
    internal fun extract(archive: File, profile: ExtractionProfile, workDir: File, target: File): ExtractionOutcome {
        if (target.exists()) return reject(ExtractionReason.TARGET_EXISTS, "the target directory already exists")
        val stop = try {
            return unpack(archive, profile, workDir, target)
        } catch (s: Stop) {
            s
        } catch (e: IOException) {
            classify(e)
        } catch (e: RuntimeException) {
            classify(e)
        }
        workDir.deleteRecursively()
        return reject(stop.reason, stop.detail)
    }

    private fun reject(reason: ExtractionReason, detail: String) = ExtractionOutcome.Rejected(reason, detail)

    private fun unpack(archive: File, profile: ExtractionProfile, workDir: File, target: File): ExtractionOutcome {
        val limits = profile.limits
        prepare(workDir, limits)
        val written = HashMap<String, Long>()
        val seen = HashSet<String>()
        var top: String? = null
        var entries = 0
        var total = 0L
        val buffer = ByteArray(COPY_BYTES)
        val raw = try {
            open.invoke(archive)
        } catch (e: IOException) {
            throw Stop(ExtractionReason.STREAM_ERROR, "the archive cannot be opened (${e.javaClass.simpleName})")
        }
        raw.use {
            val buffered = BufferedInputStream(raw, READ_BUFFER_BYTES)
            val bounded = BoundedStream(BZip2CompressorInputStream(buffered, false), limits.maxStreamBytes)
            TarArchiveInputStream(bounded).use { tar ->
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    entries++
                    if (entries > limits.maxEntries) throw Stop(ExtractionReason.TOO_MANY_ENTRIES, "more than ${limits.maxEntries} entries")
                    val bad = EntryRules.check(entry.name, entry.linkFlag, entry.isDirectory, entry.size, limits)
                    if (bad != null) throw Stop(bad, "entry $entries")
                    if (entry.isSparse) throw Stop(ExtractionReason.NOT_REGULAR, "entry $entries is a sparse file")
                    if (!entry.isCheckSumOK) throw Stop(ExtractionReason.STREAM_ERROR, "entry $entries has a damaged header")
                    val isDir = entry.isDirectory
                    val segments = normalise(entry.name).split('/')
                    if (!isDir && segments.size < 2) throw Stop(ExtractionReason.OUTSIDE_TOP, "entry $entries has no directory")
                    val first = top ?: segments[0].also { top = it }
                    if (segments[0] != first) throw Stop(ExtractionReason.OUTSIDE_TOP, "entry $entries is outside the top directory")
                    if (!seen.add(segments.joinToString("/").lowercase(Locale.ROOT))) {
                        throw Stop(ExtractionReason.DUPLICATE_NAME, "entry $entries repeats an earlier name")
                    }
                    val wanted = if (!isDir && segments.size == 2) profile.files.firstOrNull { it == segments[1] } else null
                    if (wanted != null) {
                        val bytes = writeFile(tar, File(workDir, wanted), total, limits.maxWrittenBytes, buffer)
                        written[wanted] = bytes
                        total += bytes
                    }
                }
                while (bounded.read(buffer) >= 0) { /* read to the end: the bound and the trailer check apply */ }
                if (buffered.read() >= 0) throw Stop(ExtractionReason.STREAM_ERROR, "bytes follow the end of the compressed stream")
            }
        }
        for (name in profile.files) {
            if ((written[name] ?: 0L) <= 0L) throw Stop(ExtractionReason.MISSING_FILE, "a file the engine needs is missing or empty")
        }
        commit(workDir, target)
        return ExtractionOutcome.Extracted(target, written.size, total)
    }

    private fun prepare(workDir: File, limits: ExtractionLimits) {
        val staging = workDir.parentFile ?: throw Stop(ExtractionReason.WRITE_ERROR, "the work directory has no parent")
        if (!staging.isDirectory && !staging.mkdirs() && !staging.isDirectory) {
            throw Stop(ExtractionReason.WRITE_ERROR, "the staging directory cannot be created")
        }
        if (workDir.exists() && (!workDir.deleteRecursively() || workDir.exists())) {
            throw Stop(ExtractionReason.WRITE_ERROR, "an old work directory cannot be removed")
        }
        if (usableSpace.invoke(staging) < limits.maxWrittenBytes) {
            throw Stop(ExtractionReason.NO_SPACE, "less than ${limits.maxWrittenBytes} bytes are free")
        }
        if (!workDir.mkdir()) throw Stop(ExtractionReason.WRITE_ERROR, "the work directory cannot be created")
    }

    /** Copies the current tar entry into [file]; returns the bytes written. Write trouble is WRITE_ERROR, a read failure is left to the caller. */
    private fun writeFile(tar: InputStream, file: File, before: Long, maxTotal: Long, buffer: ByteArray): Long {
        val out = try {
            openOutput.invoke(file)
        } catch (e: IOException) {
            throw Stop(ExtractionReason.WRITE_ERROR, "an output file cannot be opened (${e.javaClass.simpleName})")
        }
        val copied = try {
            var count = 0L
            while (true) {
                val n = tar.read(buffer)
                if (n < 0) break
                if (before + count + n > maxTotal) throw Stop(ExtractionReason.WRITTEN_TOO_LARGE, "more than $maxTotal bytes would be written")
                try {
                    out.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw Stop(ExtractionReason.WRITE_ERROR, "a write failed (${e.javaClass.simpleName})")
                }
                count += n
            }
            try {
                syncFile.invoke(out)
            } catch (e: IOException) {
                throw Stop(ExtractionReason.WRITE_ERROR, "a file could not be forced to storage (${e.javaClass.simpleName})")
            }
            count
        } catch (t: Throwable) {
            try { out.close() } catch (ignored: IOException) { /* the first failure is the one reported */ }
            throw t
        }
        try {
            out.close()
        } catch (e: IOException) {
            throw Stop(ExtractionReason.WRITE_ERROR, "an output file could not be closed (${e.javaClass.simpleName})")
        }
        return copied
    }

    /** The one move that makes the files visible. */
    private fun commit(workDir: File, target: File) {
        val parent = target.parentFile
        val madeParent = parent != null && !parent.exists()
        if (madeParent && !parent!!.mkdirs() && !parent.isDirectory) {
            throw Stop(ExtractionReason.COMMIT_FAILED, "the target parent cannot be created")
        }
        if (!rename.invoke(workDir, target)) {
            if (madeParent) parent!!.delete()
            throw Stop(ExtractionReason.COMMIT_FAILED, "the work directory could not be moved into place")
        }
    }

    /** One leading dot-slash and trailing slashes do not count; the result is what duplicates and the top rule compare. */
    private fun normalise(name: String): String = name.removePrefix("./").trimEnd('/')

    /** Maps a failure of the read side: the stream bound, an early end, anything else. */
    private fun classify(e: Throwable): Stop {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth++ < MAX_CAUSES) {
            if (cause is StreamLimitExceededException) return Stop(ExtractionReason.STREAM_TOO_LARGE, "the decompressed data passed the stream bound")
            if (cause is EOFException || endedEarly(cause.message)) return Stop(ExtractionReason.TRUNCATED, "the archive ends early")
            cause = cause.cause
        }
        return Stop(ExtractionReason.STREAM_ERROR, "the archive cannot be read (${e.javaClass.simpleName})")
    }

    private fun endedEarly(message: String?): Boolean {
        val text = message?.lowercase(Locale.ROOT) ?: return false
        return "truncated" in text || "unexpected end" in text || "premature" in text
    }

    /** Carries a refusal out of the reading loop; the one catch in [extract] turns it into a result. */
    private class Stop(val reason: ExtractionReason, val detail: String) : RuntimeException(detail)

    private companion object {
        const val READ_BUFFER_BYTES = 64 * 1024
        const val COPY_BYTES = 64 * 1024
        const val MAX_CAUSES = 8
    }
}
