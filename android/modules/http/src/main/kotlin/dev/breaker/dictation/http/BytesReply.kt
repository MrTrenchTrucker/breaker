package dev.breaker.dictation.http

/** The whole reply of the send call: the status and the body, read under the reply cap. */
class BytesReply(
    val status: Int,
    val body: ByteArray,
) {
    override fun toString(): String = "BytesReply(status=$status)"
}
