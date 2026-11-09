package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.core.port.AuthService
import dev.breaker.dictation.ui.AuthControl
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The submit handler over the app's [AuthService]: on success it settles the session-live state to live and posts
 * submitted(register); on any failure it settles it not live and still posts submitted(register) once, so nothing escapes
 * the background block. Each test drives it with its own fakes; every assertion is computed from one line of code below.
 */
class SignInSubmitHandlerTest {

    /** A fake [AuthService] that records what the handler passed at the call, returns a session on success, throws when [throwOn]. */
    private class RecordingAuth(private val throwOn: Boolean) : AuthService {
        @Volatile var recordedCall: CharArray? = null // the exact instance handed to register/login
        @Volatile var lastKind: String? = null // "register" or "login", whichever the handler called

        override fun register(username: String, password: CharArray): dev.breaker.dictation.core.model.AuthSession {
            recordedCall = password // remember the very instance passed in (identity)
            lastKind = "register"
            if (throwOn) throw IllegalStateException("auth refused")
            return auth_session(username)
        }

        override fun login(username: String, password: CharArray): dev.breaker.dictation.core.model.AuthSession {
            recordedCall = password
            lastKind = "login"
            if (throwOn) throw IllegalStateException("auth refused")
            return auth_session(username)
        }

        override fun logout() {}
        override fun currentSession(): dev.breaker.dictation.core.model.AuthSession? = null

        private fun auth_session(username: String) =
            dev.breaker.dictation.core.model.AuthSession(
                "id-1", username, "tok-1", UserRole.USER, setOf(dev.breaker.dictation.core.model.TokenScope.TRANSCRIBE),
            )
    }

    /** One slot that answers the control; records each submitted call and the last flag. */
    private class RecordingControl : AuthControl {
        @Volatile var count = 0
        @Volatile var lastFlag: Boolean? = null

        override fun refresh() {}
        override fun submitted(register: Boolean) {
            lastFlag = register
            count += 1
        }
    }

    /** Runs every posted block at once, recording how many times main.post fired. */
    private class InlineMainPost : MainPost {
        @Volatile var postRuns = 0

        override fun post(block: () -> Unit) {
            block()
            postRuns += 1
        }
    }

     /** One real single-thread executor as [Background]; records the thread that actually ran the submitted block. */
    private class RecordingBackground : Background {
        private val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "breaker-dictation").apply { isDaemon = true } }

        @Volatile var runOn: Thread? = null

        override fun submit(block: () -> Unit) {
            // Runs the submitted block on the one worker thread and only returns after it has finished.
            val done = CountDownLatch(1)
            ex.execute {
                runOn = Thread.currentThread()
                block()
                done.countDown()
            }
            check(done.await(5_000, TimeUnit.MILLISECONDS)) { "wiring: the submitted block did not finish in 5 s" }
        }
    }

    /** The handler with its fakes: the state the handler itself settles, the recording control, service and background. */
    private class Harness(
        val handler: SignInSubmitHandler,
        val control: RecordingControl,
        val auth: RecordingAuth,
        val bg: RecordingBackground,
        val state: SessionLiveState,
        val main: InlineMainPost,
    ) {
        companion object {
            fun build(throwOn: Boolean): Harness {
                val auth = RecordingAuth(throwOn)
                val control = RecordingControl()
                val bg = RecordingBackground()
                val state = SessionLiveState(false)
                val main = InlineMainPost()
                val handler = SignInSubmitHandler(auth, state, main, bg)
                return Harness(handler, control, auth, bg, state, main)
            }
        }
    }

    @Test
    fun `a successful sign in makes the session live and the notice goes`() {
        val h = Harness.build(throwOn = false) // login returns a session (no throw)

        h.handler.authControl = h.control
        assertEquals("state starts signed out", false, h.state.liveNow())
        // submit(login=false) runs off the worker's thread, settles the state live, posts submitted(false).
        h.handler.submit("ada@example.test", charArrayOf('p', 'a', 's'), false)

        assertEquals("success leaves the session live", true, h.state.liveNow())
        assertEquals("the control received submit exactly once with register=false", 1, h.control.count)
        assertEquals("the settle was posted on the main post exactly once", 1, h.main.postRuns)
        assertEquals("submitted flag is register=false on login", false, h.control.lastFlag)
        assertEquals("a login submit calls the service login", "login", h.auth.lastKind)
    }

    @Test
    fun `a failed sign in keeps the phone signed out`() {
        val h = Harness.build(throwOn = true) // login throws AuthFailure-like exception

        h.handler.authControl = h.control
        // A thrown failure settles the state not live and still posts submitted(false) exactly once.
        h.handler.submit("ada@example.test", charArrayOf('p', 'a', 's'), false)

        assertEquals("state stays signed out on a refused login", false, h.state.liveNow())
        assertEquals("the control received submit exactly once even though it threw", 1, h.control.count)
    }

    @Test
    fun `a successful register makes the session live too`() {
        val h = Harness.build(throwOn = false) // register returns a session (no throw)

        h.handler.authControl = h.control
        // submit(register=true) settles the state live and posts submitted(true).
        h.handler.submit("ada@example.test", charArrayOf('p', 'a', 's'), true)

        assertEquals("register leaves the session live", true, h.state.liveNow())
        assertEquals("the control received submitted exactly once with register=true", 1, h.control.count)
        assertEquals("submitted flag is register=true on register", true, h.control.lastFlag)
        assertEquals("a register submit calls the service register", "register", h.auth.lastKind)
    }

    @Test
    fun `the sign in submit hands the password to the service as a char array`() {
        val h = Harness.build(throwOn = false)
        // The handler passes the SAME instance straight to the service; the fake records that very array.
        val password = charArrayOf('p', 'a', 's')

        h.handler.authControl = h.control
        h.handler.submit("ada@example.test", password, false)

        assertEquals(
            "the array handed to the service is the same instance the handler was given",
            password,
            h.auth.recordedCall,
        )
    }

    @Test
    fun `the sign in submit zeroes the password on every path`() {
        val ok = Harness.build(throwOn = false) // success path
        val failing = Harness.build(throwOn = true) // throw path

        val okPassword = charArrayOf('p', 'a', 's')
        ok.handler.authControl = ok.control
        ok.handler.submit("ada@example.test", okPassword, false)
        assertEquals(
            "the password is all \\u0000 after the success path returns",
            true,
            okPassword.contentEquals(charArrayOf('\u0000', '\u0000', '\u0000')),
        )

        val failPassword = charArrayOf('p', 'a', 's')
        failing.handler.authControl = failing.control
        failing.handler.submit("ada@example.test", failPassword, false)
        assertEquals(
            "the password is all \\u0000 after the throw path returns",
            true,
            failPassword.contentEquals(charArrayOf('\u0000', '\u0000', '\u0000')),
        )
    }

    @Test
    fun `the sign in submit runs off the main thread`() {
        val h = Harness.build(throwOn = false)

        h.handler.authControl = h.control
        // The submitted block ran on the worker's thread, never the caller's.
        h.handler.submit("ada@example.test", charArrayOf('p', 'a', 's'), false)

        assertNotNull("the service call must go through the background", h.bg.runOn)
        assertNotEquals(
            "the service call must run off the caller's thread",
            Thread.currentThread(),
            h.bg.runOn,
        )
    }
}
