package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import java.io.File

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
     * Fetch the upstream checksum file into [stagingDir].
     *
     * @return [Result.Fetched] with the staged checksum file, or [Result.Failed].
     */
    fun fetchChecksums(stagingDir: File): Result
}
