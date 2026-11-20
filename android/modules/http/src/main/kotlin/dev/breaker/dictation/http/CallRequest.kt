package dev.breaker.dictation.http

/**
 * One request as the module sends it. A GET or DELETE has no body; a POST or PUT has a body or none.
 *
 * The headers and the body can carry a key or a session, so [toString] prints the method only.
 */
class CallRequest(
    val method: CallMethod,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray?,
    val limits: CallLimits,
) {
    init {
        require(method == CallMethod.POST || method == CallMethod.PUT || body == null) { "a GET or DELETE request has no body" }
    }

    override fun toString(): String = "CallRequest(method=$method)"
}
