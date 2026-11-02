package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.accounts.Role

internal sealed class StartResult {
    // Logs and failure messages must never carry the code: toString names no value.
    class Started(val code: String) : StartResult() {
        override fun toString(): String = "Started(redacted)"
    }

    object NoSuchAccount : StartResult()
}

internal sealed class RedeemResult {
    // The session token, the package and the account data leave only through the fields.
    class Redeemed(
        val sessionToken: String,
        val role: Role,
        val keyVersion: Int,
        val sealedPackage: ByteArray,
        val username: String,
        val accountId: Long,
    ) : RedeemResult() {
        override fun toString(): String = "Redeemed(redacted)"
    }

    // One object for every failure, so no caller can tell the causes apart.
    object Refused : RedeemResult()
}
