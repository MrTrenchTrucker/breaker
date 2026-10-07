package dev.breaker.dictation.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * Derives [length] bytes with HKDF-SHA256 (RFC 5869): Extract, then Expand.
 *
 * [ikm] is the input keying material, [salt] the extract salt and [info] the
 * expand context label. An empty [salt] is the same as 32 zero bytes, the
 * hash length, as RFC 5869 section 2.2 specifies.
 *
 * This is a raw primitive and applies **no policy**: it does not check the
 * lengths or the strength of its inputs. Bouncy Castle throws if [length] is
 * more than 255 times the hash length (8160 bytes) and that exception reaches
 * the caller unchanged.
 *
 * The caller's arrays are left untouched: nothing is written to, wiped or
 * kept from [ikm], [salt] or [info]. A new array is returned every call.
 */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val generator = HKDFBytesGenerator(SHA256Digest())
    generator.init(HKDFParameters(ikm, salt, info))
    val output = ByteArray(length)
    generator.generateBytes(output, 0, length)
    return output
}
