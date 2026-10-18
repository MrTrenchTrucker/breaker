package dev.breaker.server.syncapi.tokens

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class TokenSecret private constructor(private val text: String) {

    fun reveal(): String = text

    // The hash is taken over the 43 characters, not over the decoded bytes: the last
    // character carries two spare bits, so decoding first would let several different
    // strings log in as one token. Hashing the string means only the issued spelling
    // works.
    //
    // SHA-256 without a salt or stretching is enough here: the input is 256 random
    // bits, so there is nothing to guess offline.
    fun hash(): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))

    override fun toString(): String = "TokenSecret(redacted)"

    companion object {
        const val RANDOM_BYTES = 32
        const val TEXT_LENGTH = 43

        fun generate(random: SecureRandom): TokenSecret {
            val bytes = ByteArray(RANDOM_BYTES)
            random.nextBytes(bytes)
            return TokenSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }

        // No trimming and no decoding: a presented token is either exactly the issued
        // shape or it is not a token. Checked character by character so no regex
        // end-of-line rule can let a trailing newline through.
        fun parse(presented: String): TokenSecret? {
            if (presented.length != TEXT_LENGTH) {
                return null
            }
            for (character in presented) {
                if (!isAlphabetCharacter(character)) {
                    return null
                }
            }
            return TokenSecret(presented)
        }

        private fun isAlphabetCharacter(character: Char): Boolean =
            character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character == '_' || character == '-'
    }
}
