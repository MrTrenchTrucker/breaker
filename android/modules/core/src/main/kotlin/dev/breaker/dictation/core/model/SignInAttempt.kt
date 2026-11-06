package dev.breaker.dictation.core.model

/**
 * One sign-in in progress on the phone: the one-time secret text, the private key made for
 * this sign-in, the public key the website seals to, and the challenge the website is given.
 *
 * The secret text and the public key are copied in when the attempt is made, and every
 * read returns a new copy, so no caller can reach the internal arrays. [wipe] zeroes the
 * secret, the public key and the private key. The attempt is wiped when the sign-in ends
 * or is replaced, and nothing about it is stored anywhere else.
 *
 * Two attempts are equal only if they are the same object. The text of the attempt is
 * fixed and shows no value.
 */
class SignInAttempt(secretBytes: ByteArray, val privateKey: SignInPrivateKey, publicKey: ByteArray, val challenge: String) {
    private val secret: ByteArray
    private val publicCopy: ByteArray
    private var wiped = false

    init {
        require(secretBytes.size == SECRET_LENGTH && isCanonicalSecret(secretBytes)) { TEXT_REFUSED }
        require(publicKey.size == PUBLIC_KEY_BYTES) { PUBLIC_KEY_REFUSED }
        require(challenge.length == CHALLENGE_LENGTH && challenge.all { alphabetIndex(it) >= 0 }) { CHALLENGE_REFUSED }
        secret = secretBytes.copyOf()
        publicCopy = publicKey.copyOf()
    }

    /** A new copy of the secret text bytes. The caller owns it. */
    fun copySecretBytes(): ByteArray = secret.copyOf()

    /** A new copy of the public key bytes. The caller owns it. */
    fun copyPublicKey(): ByteArray = publicCopy.copyOf()

    /** Zero the secret, the public key and the private key. Safe to call more than once. */
    fun wipe() {
        secret.fill(0)
        publicCopy.fill(0)
        privateKey.wipe()
        wiped = true
    }

    /** True after [wipe] has run. */
    val isWiped: Boolean
        get() = wiped

    /** Never prints the secret, the key or the challenge. */
    override fun toString(): String = "SignInAttempt(redacted)"

    companion object {
        /** The length of the secret text in characters, which are also its bytes. */
        private const val SECRET_LENGTH = 43

        /** The length of the X25519 public key in bytes. */
        private const val PUBLIC_KEY_BYTES = 32

        /** The length of the challenge text in characters. */
        private const val CHALLENGE_LENGTH = 43

        private const val TEXT_REFUSED = "The sign-in secret text must be 43 URL-safe characters with a canonical last character"
        private const val PUBLIC_KEY_REFUSED = "The sign-in public key must be 32 bytes"
        private const val CHALLENGE_REFUSED = "The sign-in challenge must be 43 URL-safe characters"

        /** The position of a character in the base64url alphabet, or -1 when it is not in the alphabet. */
        private fun alphabetIndex(c: Char): Int = when (c) {
            in 'A'..'Z' -> c - 'A'
            in 'a'..'z' -> c - 'a' + 26
            in '0'..'9' -> c - '0' + 52
            '-' -> 62
            '_' -> 63
            else -> -1
        }

        /**
         * True when every byte is in the alphabet and the last one sits at a multiple of 4 in it,
         * so the two spare bits of its base64url encoding are zero.
         */
        private fun isCanonicalSecret(bytes: ByteArray): Boolean {
            for (b in bytes) {
                if (alphabetIndex((b.toInt() and 0xFF).toChar()) < 0) return false
            }
            return alphabetIndex((bytes[bytes.size - 1].toInt() and 0xFF).toChar()) % 4 == 0
        }
    }
}
