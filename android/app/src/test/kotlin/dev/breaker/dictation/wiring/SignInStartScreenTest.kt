package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.port.SignInStarter
import dev.breaker.dictation.core.usecase.SignInHolder
import dev.breaker.dictation.ui.SignInStartControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIRST_CHALLENGE = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"

private fun attemptOf(typedText: String, challenge: String, marker: Int): SignInAttempt =
    SignInAttempt(
        typedText.toByteArray(Charsets.US_ASCII),
        SignInPrivateKey(ByteArray(32) { (marker + 2).toByte() }),
        ByteArray(32) { marker.toByte() },
        challenge,
    )

private fun firstAttempt(): SignInAttempt = attemptOf("A".repeat(43), FIRST_CHALLENGE, 1)

/** Hands out the given attempts in order, so each start gets a real attempt. */
private class ScreenScriptedStarter(private val attempts: List<SignInAttempt>) : SignInStarter {
    private var calls: Int = 0

    override fun begin(): SignInAttempt {
        val made = attempts[calls]
        calls++
        return made
    }
}

/** Records every control call as a short name, in the order it was made. */
private class RecordingControl : SignInStartControl {
    val calls: MutableList<String> = mutableListOf()

    override fun idle() {
        calls.add("idle")
    }

    override fun opening() {
        calls.add("opening")
    }

    override fun waiting() {
        calls.add("waiting")
    }

    override fun refused(noServer: Boolean) {
        calls.add("refused($noServer)")
    }

    override fun returned(finished: Boolean) {
        calls.add("returned($finished)")
    }
}

private fun screenFor(
    control: RecordingControl,
    holder: SignInHolder,
    server: () -> String,
    browserReply: Boolean,
    opened: MutableList<String>,
): SignInStartScreen =
    SignInStartScreen(SignInStartWiring(holder), control, { url -> opened.add(url); browserReply }, server)

internal class SignInStartScreenTest {

    @Test
    fun `a tap with a good server opens the browser and then shows waiting`() {
        val attempt = firstAttempt()
        val holder = SignInHolder(ScreenScriptedStarter(listOf(attempt)))
        val control = RecordingControl()
        val opened = mutableListOf<String>()
        screenFor(control, holder, { "https://h.example" }, true, opened).onTap()
        assertEquals("wiring: a good tap must show opening then waiting", listOf("opening", "waiting"), control.calls)
        assertEquals("wiring: a good tap must open exactly one browser page", 1, opened.size)
        assertTrue("wiring: the browser page must carry the challenge", opened[0].contains("#challenge=" + FIRST_CHALLENGE + "&app_key="))
        assertTrue("wiring: the holder must keep the attempt while the browser is open", holder.hasAttempt())
    }

    @Test
    fun `a tap when the browser cannot open shows refused as bad address and keeps no attempt`() {
        val attempt = firstAttempt()
        val holder = SignInHolder(ScreenScriptedStarter(listOf(attempt)))
        val control = RecordingControl()
        val opened = mutableListOf<String>()
        screenFor(control, holder, { "https://h.example" }, false, opened).onTap()
        assertEquals("wiring: a browser that cannot open must show refused as bad address", listOf("opening", "refused(false)"), control.calls)
        assertFalse("wiring: a browser that cannot open must not show waiting", control.calls.contains("waiting"))
        assertFalse("wiring: a browser that cannot open must empty the holder", holder.hasAttempt())
        assertTrue("wiring: a browser that cannot open must wipe the attempt", attempt.isWiped)
    }

    @Test
    fun `a tap with no server shows refused as no server and opens nothing`() {
        val holder = SignInHolder(ScreenScriptedStarter(listOf(firstAttempt())))
        val control = RecordingControl()
        val opened = mutableListOf<String>()
        screenFor(control, holder, { "   " }, true, opened).onTap()
        assertEquals("wiring: a blank server must show refused as no server", listOf("refused(true)"), control.calls)
        assertEquals("wiring: a blank server must not open a browser", 0, opened.size)
        assertFalse("wiring: a blank server must leave the holder empty", holder.hasAttempt())
    }

    @Test
    fun `a tap with a bad address shows refused as bad address and opens nothing`() {
        val attempt = firstAttempt()
        val holder = SignInHolder(ScreenScriptedStarter(listOf(attempt)))
        val control = RecordingControl()
        val opened = mutableListOf<String>()
        screenFor(control, holder, { "ftp://h.example" }, true, opened).onTap()
        assertEquals("wiring: a bad address must show refused as bad address", listOf("refused(false)"), control.calls)
        assertEquals("wiring: a bad address must not open a browser", 0, opened.size)
        assertTrue("wiring: a bad address must wipe its attempt", attempt.isWiped)
    }

    @Test
    fun `the server text is read again at every tap`() {
        var server = "   "
        val attempt = firstAttempt()
        val holder = SignInHolder(ScreenScriptedStarter(listOf(attempt)))
        val control = RecordingControl()
        val opened = mutableListOf<String>()
        val screen = screenFor(control, holder, { server }, true, opened)
        screen.onTap()
        server = "https://h.example"
        screen.onTap()
        assertEquals(
            "wiring: the second tap must read the new server text",
            listOf("refused(true)", "opening", "waiting"),
            control.calls,
        )
        assertEquals("wiring: only the second tap must open a browser", 1, opened.size)
    }

    @Test
    fun `resume with NOT_FINISHED shows returned not finished and with TAKEN shows returned finished`() {
        val control = RecordingControl()
        val screen = screenFor(control, SignInHolder(ScreenScriptedStarter(emptyList())), { "https://h.example" }, true, mutableListOf())
        screen.onResume(SignInReturnState.NOT_FINISHED)
        screen.onResume(SignInReturnState.TAKEN)
        assertEquals("wiring: resume must show the two returned states in order", listOf("returned(false)", "returned(true)"), control.calls)
    }

    @Test
    fun `resume with IGNORED or NO_ATTEMPT or nothing calls no control`() {
        val control = RecordingControl()
        val screen = screenFor(control, SignInHolder(ScreenScriptedStarter(emptyList())), { "https://h.example" }, true, mutableListOf())
        screen.onResume(SignInReturnState.IGNORED)
        screen.onResume(SignInReturnState.NO_ATTEMPT)
        screen.onResume(null)
        assertEquals("wiring: resume with IGNORED, NO_ATTEMPT or null must change nothing", emptyList<String>(), control.calls)
    }
}
