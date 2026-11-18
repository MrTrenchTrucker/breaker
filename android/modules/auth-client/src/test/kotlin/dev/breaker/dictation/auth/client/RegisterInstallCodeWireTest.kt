package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.breaker.shared.api.KdfParams
import dev.breaker.shared.api.RegisterRequest

/**
 * The install_code member of the register body: appended last, only when the request carries a
 * code, escaped by the same writer as every other member, and absent byte-for-byte otherwise.
 * The code is a credential, so the tests use a neutral 43-character URL-safe dummy and never a
 * real value. The tests run anywhere JUnit runs; they are not a build step of this module here.
 */
class RegisterInstallCodeWireTest {

    /** The register prefix every case shares: the five members the body always carries, in order. */
    private val prefix =
        "{\"username\":\"ada\",\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA==\"," +
            "\"kdf_params\":{\"memory_kib\":65536,\"iterations\":3,\"parallelism\":1}," +
            "\"kdf_version\":1,\"auth_verifier\":\"verifier-value\""

    /** The register body for [code]: a null code is the ordinary path, a blank code is still sent. */
    private fun registerBody(code: String?): String =
        String(
            RequestEncoder.encodeRegister(
                RegisterRequest(
                    "ada",
                    "AAAAAAAAAAAAAAAAAAAAAA==",
                    KdfParams(65536, 3, 1),
                    1,
                    "verifier-value",
                    code,
                ),
            ),
            Charsets.UTF_8,
        )

    @Test fun `a null install code leaves the body exactly as before`() {
        // The same exact bytes AuthClientTest.registerRequestSerialisesExactly pins; repeated
        // here so the optional-member rule and the ordinary body read side by side.
        assertEquals(
            prefix + "}",
            registerBody(null),
        )
        assertFalse("a null code must not add the member", registerBody(null).contains("install_code"))
    }

    @Test fun `an install code is appended as install_code after the auth_verifier`() {
        assertEquals(
            prefix + ",\"install_code\":\"" + NEUTRAL_CODE + "\"}",
            registerBody(NEUTRAL_CODE),
        )
        val members = listOf("username", "salt", "kdf_params", "kdf_version", "auth_verifier", "install_code")
        val body = registerBody(NEUTRAL_CODE)
        val order = members.map { body.indexOf("\"" + it + "\"") }
        assertEquals("member order", order.sorted(), order)
        assertTrue("a member is missing: $order", order.none { it < 0 })
    }

    @Test fun `the install code is escaped and never injected`() {
        // A quote and a backslash in the code must appear as the escaped wire text \" and \\,
        // never as a raw quote that would open a new JSON member.
        val hostile = "ab\"cd\\ef"
        val body = registerBody(hostile)
        assertEquals(
            prefix + ",\"install_code\":\"ab\\\"cd\\\\ef\"}",
            body,
        )
        assertFalse("the raw hostile code reached the body unescaped", body.contains(hostile))
        assertFalse(
            "the raw quote must not open a member: " + body,
            body.contains("\"install_code\":\"ab\""),
        )
    }

    @Test fun `an install code of the longest shape is sent whole`() {
        // 43 URL-safe characters: the shape of an install code, with no real value.
        val longest = "Z".repeat(43)
        assertEquals(prefix + ",\"install_code\":\"" + longest + "\"}", registerBody(longest))
    }

    @Test fun `a blank install code is sent as a present empty member`() {
        // The server decides whether a code is valid; the client does not validate it, so a blank
        // code is still a present member and must reach the wire rather than being dropped.
        assertEquals(prefix + ",\"install_code\":\"\"}", registerBody(""))
    }

    private companion object {
        /** 43 URL-safe characters: the shape of an install code, with no real value. */
        const val NEUTRAL_CODE = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
    }
}
