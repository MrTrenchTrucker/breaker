package dev.breaker.dictation.auth.client

/**
 * The network port of the account calls. The app supplies the implementation, in the same way
 * the sync module takes its uploader from the app. This module has no HTTP client of its own.
 *
 * One request gives one reply. The implementation throws java.io.IOException when the server
 * cannot be reached or the reply cannot be read. A non-2xx status is a reply, not an exception.
 * The implementation sends exactly the headers it is given, follows no redirect, adds no header
 * of its own and caps the size of the reply.
 */
fun interface AuthHttp {
    fun send(request: AuthHttpRequest): AuthHttpReply
}

/**
 * One request as the account calls build it. The headers carry the bearer token and the body
 * carries the auth verifier, so [toString] prints only the method.
 */
class AuthHttpRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray?,
) {
    override fun toString(): String = "AuthHttpRequest(method=$method)"
}

/**
 * One answer from the server: the status and the whole body. [toString] prints only the status,
 * because the body can carry a token or a record.
 */
class AuthHttpReply(val status: Int, val body: ByteArray) {
    override fun toString(): String = "AuthHttpReply(status=$status)"
}
