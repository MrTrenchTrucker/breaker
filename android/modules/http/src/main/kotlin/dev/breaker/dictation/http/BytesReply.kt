package dev.breaker.dictation.http

/**
 * The whole reply of the send call: the status, the body read under the reply cap, and the reply
 * headers as one (name, value) pair per value. The status line is not a header and is left out.
 */
class BytesReply(
    val status: Int,
    val body: ByteArray,
    val headers: List<Pair<String, String>>,
) {
    /** A reply that carries no headers. */
    constructor(status: Int, body: ByteArray) : this(status, body, emptyList())

    override fun toString(): String = "BytesReply(status=$status)"
}
