package dev.breaker.dictation.core.model

/**
 * A sealed box of an agent-token result: a libsodium crypto_box_seal box that only the
 * account's box private key can open.
 *
 * It is not a secret by itself, but it is the only thing between a reader and the result,
 * so it is handled like one. The box lives only inside this object. The constructor copies
 * the array it is given and [copyBytes] returns a new copy on every call. Arrays you pass
 * in or get out are your own copies; changing them never changes the box.
 *
 * [size] is readable without making a copy. The code that opens the box checks a size cap
 * with it before any copy of the bytes exists, so an oversized box is refused without
 * duplicating it.
 *
 * It judges nothing except that it is not empty. What the bytes mean, how large they may
 * be and whether they open is decided by the code that opens it, in one place. There is no
 * size limit here.
 *
 * This is a plain class, not a data class. Two boxes are equal only if they are the same
 * object. To compare the contents, compare [copyBytes] results.
 */
class SealedAgentResult(bytes: ByteArray) {
    private val box: ByteArray

    init {
        require(bytes.isNotEmpty()) { "A sealed agent result cannot be empty" }
        box = bytes.copyOf()
    }

    /** The number of bytes in the box. Reading it makes no copy. */
    val size: Int
        get() = box.size

    /** A new copy of the box bytes. The caller owns it and should zero it when done. */
    fun copyBytes(): ByteArray = box.copyOf()

    /** Never prints the bytes, only how many there are. */
    override fun toString(): String = "SealedAgentResult($size bytes)"
}
