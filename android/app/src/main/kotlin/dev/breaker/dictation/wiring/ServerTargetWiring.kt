package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.stt.server.ServerTarget

/**
 * The address and the key of one server request, read together. A session stored for the saved
 * address gives that address (read now) and its bearer. No session, or a session stored for another
 * address (the call returns null), gives the saved address read now and no key, so the engine answers
 * that the key is not set and sends nothing. [addressed] is called exactly once for each call and
 * nothing is kept. Plain JVM-testable.
 */
internal fun serverTargetOf(addressed: () -> AddressedSession?, savedAddress: () -> String): ServerTarget {
    val pair: AddressedSession? = addressed()
    return if (pair != null) {
        ServerTarget(pair.base, pair.session.token)
    } else {
        ServerTarget(savedAddress(), null)
    }
}
