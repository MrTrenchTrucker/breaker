package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.KdfParams

/**
 * The values of a complete-reset request as the body gave them. Only the members and their types
 * are checked here: no length, base64, range or KDF check. The printed text is fixed, so no code,
 * salt or verifier text can reach a message by string conversion.
 */
internal class CompleteResetBody(
    val username: String,
    val resetCode: String,
    val saltText: String,
    val kdfMemoryKib: Int,
    val kdfIterations: Int,
    val kdfParallelism: Int,
    val kdfVersion: Int,
    val verifierText: String,
) {
    override fun toString(): String = "CompleteResetBody"
}

/**
 * Reads a complete-reset request body. Returns null for anything that is not exactly one JSON object
 * with the six members username, reset_code, salt, kdf_params, kdf_version and auth_verifier, each
 * once, where kdf_params is an object with exactly the three integer members memory_kib, iterations
 * and parallelism, each once. A string member must be a JSON string and an integer member must pass
 * the strict integer rule, so a value outside the Int range is a shape fault like a fraction is.
 * Nothing may follow the object. It never throws.
 */
internal fun parseCompleteResetBody(text: String): CompleteResetBody? {
    return try {
        CompleteResetReader(text).readBody()
    } catch (_: Exception) {
        null
    }
}

private class CompleteResetReader(text: String) : StrictJsonCursor(text) {
    fun readBody(): CompleteResetBody {
        skipSpace()
        expect('{')
        var username: String? = null
        var resetCode: String? = null
        var saltText: String? = null
        var kdf: KdfParams? = null
        var kdfVersion: Int? = null
        var verifierText: String? = null
        while (true) {
            skipSpace()
            val name = readString()
            skipSpace()
            expect(':')
            skipSpace()
            when (name) {
                "username" -> username = once(username, readString())
                "reset_code" -> resetCode = once(resetCode, readString())
                "salt" -> saltText = once(saltText, readString())
                "kdf_params" -> kdf = once(kdf, readKdfParams())
                "kdf_version" -> kdfVersion = once(kdfVersion, readInteger())
                "auth_verifier" -> verifierText = once(verifierText, readString())
                else -> refuse()
            }
            skipSpace()
            val next = readChar()
            if (next == '}') break
            if (next != ',') refuse()
        }
        skipSpace()
        if (position != text.length) refuse()

        val params = kdf ?: refuse()
        return CompleteResetBody(
            username = username ?: refuse(),
            resetCode = resetCode ?: refuse(),
            saltText = saltText ?: refuse(),
            kdfMemoryKib = params.memoryKib,
            kdfIterations = params.iterations,
            kdfParallelism = params.parallelism,
            kdfVersion = kdfVersion ?: refuse(),
            verifierText = verifierText ?: refuse(),
        )
    }

    // The nested object: exactly memory_kib, iterations and parallelism, each once, each an integer.
    private fun readKdfParams(): KdfParams {
        expect('{')
        var memoryKib: Int? = null
        var iterations: Int? = null
        var parallelism: Int? = null
        while (true) {
            skipSpace()
            val name = readString()
            skipSpace()
            expect(':')
            skipSpace()
            when (name) {
                "memory_kib" -> memoryKib = once(memoryKib, readInteger())
                "iterations" -> iterations = once(iterations, readInteger())
                "parallelism" -> parallelism = once(parallelism, readInteger())
                else -> refuse()
            }
            skipSpace()
            val next = readChar()
            if (next == '}') break
            if (next != ',') refuse()
        }
        return KdfParams(memoryKib ?: refuse(), iterations ?: refuse(), parallelism ?: refuse())
    }
}
