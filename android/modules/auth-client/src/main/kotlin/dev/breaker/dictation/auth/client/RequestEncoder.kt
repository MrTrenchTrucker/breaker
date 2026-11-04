package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import dev.breaker.shared.api.LoginRequest
import dev.breaker.shared.api.RegisterRequest
import dev.breaker.shared.api.SaltRequest

/**
 * Hand-rolled wire mapping, SyncBodyCodec style: fixed key order, no whitespace, snake_case keys.
 * Requests serialise as a JSON OBJECT - each body is wrapped in outer { ... } - the schema's field
 * names and order; a drift in field name or order fails the matching test. No JSON library is used.
 */
internal object RequestEncoder {
    // Escapes a string for use inside wire JSON, matching the sync body writer's escape set so that
    // control characters and quotes never produce invalid JSON on the wire.
    private fun jsonStr(s: String): String {
        val b = StringBuilder()
        b.append('"')
        for (c in s) {
            when (c.code) {
                '"'.code -> b.append("\\\"")
                '\\'.code -> b.append("\\\\")
                0x08 -> b.append("\\b")
                0x0C -> b.append("\\f")
                0x0A -> b.append("\\n")
                0x0D -> b.append("\\r")
                0x09 -> b.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        val hex = c.code.toString(16).padStart(4, '0')
                        b.append("\\u").append(hex)
                    } else {
                        b.append(c)
                    }
                }
            }
        }
        b.append('"')
        return b.toString()
    }

    fun encodeRegister(req: RegisterRequest): ByteArray {
        val kdf = req.kdfParams
        val body = buildString {
            append("{")
            append("\"username\":")
            append(jsonStr(req.username))
            append(",\"salt\":")
            append(jsonStr(req.salt))
            append(",\"kdf_params\":{")
            append("\"memory_kib\":")
            append(kdf.memoryKib)
            append(",\"iterations\":")
            append(kdf.iterations)
            append(",\"parallelism\":")
            append(kdf.parallelism)
            append("},\"kdf_version\":")
            append(refuseInvalidVersion(req.kdfVersion))
            append(",\"auth_verifier\":")
            append(jsonStr(req.authVerifier))
            append("}")
        }
        return body.encodeToByteArray()
    }

    fun encodeSalt(req: SaltRequest): ByteArray {
        val body = buildString {
            append("{")
            append("\"username\":")
            append(jsonStr(req.username))
            append("}")
        }
        return body.encodeToByteArray()
    }

    fun encodeLogin(req: LoginRequest): ByteArray {
        val body = buildString {
            append("{")
            append("\"username\":")
            append(jsonStr(req.username))
            append(",\"auth_verifier\":")
            append(jsonStr(req.authVerifier))
            append("}")
        }
        return body.encodeToByteArray()
    }

    // Register carries kdf_version; a value other than 1 is refused before any wire byte.
    private fun refuseInvalidVersion(v: Int): String =
        if (v == KdfGuard.ALLOWED_VERSION) v.toString() else throw KdfRefused(KdfRefusal.UNSUPPORTED_VERSION)
}
