package dev.breaker.dictation.updater

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** The outcome of copying a file through a digest check and a size cap. */
internal sealed class CheckedCopy {
    /** The bytes matched the expected digest and were within the cap. */
    internal object Matched : CheckedCopy()

    /** The bytes did not match the expected digest, or the expected digest is not 64 lowercase hexadecimal characters. */
    internal object Mismatched : CheckedCopy()

    /** More bytes were present than the cap allows. */
    internal object TooLarge : CheckedCopy()

    /** The input could not be read. */
    internal object Failed : CheckedCopy()
}

/**
 * Copies [input] to [out] and hashes the same bytes with SHA-256 in one pass.
 *
 * The expected digest is checked for its shape before any byte is read. At most [capBytes] plus one byte is read from
 * [input]. The extra byte, when present, is written to [out] before the copy is found to be too large, so the caller
 * discards [out] on any result other than Matched.
 */
internal fun copyChecked(input: InputStream, out: OutputStream, expectedSha256: String, capBytes: Long): CheckedCopy {
    if (!isLowerSha256Hex(expectedSha256)) return CheckedCopy.Mismatched
    val digest = MessageDigest.getInstance("SHA-256")
    val copied = copyUpTo(input, capBytes + 1L) { bytes, offset, length ->
        digest.update(bytes, offset, length)
        out.write(bytes, offset, length)
    } ?: return CheckedCopy.Failed
    if (copied > capBytes) return CheckedCopy.TooLarge
    return if (toLowerHex(digest.digest()) == expectedSha256) CheckedCopy.Matched else CheckedCopy.Mismatched
}
