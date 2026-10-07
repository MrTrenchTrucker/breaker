package dev.breaker.dictation.stt.ondevice

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Verifies a model archive against a compiled pin and the upstream checksum file.
 *
 * The rule this file keeps: a downloaded archive is trusted only when its
 * computed digest equals our compiled pin AND that pin appears as a digest
 * in the upstream checksum file. The pin is the digest of the downloaded
 * archive itself - it is not a version number, a size, or a filename.
 *
 * Verification is deliberately strict and ordered. Each step has its own
 * refusal category so the caller can distinguish a missing file from a
 * malformed pin from a digest mismatch. The order is part of the contract:
 * tests assert the FIRST failure, not any failure.
 */
object ModelIntegrity {

    /** Buffer size for streaming digest computation (64 KiB). */
    const val BUFFER_BYTES: Int = 64 * 1024

    /** Length of a SHA-256 digest in lowercase hex characters. */
    const val HEX_LENGTH: Int = 64

    /**
     * The outcome of a verification attempt.
     */
    sealed class Verdict {
        /** The archive was verified; [digest] is the computed SHA-256 (lowercase hex). */
        data class Verified(val digest: String) : Verdict()

        /** The archive was refused; [refusal] categorises why, [detail] explains. */
        data class Refused(val refusal: Refusal, val detail: String) : Verdict()

        /** True when the archive passed verification. */
        val isVerified: Boolean get() = this is Verified
    }

    /**
     * Why verification was refused.
     */
    enum class Refusal {
        /** The file does not exist. */
        FILE_MISSING,

        /** The path exists but is not a readable regular file. */
        NOT_A_READABLE_FILE,

        /** The file is empty (zero bytes). */
        FILE_EMPTY,

        /** The file exists and is readable but its digest could not be computed. */
        DIGEST_UNREADABLE,

        /** The pin is not a 64-character hex string. */
        PIN_MALFORMED,

        /** The pin does not appear as a digest in the upstream checksum file. */
        PIN_NOT_IN_UPSTREAM,

        /** The computed digest does not match the pin. */
        PIN_MISMATCH,
    }

    /**
     * Verify [file] against [pin] and [checksums].
     *
     * The order of checks is part of the contract - each step is evaluated
     * before the next, and the first failure is the one reported:
     *
     * 1. `!file.exists()` -> [Refusal.FILE_MISSING]
     * 2. `!file.isFile || !file.canRead()` -> [Refusal.NOT_A_READABLE_FILE]
     * 3. `file.length() == 0L` -> [Refusal.FILE_EMPTY]
     * 4. pin does not match `^[0-9a-fA-F]{64}$` -> [Refusal.PIN_MALFORMED]
     *    (checked before any bytes are read - a malformed pin can never match)
     * 5. `!checksums.containsDigest(pin)` -> [Refusal.PIN_NOT_IN_UPSTREAM]
     * 6. `sha256(file) == null` -> [Refusal.DIGEST_UNREADABLE]
     * 7. computed digest != pin (case-insensitive) -> [Refusal.PIN_MISMATCH]
     * 8. otherwise -> [Verdict.Verified]
     *
     * [archiveName] is reserved for the per-archive check (upstream's line for
     * this archive must carry the pin); it is not used until the model registry
     * records the name.
     *
     * @param archiveName the archive's upstream file name; reserved, not used yet.
     * @return [Verdict.Verified] with the computed digest on success,
     *   [Verdict.Refused] otherwise.
     */
    fun verify(
        file: File,
        pin: String,
        checksums: UpstreamChecksums.Checksums,
        archiveName: String? = null,
    ): Verdict {
        if (!file.exists()) {
            return Verdict.Refused(Refusal.FILE_MISSING, "file not found: ${file.absolutePath}")
        }

        if (!file.isFile || !file.canRead()) {
            return Verdict.Refused(Refusal.NOT_A_READABLE_FILE, "not a readable file: ${file.absolutePath}")
        }

        if (file.length() == 0L) {
            return Verdict.Refused(Refusal.FILE_EMPTY, "file is empty: ${file.absolutePath}")
        }

        if (!pin.matches(PIN_REGEX)) {
            return Verdict.Refused(Refusal.PIN_MALFORMED, "pin is not a 64-character hex string")
        }

        if (!checksums.containsDigest(pin)) {
            return Verdict.Refused(
                Refusal.PIN_NOT_IN_UPSTREAM,
                "pin ${pin.lowercase()} not found in upstream checksums"
            )
        }

        val computed = sha256(file)
            ?: return Verdict.Refused(
                Refusal.DIGEST_UNREADABLE,
                "could not compute digest for: ${file.absolutePath}"
            )

        if (!computed.equals(pin, ignoreCase = true)) {
            return Verdict.Refused(
                Refusal.PIN_MISMATCH,
                "expected ${pin.lowercase()}, found $computed"
            )
        }

        return Verdict.Verified(computed)
    }

    /**
     * Compute the SHA-256 digest of [file].
     *
     * Returns null on [IOException] or [SecurityException] - the caller
     * decides how to handle an unreadable file. This method never throws
     * for filesystem errors.
     *
     * @return the lowercase hex digest, or null if the file could not be read.
     */
    fun sha256(file: File): String? {
        return try {
            file.inputStream().use { sha256(it) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Compute the SHA-256 digest of [stream].
     *
     * The stream is read in [BUFFER_BYTES] chunks - a model archive is
     * hundreds of megabytes and must never be slurped into memory whole.
     * The stream is closed by this method.
     *
     * @return the lowercase hex digest.
     * @throws IOException if reading fails.
     */
    fun sha256(stream: InputStream): String {
        val digest = newSha256Digest()
        val buffer = ByteArray(BUFFER_BYTES)

        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().toHex()
    }

    /**
     * Compute the SHA-256 digest of [bytes].
     *
     * @return the lowercase hex digest.
     */
    fun sha256(bytes: ByteArray): String {
        val digest = newSha256Digest()
        digest.update(bytes)
        return digest.digest().toHex()
    }

    private fun newSha256Digest(): MessageDigest =
        try {
            MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            // SHA-256 is guaranteed by the JVM spec; this should never happen.
            throw IllegalStateException("SHA-256 not available", e)
        }

    private val PIN_REGEX = Regex("^[0-9a-fA-F]{64}$")

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            chars[i * 2] = HEX_CHARS[v ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(chars)
    }
}
