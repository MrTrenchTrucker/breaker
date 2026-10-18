package dev.breaker.server.syncapi.accounts

internal sealed class RegisterResult {
    data class Registered(val accountId: Long, val role: Role) : RegisterResult()

    object UsernameTaken : RegisterResult()

    object KdfOutOfBounds : RegisterResult()

    /** [field] is "username", "salt" or "kdf_version". */
    data class InvalidField(val field: String) : RegisterResult()
}

internal sealed class SaltLookup {
    class Found(salt: ByteArray, val kdf: KdfParams, val kdfVersion: Int) : SaltLookup() {
        private val saltBytes: ByteArray = salt.copyOf()

        // A copy in and a copy out: callers cannot change what the result reports.
        val salt: ByteArray
            get() = saltBytes.copyOf()
    }

    object NoSuchAccount : SaltLookup()
}

internal sealed class VerifyResult {
    data class Verified(val accountId: Long, val role: Role) : VerifyResult()

    object Rejected : VerifyResult()
}
