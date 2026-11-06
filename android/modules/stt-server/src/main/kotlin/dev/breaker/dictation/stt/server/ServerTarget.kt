package dev.breaker.dictation.stt.server

/**
 * The address and the key of ONE call, read together. The module owns no way to look at the key
 * except the engine's own header. This is a plain class: it has no equals, no hashCode, no copy and
 * no component, so nothing can compare or take apart a key.
 *
 * [base] is the server address. [key] is the API key, or null when none is set.
 */
class ServerTarget(val base: String, val key: String?) {

    /** Fixed text. It never prints [base] or [key]. */
    override fun toString(): String = "ServerTarget"
}

/**
 * Builds the provider for the two-provider form. The key provider is read ONLY when the base is
 * usable, as the engine did before: the address sentence came back before the key was read. For an
 * unusable base the key is null, and the engine returns the address sentence first, so that null is
 * never looked at.
 */
internal fun guardedTarget(serverUrl: () -> String, apiKey: () -> String?): () -> ServerTarget = {
    val base = serverUrl()
    ServerTarget(base, if (ServerEndpoint.addressProblem(base) == null) apiKey() else null)
}
