package dev.breaker.dictation.training.client

import java.security.MessageDigest

/**
 * The pure checks on a downloaded model, run in a fixed order. A model is content that arrives from
 * elsewhere, so nothing is trusted until every check has passed.
 *
 * Status: written without a compiler, not built, not tried on a phone.
 */
internal object ModelVerifier {
    const val DIGEST_HEX_LENGTH: Int = 64

    private const val HEX_DIGITS = "0123456789abcdef"

    /** The digest as compared and stored: lower case. */
    fun normalised(digest: String): String = digest.lowercase()

    /** SHA-256 of [bytes] as 64 lower case hex characters. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(DIGEST_HEX_LENGTH)
        for (b in digest) {
            val v = b.toInt() and 0xff
            out.append(HEX_DIGITS[v shr 4]).append(HEX_DIGITS[v and 0x0f])
        }
        return out.toString()
    }

    private fun isHexDigest(digest: String): Boolean =
        digest.length == DIGEST_HEX_LENGTH && digest.all { it in HEX_DIGITS }

    /**
     * Returns the first check [reply] fails, or null when it passes all five, in this order: the declared
     * size is in 1 to [TrainingLimits.MAX_MODEL_BYTES] (before anything else is looked at); the byte count
     * equals the declared size; the declared digest is exactly 64 hex characters (either case); the digest
     * of the bytes equals the declared digest; the sample count is positive.
     */
    fun check(reply: ModelReply): FetchRefusal? {
        if (reply.sizeBytes !in 1L..TrainingLimits.MAX_MODEL_BYTES) return FetchRefusal.SIZE_OUT_OF_RANGE
        if (reply.bytes.size.toLong() != reply.sizeBytes) return FetchRefusal.SIZE_MISMATCH
        val declared = normalised(reply.sha256)
        if (!isHexDigest(declared)) return FetchRefusal.BAD_DIGEST_FORMAT
        if (sha256Hex(reply.bytes) != declared) return FetchRefusal.DIGEST_MISMATCH
        if (reply.samplesUsed <= 0) return FetchRefusal.BAD_SAMPLES_COUNT
        return null
    }
}
