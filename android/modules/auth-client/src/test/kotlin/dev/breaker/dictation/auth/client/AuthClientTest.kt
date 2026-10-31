package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import dev.breaker.shared.api.LoginRequest
import dev.breaker.shared.api.RegisterRequest
import dev.breaker.shared.api.SaltRequest

/** The sixteen-bytes salt the tests use: 16 x 'A' -> base64 "AAAAAAAAAAAAAAAAAAAAAA==". */
private const val SIXTEEN_BYTE_SALT = "AAAAAAAAAAAAAAAAAAAAAA=="

/**
 * The auth-client suite pins: exact wire encoding of every request and its field order, each
 * mapped response field, and the KDF floor guard's at-boundary and just-past values. Each test
 * asserts one property directly so a field-name, order or escaping drift fails loudly rather than
 * silently. The tests run anywhere JUnit runs; they are not a build step of this module here.
 */
class AuthClientTest {

    // ---- request serialisation: exact wire fields (wrapped in outer braces), schema order, snake_case ----

    @Test fun registerRequestSerialisesExactly() {
        val body = RequestEncoder.encodeRegister(
            RegisterRequest("ada", SIXTEEN_BYTE_SALT, dev.breaker.shared.api.KdfParams(65536, 3, 1), 1, "verifier-value")
        )
        assertEquals(
            "{\"username\":\"ada\",\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"kdf_params\":{\"memory_kib\":65536,\"iterations\":3,\"parallelism\":1},\"kdf_version\":1,\"auth_verifier\":\"verifier-value\"}",
            String(body)
        )
    }

    @Test fun saltRequestSerialisesExactly() {
        val body = RequestEncoder.encodeSalt(SaltRequest("ada"))
        assertEquals("{\"username\":\"ada\"}", String(body))
    }

    @Test fun loginRequestSerialisesExactly() {
        val body = RequestEncoder.encodeLogin(LoginRequest("ada", "verifier-value"))
        assertEquals("{\"username\":\"ada\",\"auth_verifier\":\"verifier-value\"}", String(body))
    }

    // ---- response parsing ----

    @Test fun registerResponseParsesFields() {
        val r = AuthDecoder.parseRegister("""{"username":"ada","role":"user"}""")
        assertEquals("ada", r.username)
        assertEquals("user", r.role)
    }

    @Test fun loginAllNullAllowed() {
        val r = AuthDecoder.parseLogin(
            """{"token":"abc","role":"user","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":0,"prev_wrapped_dek":null,"reset_at":null,"rekeyed_at":null}"""
        )
        assertEquals("abc", r.token)
        assertEquals("user", r.role)
        assertNull(r.wrappedDek)
        assertNull(r.wrappedBoxPrivkey)
        assertNull(r.boxPubkey)
        assertEquals(0, r.keyVersion)
        assertNull(r.prevWrappedDek)
        assertNull(r.resetAt)
        assertNull(r.rekeyedAt)
    }

    @Test fun loginMixedNullRefused() {
        val bad = """{"token":"abc","role":"user","wrapped_dek":"dek","wrapped_box_privkey":null,"box_pubkey":null,"key_version":1}"""
        val ex = assertThrows(AuthDecodeException::class.java) { AuthDecoder.parseLogin(bad) }
        assertEquals("NULL_KEYS", ex.family)
    }

    @Test fun loginMissingRequiredKeyRefused() {
        val bad = """{"role":"user","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":1}"""
        val ex = assertThrows(AuthDecodeException::class.java) { AuthDecoder.parseLogin(bad) }
        assertEquals("MISSING_KEY", ex.family)
    }

    @Test fun loginWrongTypeRefused() {
        val bad = """{"token":5,"role":"user","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":1}"""
        val ex = assertThrows(AuthDecodeException::class.java) { AuthDecoder.parseLogin(bad) }
        assertEquals("TYPE", ex.family)
    }

    @Test fun unknownKeyIgnoredButMappedFieldsRight() {
        val body = """{"token":"abc","role":"user","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":1,"prev_wrapped_dek":null,"newer_unknown":"x","reset_at":null,"rekeyed_at":null}"""
        val r = AuthDecoder.parseLogin(body) // newer unknown key ignored, parse succeeds
        assertEquals("abc", r.token)
        assertEquals(1, r.keyVersion)
        assertNull(r.rekeyedAt)
    }

    @Test fun explicitNullOptionalStringsReadAsNull() {
        // Explicit JSON nulls and missing keys read back as null for optional strings.
        val body = """{"token":"abc","role":"user","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":1,"prev_wrapped_dek":null,"reset_at":"now"}"""
        val r = AuthDecoder.parseLogin(body)
        assertEquals(null, r.wrappedDek)
        assertEquals("now", r.resetAt)
    }

    // ---- guard-escape tests: a salt reply whose guard-breaking params must escape as KdfRefused ----

    @Test fun saltReplyGuardBreakEscapesAsKdfRefused() {
        // Iterations 99 is out of range; the refusal must be core KdfRefused with an exact reason,
        // provably NOT AuthDecodeException (proves the decode catch does not swallow KdfRefused).
        val body = """{"salt":"AAAAAAAAAAAAAAAAAAAAAA==","kdf_params":{"memory_kib":65536,"iterations":99,"parallelism":1},"kdf_version":1}"""
        val ex = assertThrows(KdfRefused::class.java) { AuthDecoder.parseSalt(body) }
        assertEquals(KdfRefusal.ITERATIONS_ABOVE_CEILING, ex.reason)
        }

    @Test fun memoryOverflowRefusesInRangeReason() {
        // 4295033344 must be refused with the memory RANGE reason; it must NOT truncate to 65536.
        val body = """{"salt":"AAAAAAAAAAAAAAAAAAAAAA==","kdf_params":{"memory_kib":4295033344,"iterations":3,"parallelism":1},"kdf_version":1}"""
        val ex = assertThrows(KdfRefused::class.java) { AuthDecoder.parseSalt(body) }
        assertEquals(KdfRefusal.MEMORY_ABOVE_CEILING, ex.reason)
        }

    @Test fun nonBase64SaltIsAuthDecodeException() {
        val bad = """{"salt":"not_base64!!","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1}"""
        val ex = assertThrows(AuthDecodeException::class.java) { AuthDecoder.parseSalt(bad) }
        assertEquals("BAD_BASE64", ex.family)
    }

    @Test fun shortSaltRefusesAsBadSaltLengthKdfRefused() {
        // "c2FsdA==" decodes to 4 bytes -> BAD_SALT_LENGTH, not AuthDecodeException.
        val bad = """{"salt":"c2FsdA==","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1}"""
        val ex = assertThrows(KdfRefused::class.java) { AuthDecoder.parseSalt(bad) }
        assertEquals(KdfRefusal.BAD_SALT_LENGTH, ex.reason)
        }

    @Test fun saltResponseParsesFields() {
        val r = AuthDecoder.parseSalt(
            """{"salt":"AAAAAAAAAAAAAAAAAAAAAA==","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1}"""
        )
        assertEquals(SIXTEEN_BYTE_SALT, r.salt)
        assertEquals(65536, r.kdfParams.memoryKib)
        assertEquals(3, r.kdfParams.iterations)
        assertEquals(1, r.kdfParams.parallelism)
        assertEquals(1, r.kdfVersion)
    }

    @Test fun registerRequestBadKdfVersionRefused() {
        // kdf_version 2 must be refused as UNSUPPORTED_VERSION before any wire byte is produced.
        val ex = assertThrows(
            KdfRefused::class.java
        ) { RequestEncoder.encodeRegister(RegisterRequest("ada", SIXTEEN_BYTE_SALT, dev.breaker.shared.api.KdfParams(65536, 3, 1), 2, "verifier-value")) }
        assertEquals(KdfRefusal.UNSUPPORTED_VERSION, ex.reason)
    }

    // ---- escape decoding ----

    @Test fun escapeUnicodeDecodesToA() {
        val r = JsonValue.parse("""{"k":"\u0041"}""")
        assertEquals(JsonValue.Obj(mapOf("k" to JsonValue.Str("A"))), r)
    }

    @Test fun badEscapeRefusedAsType() {
        // a \\u with fewer than four hex digits is a malformed escape -> TYPE
        val ex = assertThrows(AuthDecodeException::class.java) { JsonValue.parse("""{"k":"\u0"}""") }
        assertEquals("TYPE", ex.family)
        assertEquals("bad escape", ex.detail)
    }

    // ---- depth cap ----

    @Test fun level4NestedValueInUnknownKeyAccepted() {
        // the unknown key holds one object nesting ({"a":{"b":{}}}); the mapped fields parse cleanly
        val r = AuthDecoder.parseLogin(
            """{"token":"abc","role":"user","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":1,"meta":{"a":{"b":{}}}}"""
        )
        assertEquals("abc", r.token)
        assertEquals(1, r.keyVersion)
    }

    @Test fun level5ContainerRefusedAsType() {
        // two object nestings under an unknown key: the inner container would be at level 5.
        // The "c" value is an object {} because a scalar cannot trigger the container cap.
        val ex = assertThrows(AuthDecodeException::class.java) {
            AuthDecoder.parseLogin(
                """{"token":"abc","role":"user","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":1,"meta":{"a":{"b":{"c":{}}}}}"""
                )
        }
        assertEquals("TYPE", ex.family)
        assertTrue(ex.detail.startsWith("container would be at level 5"))
    }

    @Test fun body20000DeepRefusedAsType() {
        // build a valid 20001-object deep structure with a bounded loop, never a process or fork.
        // The prefix is fixed; each of the 20000 inner objects adds one more nesting level, so the
        // first level-5 container is reached and refused as TYPE (see parseValue depth semantics).
        val sb = StringBuilder()
        sb.append("{\"token\":\"abc\",\"role\":\"user\",\"wrapped_dek\":null,\"wrapped_box_privkey\":null,\"box_pubkey\":null,\"key_version\":1,\"meta\":")
        repeat(20000) { sb.append("{\"a\":") }
        sb.append("1")
        repeat(20000) { sb.append("}") }
        sb.append("}")
        val ex = assertThrows(AuthDecodeException::class.java) { AuthDecoder.parseLogin(sb.toString()) }
        assertEquals("TYPE", ex.family)
        assertTrue(ex.detail.startsWith("container would be at level 5"))
    }

    @Test fun unknownKeyHoldsArrayTrueFalseAndNestedObject() {
        val body = """{"token":"abc","role":"user","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":1,"meta":[1,true,false,{"a":1}]}"""
        val r = AuthDecoder.parseLogin(body)
        assertEquals("abc", r.token)
        assertEquals("user", r.role)
        assertEquals(1, r.keyVersion)
    }

    // ---- memory floor guard ----

    @Test fun negativeMemoryKibRefusesBelowFloor() {
        val body = """{"salt":"AAAAAAAAAAAAAAAAAAAAAA==","kdf_params":{"memory_kib":-1,"iterations":3,"parallelism":1},"kdf_version":1}"""
        val ex = assertThrows(KdfRefused::class.java) { AuthDecoder.parseSalt(body) }
        assertEquals(KdfRefusal.MEMORY_BELOW_FLOOR, ex.reason)
    }

    @Test fun memoryKibNeg65536RefusesBelowFloor() {
        val body = """{"salt":"AAAAAAAAAAAAAAAAAAAAAA==","kdf_params":{"memory_kib":-65536,"iterations":3,"parallelism":1},"kdf_version":1}"""
        val ex = assertThrows(KdfRefused::class.java) { AuthDecoder.parseSalt(body) }
        assertEquals(KdfRefusal.MEMORY_BELOW_FLOOR, ex.reason)
    }

    // ---- key version range ----

    @Test fun keyVersionOutsideIntRangeRefused() {
        val body = """{"token":"abc","role":"user","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":4294967297}"""
        val ex = assertThrows(AuthDecodeException::class.java) { AuthDecoder.parseLogin(body) }
        assertEquals("TYPE", ex.family)
    }

    @Test fun kdfVersionNegOneRefusesUnsupportedVersion() {
        val body = """{"salt":"AAAAAAAAAAAAAAAAAAAAAA==","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":-1}"""
        val ex = assertThrows(KdfRefused::class.java) { AuthDecoder.parseSalt(body) }
        assertEquals(KdfRefusal.UNSUPPORTED_VERSION, ex.reason)
    }

    @Test fun keyVersionNegFiveParses() {
        val r = AuthDecoder.parseLogin(
            """{"token":"abc","role":"user","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":-5}"""
        )
        assertEquals("abc", r.token)
        assertEquals(-5, r.keyVersion)
    }

    // ---- control characters ----

    @Test fun usernameControlCharsEncodeExactly() {
        val body = RequestEncoder.encodeSalt(SaltRequest("\u0022\u005C\u000A\u0001"))  // quote, backslash, newline, U+0001
        val expected = "{\"username\":\"" +
            "\\\"" +      // U+0022 -> escaped as backslash-quote (\")
            "\\\\" +    // U+005C -> escaped as double-backslash (\\)
            "\\n" +     // U+000A -> escaped as backslash-n (\n)
            "\\u0001" + // U+0001 -> escaped as lower-case \\u0001
            "\"}"
        assertEquals(expected, String(body))
    }

    // ---- duplicate key and trailing text ----

    @Test fun duplicateKeyRefused() {
        val ex = assertThrows(AuthDecodeException::class.java) { JsonValue.parse("""{"role":"user","a":1,"a":2}""") }
        assertEquals("TYPE", ex.family)
    }

    @Test fun trailingTextRefused() {
        val ex = assertThrows(AuthDecodeException::class.java) {
            JsonValue.parse("{\"token\":\"abc\",\"role\":\"user\"} trailing")
        }
        assertEquals("TYPE", ex.family)
    }

    @Test fun loneMinusValueRefusedAsType() {
        val ex = assertThrows(AuthDecodeException::class.java) { JsonValue.parse("""{"k":-}""") }
        assertEquals("TYPE", ex.family)
    }
}
