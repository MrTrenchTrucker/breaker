package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SaltLengthTest {

    private val kdfParams = KdfParams(memoryKib = 65536, iterations = 3, parallelism = 1)

    // 16 bytes: "YWJjZGVmZ2hpamtsbW5vcA==" (22 chars + ==)
    private val salt16 = "YWJjZGVmZ2hpamtsbW5vcA=="
    // 15 bytes: "YWJjZGVmZ2hpamtsbW5v" (20 chars, no padding)
    private val salt15 = "YWJjZGVmZ2hpamtsbW5v"
    // 32 bytes: "YWJjZGVmZ2hpamtsbW5vcGFiY2RlZmdoaWprbG1ub3A=" (43 chars + =)
    private val salt32 = "YWJjZGVmZ2hpamtsbW5vcGFiY2RlZmdoaWprbG1ub3A="

    @Test
    fun `RegisterRequest accepts 16-byte salt`() {
        val req = RegisterRequest(
            username = "alice",
            salt = salt16,
            kdfParams = kdfParams,
            kdfVersion = 1,
            authVerifier = salt32
        )
        assertEquals(salt16, req.salt)
    }

    @Test
    fun `RegisterRequest refuses 15-byte salt`() {
        try {
            RegisterRequest(
                username = "alice",
                salt = salt15,
                kdfParams = kdfParams,
                kdfVersion = 1,
                authVerifier = salt32
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should name the salt length invariant, was: ${e.message}",
                e.message!!.contains("salt")
            )
        }
    }

    @Test
    fun `RegisterRequest refuses 32-byte salt`() {
        try {
            RegisterRequest(
                username = "alice",
                salt = salt32,
                kdfParams = kdfParams,
                kdfVersion = 1,
                authVerifier = salt32
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should name the salt length invariant, was: ${e.message}",
                e.message!!.contains("salt")
            )
        }
    }

    @Test
    fun `SaltResponse accepts 16-byte salt`() {
        val resp = SaltResponse(
            salt = salt16,
            kdfParams = kdfParams,
            kdfVersion = 1
        )
        assertEquals(salt16, resp.salt)
    }

    @Test
    fun `SaltResponse refuses 32-byte salt`() {
        try {
            SaltResponse(
                salt = salt32,
                kdfParams = kdfParams,
                kdfVersion = 1
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should name the salt length invariant, was: ${e.message}",
                e.message!!.contains("salt")
            )
        }
    }

    @Test
    fun `SaltResponse refuses 15-byte salt`() {
        try {
            SaltResponse(
                salt = salt15,
                kdfParams = kdfParams,
                kdfVersion = 1
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should name the salt length invariant, was: ${e.message}",
                e.message!!.contains("salt")
            )
        }
    }

    @Test
    fun `RegisterRequest verifier stays 32 bytes`() {
        val req = RegisterRequest(
            username = "alice",
            salt = salt16,
            kdfParams = kdfParams,
            kdfVersion = 1,
            authVerifier = salt32
        )
        assertEquals(salt32, req.authVerifier)
    }

    @Test
    fun `RegisterRequest refuses invalid base64 salt`() {
        try {
            RegisterRequest(
                username = "alice",
                salt = "not-valid-base64!!!",
                kdfParams = kdfParams,
                kdfVersion = 1,
                authVerifier = salt32
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // java.util.Base64.decode throws its own IllegalArgumentException
            // before the require() message exists, so we only assert the type
        }
    }
}
