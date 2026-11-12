package dev.breaker.shared.api.sync

import java.util.Base64

/**
 * The three parts of a sealed record: the nonce, the ciphertext and the tag. The constructor stores
 * copies of the arrays it is given. The properties return the stored arrays themselves, not copies,
 * so a caller must not change them. The text form never shows any byte, only the sizes.
 */
class RecordFrameParts(nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray) {
    /** The stored nonce bytes (not a copy). */
    val nonce: ByteArray = nonce.copyOf()

    /** The stored ciphertext bytes (not a copy); may be empty. */
    val ciphertext: ByteArray = ciphertext.copyOf()

    /** The stored tag bytes (not a copy). */
    val tag: ByteArray = tag.copyOf()

    /** Never prints the bytes. */
    override fun toString(): String =
        "RecordFrameParts(nonce=" + this.nonce.size + ", ciphertext=" + this.ciphertext.size +
            ", tag=" + this.tag.size + ")"
}

/**
 * The frame of an encrypted record at key_version 1 or higher: the standard padded base64 of the
 * nonce (12 bytes), then the ciphertext, then the tag (16 bytes), with no line wrap, at least
 * 28 decoded bytes. It also builds the associated data that binds a record to its client id and
 * key version. This object only joins, splits and builds bytes; it holds no cryptography.
 */
object RecordFrame {
    /** The size of the nonce in bytes. */
    const val NONCE_BYTES = 12

    /** The size of the tag in bytes. */
    const val TAG_BYTES = 16

    /** The least number of decoded bytes of a frame: a nonce and a tag around an empty ciphertext. */
    const val MIN_FRAME_BYTES = 28

    private const val NEWLINE: Byte = 0x0A

    /**
     * The frame text for the three parts. Refuses a nonce that is not 12 bytes or a tag that is not
     * 16 bytes. The ciphertext may be empty.
     */
    fun join(nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray): String {
        if (nonce.size != NONCE_BYTES) refuse(SyncBodyFamily.FRAME)
        if (tag.size != TAG_BYTES) refuse(SyncBodyFamily.FRAME)
        return Base64.getEncoder().encodeToString(nonce + ciphertext + tag)
    }

    /**
     * The three parts of a frame text. The text is read by the strict base64 reader of the sync body
     * codec, which refuses every non-canonical form, so a text that is accepted is the exact
     * encoding of its bytes. Refuses fewer than 28 decoded bytes.
     */
    fun split(encrypted: String): RecordFrameParts {
        val bytes = SyncBodyCodec.decodeBase64Strict(encrypted)
        if (bytes.size < MIN_FRAME_BYTES) refuse(SyncBodyFamily.FRAME)
        val tagStart = bytes.size - TAG_BYTES
        return RecordFrameParts(
            bytes.copyOfRange(0, NONCE_BYTES),
            bytes.copyOfRange(NONCE_BYTES, tagStart),
            bytes.copyOfRange(tagStart, bytes.size),
        )
    }

    /**
     * The associated data of a record: the UTF-8 bytes of the client id, one newline byte, then the
     * decimal key version. Refuses a client id that is not a lowercase canonical UUID and a key
     * version below 1.
     */
    fun aad(clientId: String, keyVersion: Int): ByteArray {
        if (!SyncBodyCodec.isClientId(clientId)) refuse(SyncBodyFamily.CLIENT_ID)
        if (keyVersion < 1) refuse(SyncBodyFamily.KEY_VERSION)
        val version = keyVersion.toString().toByteArray(Charsets.UTF_8)
        return clientId.toByteArray(Charsets.UTF_8) + NEWLINE + version
    }
}
