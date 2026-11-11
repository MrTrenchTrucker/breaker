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
    var onLogout: () -> Unit = {}

    override fun register(username: String, password: CharArray): AuthSession =
        throw UnsupportedOperationException("register is not used by the sign-out tests")

    override fun login(username: String, password: CharArray): AuthSession =
        throw UnsupportedOperationException("login is not used by the sign-out tests")

    override fun logout() {
        logoutCalls += 1
        onLogout()
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

/** The sign-out answer: push pending deletes first, then logout off the main thread, then setLive(false) and the redraw on the main thread. */
class SignOutHandlerTest {
    private val auth = RecordingAuthService()
    private val state = SessionLiveState(true)
    private val background = HeldBackground()
    private val main = HeldMain()
    private val events: MutableList<String> = mutableListOf()

    private fun handler(
        clearAll: () -> Unit = { },
        pushForSignout: () -> Unit = { },
    ): SignOutHandler = SignOutHandler(
        authService = auth,
        sessionLive = state,
        background = background,
        main = main,
        refresh = { events.add("refresh sees live=" + state.liveNow()) },
        clearAll = clearAll,
        pushForSignout = pushForSignout,
    )

    @Test
    fun `the action hands the push, the logout and nothing else to the background`() {
        var pushed = false
        handler(
            clearAll = { },
            pushForSignout = { pushed = true },
        ).run {
            action()()
            assertEquals("app: sign out: one block is handed to the background", 1, background.blocks.size)
            assertEquals("app: sign out: the push has not run on the calling thread", false, pushed)
            assertEquals("app: sign out: the logout has not run on the calling thread", 0, auth.logoutCalls)
            assertEquals("app: sign out: nothing is posted to the main thread yet", 0, main.blocks.size)
            assertTrue("app: sign out: the session is still live until the main post runs", state.liveNow())
        }
    }

    @Test
    fun `pushForSignout runs before the logout`() {
        val order = mutableListOf<String>()
        handler(
            clearAll = { order.add("clear") },
            pushForSignout = { order.add("push") },
        ).run {
            action()()
            background.blocks.single()()
            assertEquals("app: sign out: push ran before clear (logout sits between them in the source)", listOf("push", "clear"), order)
            assertEquals("app: sign out: the logout ran once", 1, auth.logoutCalls)
            assertEquals("app: sign out: nothing is posted to the main thread yet", 1, main.blocks.size)
        }
    }

    @Test
    fun `a push that throws still logs out once and never blocks sign-out`() {
        handler(
            clearAll = { },
            pushForSignout = { throw IllegalStateException("the push failed") },
        ).run {
            action()()
            background.blocks.single()()
            assertEquals("app: sign out: the failing push did not block logout", 1, auth.logoutCalls)
            assertEquals("app: sign out: one block is posted to the main thread", 1, main.blocks.size)
            main.blocks.single()()
            assertFalse("app: sign out: live is false even though the push threw", state.liveNow())
            assertEquals("app: sign out: the redraw still runs", listOf("refresh sees live=false"), events)
        }
    }

    @Test
    fun `a logout that throws still ends in live false and a redraw and the throw does not escape`() {
        auth.throwOnLogout = true
        handler().run {
            action()()
            background.blocks.single()()
            assertEquals("app: sign out: the failing logout was called once", 1, auth.logoutCalls)
            assertEquals("app: sign out: the main block is still posted", 1, main.blocks.size)
            main.blocks.single()()
            assertFalse("app: sign out: live is false even though the logout threw", state.liveNow())
            assertEquals("app: sign out: the redraw still runs", listOf("refresh sees live=false"), events)
        }
    }

    @Test
    fun `the main block sets live false and only then redraws`() {
        handler().run {
            action()()
            background.blocks.single()()
            main.blocks.single()()
            assertFalse("app: sign out: the session is not live after the main block", state.liveNow())
            assertEquals(
                "app: sign out: the redraw runs after setLive(false)",
                listOf("refresh sees live=false"),
                events,
            )
        }
    }

    @Test
    fun `the action can be used twice and each use pushes and logs out by itself`() {
        val order = mutableListOf<String>()
        val action = handler(
            clearAll = { order.add("clear") },
            pushForSignout = { order.add("push") },
        ).action()
        action()
        action()
        assertEquals("app: sign out: each use handed one block to the background", 2, background.blocks.size)
        background.blocks.forEach { it() }
        assertEquals("app: sign out: each use logged out", 2, auth.logoutCalls)
        assertEquals("app: sign out: each use pushed before its logout", listOf("push", "clear", "push", "clear"), order)
        assertEquals("app: sign out: each use posted to the main thread", 2, main.blocks.size)
    }

    @Test
    fun `the background block clears the queue and tombstones alongside the logout`() {
        var cleared = false
        handler(clearAll = { cleared = true }).run {
            action()()
            background.blocks.single()()
            assertTrue("app: sign out: the bulk clear ran", cleared)
            assertEquals("app: sign out: the logout ran once", 1, auth.logoutCalls)
        }
    }

    @Test
    fun `the bulk clear still runs when logout throws`() {
        auth.throwOnLogout = true
        var cleared = false
        handler(clearAll = { cleared = true }).run {
            action()()
            background.blocks.single()()
            assertTrue("app: sign out: the bulk clear ran even though logout threw", cleared)
            assertEquals("app: sign out: the failing logout was called once", 1, auth.logoutCalls)
        }
    }

    @Test
    fun `the main block still runs after logout and the bulk clear`() {
        var cleared = false
        handler(clearAll = { cleared = true }).run {
            action()()
            background.blocks.single()()
            main.blocks.single()()
            assertFalse("app: sign out: the session is not live after the main block", state.liveNow())
            assertEquals(
                "app: sign out: the redraw runs after setLive(false)",
                listOf("refresh sees live=false"),
                events,
            )
            assertTrue("app: sign out: the bulk clear ran before the redraw", cleared)
        }
    }

    @Test
    fun `the order is push, then logout, then the bulk clear`() {
        val order = mutableListOf<String>()
        auth.onLogout = { order.add("logout") }
        try {
            handler(
                clearAll = { order.add("clear") },
                pushForSignout = { order.add("push") },
            ).run {
                action()()
                background.blocks.single()()
                assertEquals("app: sign out: push, then logout, then clear",
                    listOf("push", "logout", "clear"), order)
            }
        } finally {
            auth.onLogout = {}
        }
    }

    @Test
    fun `a clear that throws is swallowed logout still runs and the redraw still happens`() {
        var cleared = false
        handler(
            clearAll = { cleared = true; throw IllegalStateException("the clear failed") },
            pushForSignout = { },
        ).run {
            val threw = try {
                action()()
                background.blocks.single()()
                false
            } catch (e: Exception) {
                true
            }
            assertFalse("app: sign out: nothing escapes a failing clear", threw)
            assertTrue("app: sign out: the clear ran", cleared)
            assertEquals("app: sign out: the logout still ran once", 1, auth.logoutCalls)
            assertEquals("app: sign out: one block is posted to the main thread", 1, main.blocks.size)
            main.blocks.single()()
            assertFalse("app: sign out: live is false even though the clear threw", state.liveNow())
            assertEquals("app: sign out: the redraw still runs", listOf("refresh sees live=false"), events)
        }
    }
}
