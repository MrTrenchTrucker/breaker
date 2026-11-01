package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CryptoRefused

/** The key length HSalsa20 takes, in bytes. */
private const val HSALSA20_KEY_BYTES = 32

/** The input length HSalsa20 takes, in bytes. */
private const val HSALSA20_INPUT_BYTES = 16

/** The length of the subkey HSalsa20 returns, in bytes. */
private const val HSALSA20_OUTPUT_BYTES = 32

/** The number of double rounds (a column round then a row round): 10 of them make 20 rounds. */
private const val HSALSA20_DOUBLE_ROUNDS = 10

/*
 * The four constants are the little-endian words of the ASCII text "expand 32-byte k".
 * Each word is below 2 to the power 31, so each is a plain Int literal and needs no
 * conversion. The underscore splits every word in two halves so that no run of eight digits
 * shows up.
 */
private const val SIGMA_0 = 0x6170_7865
private const val SIGMA_1 = 0x3320_646e
private const val SIGMA_2 = 0x7962_2d32
private const val SIGMA_3 = 0x6b20_6574

/** The state words that make up the output, in output order. */
private val HSALSA20_OUTPUT_WORDS = intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)

/** Reads four bytes at [offset] as one little-endian Int. */
private fun hsalsaReadLe(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

/** Writes [value] as four little-endian bytes at [offset]. */
private fun hsalsaWriteLe(value: Int, out: ByteArray, offset: Int) {
    out[offset] = value.toByte()
    out[offset + 1] = (value ushr 8).toByte()
    out[offset + 2] = (value ushr 16).toByte()
    out[offset + 3] = (value ushr 24).toByte()
}

/** One Salsa20 quarter round on the state words at the four positions given. */
private fun hsalsaQuarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
    x[b] = x[b] xor (x[a] + x[d]).rotateLeft(7)
    x[c] = x[c] xor (x[b] + x[a]).rotateLeft(9)
    x[d] = x[d] xor (x[c] + x[b]).rotateLeft(13)
    x[a] = x[a] xor (x[d] + x[c]).rotateLeft(18)
}

/**
 * HSalsa20: the 20-round Salsa20 core WITHOUT the final addition of the input state,
 * keeping only the words 0, 5, 10, 15, 6, 7, 8 and 9 of the result.
 *
 * Bouncy Castle has no standalone HSalsa20, and libsodium uses it to turn the X25519
 * shared secret into the key of a sealed box, so it is written out here. The state is
 * laid out as the Salsa20 specification has it: word 0, 5, 10 and 15 hold the four
 * constants, words 1-4 and 11-14 hold the key, and words 6-9 hold the 16-byte input.
 *
 * Throws [CryptoRefused] when [key] is not 32 bytes or [input16] is not 16 bytes. The
 * caller's arrays are left untouched, a new 32-byte array is returned every call, and the
 * working state is zeroed before this function returns.
 */
internal fun hsalsa20(key: ByteArray, input16: ByteArray): ByteArray {
    if (key.size != HSALSA20_KEY_BYTES || input16.size != HSALSA20_INPUT_BYTES) throw CryptoRefused()
    val x = IntArray(16)
    try {
        x[0] = SIGMA_0
        x[1] = hsalsaReadLe(key, 0)
        x[2] = hsalsaReadLe(key, 4)
        x[3] = hsalsaReadLe(key, 8)
        x[4] = hsalsaReadLe(key, 12)
        x[5] = SIGMA_1
        x[6] = hsalsaReadLe(input16, 0)
        x[7] = hsalsaReadLe(input16, 4)
        x[8] = hsalsaReadLe(input16, 8)
        x[9] = hsalsaReadLe(input16, 12)
        x[10] = SIGMA_2
        x[11] = hsalsaReadLe(key, 16)
        x[12] = hsalsaReadLe(key, 20)
        x[13] = hsalsaReadLe(key, 24)
        x[14] = hsalsaReadLe(key, 28)
        x[15] = SIGMA_3

        repeat(HSALSA20_DOUBLE_ROUNDS) {
            // Column round.
            hsalsaQuarterRound(x, 0, 4, 8, 12)
            hsalsaQuarterRound(x, 5, 9, 13, 1)
            hsalsaQuarterRound(x, 10, 14, 2, 6)
            hsalsaQuarterRound(x, 15, 3, 7, 11)
            // Row round.
            hsalsaQuarterRound(x, 0, 1, 2, 3)
            hsalsaQuarterRound(x, 5, 6, 7, 4)
            hsalsaQuarterRound(x, 10, 11, 8, 9)
            hsalsaQuarterRound(x, 15, 12, 13, 14)
        }

        val out = ByteArray(HSALSA20_OUTPUT_BYTES)
        for (i in HSALSA20_OUTPUT_WORDS.indices) {
            hsalsaWriteLe(x[HSALSA20_OUTPUT_WORDS[i]], out, 4 * i)
        }
        return out
    } finally {
        x.fill(0)
    }
}
