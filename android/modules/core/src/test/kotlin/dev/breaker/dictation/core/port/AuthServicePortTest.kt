package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The auth port, exercised against a fake the way the auth module will write it. */
class AuthServicePortTest {
    @Test
    fun `an auth adapter can be written against the port`() {
        var stored: AuthSession? = null
        val service = object : AuthService {
            override fun register(username: String, password: String): AuthSession {
                // The first account registered is an admin; the server decides.
                stored = AuthSession("u-1", username, "tok", UserRole.ADMIN, setOf(TokenScope.ADMIN))
                return stored!!
            }

            override fun login(username: String, password: String): AuthSession {
                stored = AuthSession("u-2", username, "tok-2", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
                return stored!!
            }

            override fun logout() {
                stored = null
            }

            override fun currentSession(): AuthSession? = stored
        }

        val first = service.register("first", "password")
        assertTrue(first.isAdmin)
        assertEquals(first, service.currentSession())

        val second = service.login("second", "password")
        assertEquals(UserRole.USER, second.role)
        assertTrue(!second.isAdmin)

        service.logout()
        assertNull(service.currentSession())
    }
}
