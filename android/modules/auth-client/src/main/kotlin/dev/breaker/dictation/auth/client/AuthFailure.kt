package dev.breaker.dictation.auth.client

/** Why an account call failed. Each reason has one fixed sentence, and no sentence carries any input. */
enum class AuthFailureReason {
    BAD_CREDENTIALS,
    BAD_USERNAME,
    NO_SUCH_ACCOUNT,
    USERNAME_TAKEN,
    WEAK_PASSWORD,
    SERVER_UNREACHABLE,
    SERVER_ERROR,
    UNREADABLE_REPLY,
    BAD_SERVER_ADDRESS,
    RATE_LIMITED,
}

/**
 * A refused or failed account call. The message is the fixed sentence for its reason, and no
 * sentence carries any input. [retryAfterSeconds] is the Retry-After value the server asked the
 * client to honor, in seconds, or null when the server did not ask for a wait. It is never inside
 * [message]. A key-derivation refusal is never wrapped in this type; it passes through unchanged.
 */
class AuthFailure(
    val reason: AuthFailureReason,
    val retryAfterSeconds: Int? = null,
) : RuntimeException(sentenceFor(reason))

private fun sentenceFor(reason: AuthFailureReason): String = when (reason) {
    AuthFailureReason.BAD_CREDENTIALS -> "The username or password was not accepted."
    AuthFailureReason.BAD_USERNAME -> "A username may use only letters, digits, dots, hyphens and underscores, up to 64 characters."
    AuthFailureReason.NO_SUCH_ACCOUNT -> "No account with that username exists."
    AuthFailureReason.USERNAME_TAKEN -> "That username is already taken."
    AuthFailureReason.WEAK_PASSWORD -> "The password is too short."
    AuthFailureReason.SERVER_UNREACHABLE -> "The server could not be reached."
    AuthFailureReason.SERVER_ERROR -> "The server could not complete the request."
    AuthFailureReason.UNREADABLE_REPLY -> "The server's reply could not be read."
    AuthFailureReason.BAD_SERVER_ADDRESS -> "The server address must start with http:// or https://."
    AuthFailureReason.RATE_LIMITED -> "Too many requests. Try again later."
}
