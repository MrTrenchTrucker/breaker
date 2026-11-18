package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The auth port, exercised against a fake the way the auth module will write it. */
class AuthServicePortTest {
    @Test
    fun `an auth adapter can be written against the port`() {
        var stored: AuthSession? = null
        val service = object : AuthService {
            override fun register(username: String, password: CharArray): AuthSession {
                // The first account registered is an admin; the server decides.
                stored = AuthSession("u-1", username, "tok", UserRole.ADMIN, setOf(TokenScope.ADMIN))
                return stored!!
            }

            override fun login(username: String, password: CharArray): AuthSession {
                stored = AuthSession("u-2", username, "tok-2", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
                return stored!!
            }

            override fun logout() {
                stored = null
            }

            override fun currentSession(): AuthSession? = stored
        }

        val first = service.register("first", "password".toCharArray())
        assertTrue(first.isAdmin)
        assertEquals(first, service.currentSession())

        val second = service.login("second", "password".toCharArray())
        assertEquals(UserRole.USER, second.role)
        assertTrue(!second.isAdmin)

        service.logout()
        assertNull(service.currentSession())
    }

    private val installCode = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
    private val username = "ada-lovelace"
    private val passwordText = "correct horse battery staple"

    /**
     * A port implementer that has NOT opted into the install-code overload, which is
     * what every existing implementer looks like after the port gains the overload.
     */
    private class PlainAuthService : AuthService {
        var registeredUsername: String? = null
        var registeredPassword: CharArray? = null
        var session: AuthSession? = null

        override fun register(username: String, password: CharArray): AuthSession {
            registeredUsername = username
            registeredPassword = password
            session = AuthSession("u-1", username, "tok", UserRole.ADMIN, setOf(TokenScope.ADMIN))
            return session!!
        }

        override fun login(username: String, password: CharArray): AuthSession =
            AuthSession("u-2", username, "tok-2", UserRole.USER, setOf(TokenScope.TRANSCRIBE))

        override fun logout() {
            session = null
        }

        override fun currentSession(): AuthSession? = session
    }

    @Test
    fun `a null install code delegates to the two argument register unchanged`() {
        val service = PlainAuthService()
        val password = passwordText.toCharArray()

        val session = service.register(username, password, null)

        assertEquals(username, service.registeredUsername)
        assertArrayEquals(passwordText.toCharArray(), service.registeredPassword)
        assertEquals(session, service.session)
        assertEquals(session, service.currentSession())
    }

    @Test
    fun `the null delegation hands the same password array to the two argument register`() {
        val service = PlainAuthService()
        val password = passwordText.toCharArray()

        service.register(username, password, null)

        assertSame(
            "the null delegation must pass the caller's CharArray itself, never a copy — " +
                "the service clears the array it is given, so a copy leaves the caller's " +
                "password in memory",
            password,
            service.registeredPassword,
        )
    }

    @Test
    fun `a non-null install code is refused loudly by an implementer that has not opted in`() {
        val service = PlainAuthService()

        assertThrows(UnsupportedOperationException::class.java) {
            service.register(username, passwordText.toCharArray(), installCode)
        }
        assertNull("a refused registration must not leave a session behind", service.currentSession())
    }

    @Test
    fun `the refusal carries the fixed sentence and none of the credentials`() {
        val service = PlainAuthService()

        val thrown = assertThrows(UnsupportedOperationException::class.java) {
            service.register(username, passwordText.toCharArray(), installCode)
        }

        assertEquals(
            "Registration with an install code is not supported by this AuthService implementation.",
            thrown.message,
        )
        listOf("the install code" to installCode, "the username" to username, "the password" to passwordText)
            .forEach { (what, secret) ->
                assertTrue(
                    "the refusal message carried $what: ${thrown.message}",
                    !(thrown.message ?: "").contains(secret),
                )
            }
    }

    @Test
    fun `the install code appears in no string form message or exception text`() {
        val service = PlainAuthService()

        val thrown = assertThrows(UnsupportedOperationException::class.java) {
            service.register(username, passwordText.toCharArray(), installCode)
        }

        val printed = listOf(
            "the exception message" to (thrown.message ?: ""),
            "the exception toString" to thrown.toString(),
            "the service toString" to service.toString(),
        )
        printed.forEach { (where, text) ->
            assertTrue("$where carried the install code: $text", !text.contains(installCode))
            assertTrue("$where carried the username: $text", !text.contains(username))
            assertTrue("$where carried the password: $text", !text.contains(passwordText))
        }
    }

    @Test
    fun `the auth port makes no logging call`() {
        val port = File("src/main/kotlin/dev/breaker/dictation/core/port/AuthService.kt")
        assertTrue("expected the auth port at ${port.absolutePath}", port.isFile)

        // Core has no logger, so the pin is the absence of any logging call in the file,
        // in the same line-by-line source-scan style as FrameworkFreeTest.
        val forbidden = listOf(
            Regex("""java\.util\.logging"""),
            Regex("""\bLogger\b"""),
            Regex("""\bLog\."""),
            Regex("""\bprintln\b"""),
            Regex("""System\.out"""),
        )
        val offenders = mutableListOf<String>()
        port.forEachLine { line ->
            forbidden.forEach { pattern ->
                if (pattern.containsMatchIn(line)) offenders += line.trim()
            }
        }
        assertTrue(
            "core has no logger; the auth port must not log — remove these lines:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
