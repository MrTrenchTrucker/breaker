package dev.breaker.dictation.auth.client

/** Why an account call failed. Each reason has one fixed sentence, and no sentence carries any input. */
enum class AuthFailureReason {
    BAD_CREDENTIALS,
    NO_SUCH_ACCOUNT,
    USERNAME_TAKEN,
    WEAK_PASSWORD,
    SERVER_UNREACHABLE,
    SERVER_ERROR,
    UNREADABLE_REPLY,
    BAD_SERVER_ADDRESS
}

/**
 * A refused or failed account call. The message is the fixed sentence for its reason, and no
 * cause is attached. A key-derivation refusal is never wrapped in this type; it passes through
 * unchanged.
 */
class AuthFailure(val reason: AuthFailureReason) : RuntimeException(sentenceFor(reason))

private fun sentenceFor(reason: AuthFailureReason): String = when (reason) {
    AuthFailureReason.BAD_CREDENTIALS -> "The username or password was not accepted."
    AuthFailureReason.NO_SUCH_ACCOUNT -> "No account with that username exists."
    AuthFailureReason.USERNAME_TAKEN -> "That username is already taken."
    AuthFailureReason.WEAK_PASSWORD -> "The password is too short."
    AuthFailureReason.SERVER_UNREACHABLE -> "The server could not be reached."
    AuthFailureReason.SERVER_ERROR -> "The server could not complete the request."
    AuthFailureReason.UNREADABLE_REPLY -> "The server's reply could not be read."
    AuthFailureReason.BAD_SERVER_ADDRESS -> "The server address must start with https://."
}
