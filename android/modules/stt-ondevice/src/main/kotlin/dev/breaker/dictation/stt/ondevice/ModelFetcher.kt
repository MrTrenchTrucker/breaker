package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import java.io.File
import java.io.IOException

/**
 * The module's ONLY network boundary.
 *
 * The real implementation must request the release asset as a binary stream.
 * The GitHub API asset route returns JSON metadata unless the octet-stream
 * accept header is sent, and the digest check would then reject it. A test
 * supplies its own implementation so no test opens a socket.
 *
 * The returned file is written under the given staging directory.
 */
interface ModelFetcher {
    /**
     * The outcome of a fetch attempt.
     */
    sealed class Result {
        /** The file was fetched and written to [file]. */
        data class Fetched(val file: File) : Result()

        /** The fetch failed; [reason] is a short, safe explanation. */
        data class Failed(val reason: String) : Result()
    }

    /**
     * Fetch the model archive for [entry] into [stagingDir].
     *
     * The implementation must request the asset as a binary stream (see
     * class KDoc) and write the bytes to a file under [stagingDir].
     *
     * @return [Result.Fetched] with the staged file, or [Result.Failed].
     */
    fun fetchModel(entry: ModelEntry, stagingDir: File): Result

    /**
     * Fetch the model archive for [entry] into [stagingDir], reporting progress.
     *
     * [onProgress] receives the bytes written so far and the total from the final
     * reply's length, or -1 when that length is unknown. It is called on the thread
     * that runs the fetch. MB in this project means 1,000,000 bytes. The fetcher
     * does not guard the callback; ModelInstaller does.
     *
     * The default body ignores progress and calls the two-argument form, so a
     * fetcher that only implements that form keeps working.
     */
    fun fetchModel(
        entry: ModelEntry,
        stagingDir: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Result = fetchModel(entry, stagingDir)

    /**
     * Fetch the upstream checksum file into [stagingDir].
     *
     * @return [Result.Fetched] with the staged checksum file, or [Result.Failed].
     */
    fun fetchChecksums(stagingDir: File): Result
}

/**
 * Thrown by a fetcher when the free-space check fails before any byte is
 * written. It is an IOException, so a caller that only knows the port still
 * treats it as a staging problem.
 */
internal class NoSpaceException(message: String = "not enough space") : IOException(message)
