package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import dev.breaker.shared.api.KdfParams
import dev.breaker.shared.api.LoginResponse
import dev.breaker.shared.api.RegisterResponse
import dev.breaker.shared.api.SaltResponse

/**
 * Response parsing + the ADR-006 guard, applied wherever the client would derive against
 * parameters handed back. Unknown keys are ignored; every required key must be present and of
 * the right type or the parse refuses with a distinguishable family.
 */
internal object AuthDecoder {
    /** Register response: {username, role}. No KDF involved here. */
    fun parseRegister(body: String): RegisterResponse {
        val obj = obj(body)
        return RegisterResponse(
            username = obj.str("username"),
            role = obj.str("role"),
        )
    }

    /** Salt response carries KDF params; the guard applies before anything is derived. */
    fun parseSalt(body: String): SaltResponse {
        val obj = obj(body)
        // Decode the salt first, before anything else builds: a non-base64 string is an
        // AuthDecodeException; a decoded length other than 16 bytes refuses with BAD_SALT_LENGTH.
        val saltBytes = try {
            java.util.Base64.getDecoder().decode(obj.str("salt"))
        } catch (e: IllegalArgumentException) {
            throw AuthDecodeException("BAD_BASE64", "salt is not valid base64")
        }
        if (saltBytes.size != 16) throw KdfRefused(KdfRefusal.BAD_SALT_LENGTH)

        // The wire shape nests kdf_params under the salt object.
        val p = obj.obj("kdf_params")
        val memoryKib = p.long("memory_kib")
        val iterations = p.long("iterations")
        val parallelism = p.long("parallelism")
        val version = obj.long("kdf_version")

        // The guard runs over the Long wire values and throws KdfRefused with a RANGE reason. That
        // must escape as-is (not be re-wrapped) so it runs before the try that builds the shared class.
        KdfGuard.check(memoryKib, iterations, parallelism, version)

        return try {
            SaltResponse(
                salt = obj.str("salt"),
                kdfParams = KdfParams(memoryKib.toInt(), iterations.toInt(), parallelism.toInt()),
                kdfVersion = version.toInt(),
            )
        } catch (e: KdfRefused) {
            throw e // never swallow a guard refusal inside the construction catch
        } catch (e: IllegalArgumentException) {
            throw AuthDecodeException("CONSTRUCT", "could not build shared response")
        }
    }

    /** Login response: token, role, the three wrapped keys (null together or all present), then the versioned fields. */
    fun parseLogin(body: String): LoginResponse {
        val obj = obj(body)
        val wrappedDek = obj.strOrNull("wrapped_dek")
        val wrappedBoxPrivkey = obj.strOrNull("wrapped_box_privkey")
        val boxPubkey = obj.strOrNull("box_pubkey")
        requireMixedNull(wrappedDek, wrappedBoxPrivkey, boxPubkey)
        return LoginResponse(
            token = obj.str("token"),
            role = obj.str("role"),
            wrappedDek = wrappedDek,
            wrappedBoxPrivkey = wrappedBoxPrivkey,
            boxPubkey = boxPubkey,
            keyVersion = parseKeyVersion(obj.long("key_version")),
            prevWrappedDek = obj.strOrNull("prev_wrapped_dek"),
            resetAt = obj.strOrNull("reset_at"),
            rekeyedAt = obj.strOrNull("rekeyed_at"),
        )
    }

    private fun requireMixedNull(a: String?, b: String?, c: String?) {
        val present = listOfNotNull(a, b, c).count { it != null }
        if (present != 0 && present != 3) throw AuthDecodeException("NULL_KEYS", "wrapped keys must be null together or all present")
    }

    /** Wire Long to Int: refuse any value outside the signed-Int range as TYPE before truncating. */
    private fun parseKeyVersion(wire: Long): Int =
        if (wire in Int.MIN_VALUE..Int.MAX_VALUE) wire.toInt()
        else throw AuthDecodeException("TYPE", "key_version $wire out of Int range")

    private fun obj(body: String): ObjReader {
        val value = JsonValue.parse(body)
        return when (value) {
            is JsonValue.Obj -> ObjReader(value.map)
            else -> throw AuthDecodeException("TYPE", "expected a JSON object body")
        }
    }
}

/** A read-only view over the parsed map; refuses missing keys with a named family. */
private class ObjReader(private val map: Map<String, JsonValue>) {
    fun obj(key: String): ObjReader {
        val v = map[key] ?: throw AuthDecodeException("MISSING_KEY", "required object '$key' missing")
        return if (v is JsonValue.Obj) ObjReader(v.map) else throw AuthDecodeException("TYPE", "key '$key' not an object")
    }

    fun str(key: String): String {
        val v = map[key] ?: throw AuthDecodeException("MISSING_KEY", "required key '$key' missing")
        return if (v is JsonValue.Str) v.s else throw AuthDecodeException("TYPE", "key '$key' not a string")
    }

    fun long(key: String): Long {
        val v = map[key] ?: throw AuthDecodeException("MISSING_KEY", "required key '$key' missing")
        return if (v is JsonValue.Num) v.l else throw AuthDecodeException("TYPE", "key '$key' not an integer")
    }

    fun strOrNull(key: String): String? {
        val v = map[key] ?: return null // newer absent key -> ignored; old phone must not break
        return when (v) {
            is JsonValue.Str -> v.s
            JsonValue.Null -> null // explicit JSON null for an optional string is a valid absent value
            else -> throw AuthDecodeException("TYPE", "key '$key' not a string")
        }
    }

}
