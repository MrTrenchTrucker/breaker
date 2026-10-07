package dev.breaker.server.syncapi.accounts

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC_NAME = "HmacSHA256"

// Own implementation because the JDK's PBKDF2 factory takes a char array and cannot
// carry arbitrary bytes, and the verifier is raw bytes. One 32-byte block is enough:
// the output is exactly one SHA-256 width (RFC 8018, block index 1).
internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
    require(iterations >= 1) { "sync-api: pbkdf2 needs at least one iteration" }
    require(password.isNotEmpty()) { "sync-api: pbkdf2 needs a non-empty password" }

    val mac = Mac.getInstance(HMAC_NAME)
    mac.init(SecretKeySpec(password, HMAC_NAME))

    val first = ByteArray(salt.size + 4)
    System.arraycopy(salt, 0, first, 0, salt.size)
    first[first.size - 1] = 1

    // doFinal resets the Mac, so the one instance serves every iteration.
    var previous: ByteArray = mac.doFinal(first)
    val result: ByteArray = previous.copyOf()
    repeat(iterations - 1) {
        previous = mac.doFinal(previous)
        for (index in result.indices) {
            result[index] = (result[index].toInt() xor previous[index].toInt()).toByte()
        }
    }
    return result
}
