package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession

/**
 * The pair of the server address read now and the session stored for that same address. A caller
 * that needs the address and the bearer together takes both from one of these, so it never joins a
 * session to an address read at another moment. It is a plain class: it has no copy, equals or
 * component functions that could print or compare the bearer, and [toString] prints fixed text.
 */
class AddressedSession(val base: String, val session: AuthSession) {
    override fun toString(): String = "AddressedSession"
}
