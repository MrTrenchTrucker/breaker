package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthFailure
import dev.breaker.dictation.auth.client.AuthFailureReason

/**
 * The line the auth screen shows for a failed account call, or null when the failure has no line of
 * its own. Only a locked name has one, and the line is the failure's own fixed sentence.
 */
internal fun shownLineFor(failure: AuthFailure): String? = when (failure.reason) {
    AuthFailureReason.NAME_LOCKED -> failure.message
    else -> null
}
