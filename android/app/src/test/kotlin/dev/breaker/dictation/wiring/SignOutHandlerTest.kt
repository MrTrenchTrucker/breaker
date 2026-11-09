package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.port.AuthService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An auth service that counts logout calls and can be told to throw from it. */
private class RecordingAuthService : AuthService {
    var logoutCalls: Int = 0
    var throwOnLogout: Boolean = false

    override fun register(username: String, password: CharArray): AuthSession =
        throw UnsupportedOperationException("register is not used by the sign-out tests")

    override fun login(username: String, password: CharArray): AuthSession =
        throw UnsupportedOperationException("login is not used by the sign-out tests")

    override fun logout() {
        logoutCalls += 1
        if (throwOnLogout) throw IllegalStateException("the logout failed")
    }

    override fun currentSession(): AuthSession? = null
}

/** A background that keeps the submitted blocks; the test runs them by hand, so nothing is timed. */
private class HeldBackground : Background {
    val blocks: MutableList<() -> Unit> = mutableListOf()

    override fun submit(block: () -> Unit) {
        blocks.add(block)
    }
}

/** A main post that keeps the posted blocks; the test runs them by hand, so nothing is timed. */
private class HeldMain : MainPost {
    val blocks: MutableList<() -> Unit> = mutableListOf()

    override fun post(block: () -> Unit) {
        blocks.add(block)
    }
}

/** The sign-out answer: logout off the main thread, then setLive(false) and the redraw on the main thread, in that order. */
class SignOutHandlerTest {
    private val auth = RecordingAuthService()
    private val state = SessionLiveState(true)
    private val background = HeldBackground()
    private val main = HeldMain()
    private val events: MutableList<String> = mutableListOf()

    private fun handler(): SignOutHandler = SignOutHandler(
        authService = auth,
        sessionLive = state,
        background = background,
        main = main,
        refresh = { events.add("refresh sees live=" + state.liveNow()) },
    )

    @Test
    fun `the action hands the logout to the background and runs nothing itself`() {
        handler().action()()
        assertEquals("app: sign out: one block is handed to the background", 1, background.blocks.size)
        assertEquals("app: sign out: the logout has not run on the calling thread", 0, auth.logoutCalls)
        assertEquals("app: sign out: nothing is posted to the main thread yet", 0, main.blocks.size)
        assertTrue("app: sign out: the session is still live until the main post runs", state.liveNow())
    }

    @Test
    fun `the background block logs out once and posts one block to the main thread`() {
        handler().action()()
        background.blocks.single()()
        assertEquals("app: sign out: the logout ran once", 1, auth.logoutCalls)
        assertEquals("app: sign out: one block is posted to the main thread", 1, main.blocks.size)
        assertTrue("app: sign out: the flag has not changed before the main block runs", state.liveNow())
        assertEquals("app: sign out: the redraw has not run before the main block runs", emptyList<String>(), events)
    }

    @Test
    fun `the main block sets live false and only then redraws`() {
        handler().action()()
        background.blocks.single()()
        main.blocks.single()()
        assertFalse("app: sign out: the session is not live after the main block", state.liveNow())
        assertEquals(
            "app: sign out: the redraw runs after setLive(false)",
            listOf("refresh sees live=false"),
            events,
        )
    }

    @Test
    fun `a logout that throws still ends in live false and a redraw and the throw does not escape`() {
        auth.throwOnLogout = true
        handler().action()()
        background.blocks.single()()
        assertEquals("app: sign out: the failing logout was called once", 1, auth.logoutCalls)
        assertEquals("app: sign out: the main block is still posted", 1, main.blocks.size)
        main.blocks.single()()
        assertFalse("app: sign out: live is false even though the logout threw", state.liveNow())
        assertEquals("app: sign out: the redraw still runs", listOf("refresh sees live=false"), events)
    }

    @Test
    fun `the action can be used twice and each use logs out by itself`() {
        val action = handler().action()
        action()
        action()
        assertEquals("app: sign out: each use hands one block to the background", 2, background.blocks.size)
        background.blocks.forEach { it() }
        assertEquals("app: sign out: each use logged out", 2, auth.logoutCalls)
        assertEquals("app: sign out: each use posted to the main thread", 2, main.blocks.size)
    }
}
