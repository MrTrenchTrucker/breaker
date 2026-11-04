package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.port.HandoffReceipt
import dev.breaker.dictation.core.port.HandoffReceiver
import dev.breaker.dictation.core.port.SignInStarter
import dev.breaker.dictation.core.usecase.SignInHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

private const val FIRST_CHALLENGE = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
private const val SECOND_CHALLENGE = "h0MHWX2Mnc_cEYThDk9bpQ7-lifZvd4YEyafEtXRNd4"
private val CODE_ONE: String = "Q".repeat(43)
private val CODE_TWO: String = "R".repeat(43)

private fun attemptOf(typedText: String, challenge: String, marker: Int): SignInAttempt =
    SignInAttempt(
        typedText.toByteArray(Charsets.US_ASCII),
        SignInPrivateKey(ByteArray(32) { (marker + 2).toByte() }),
        ByteArray(32) { marker.toByte() },
        challenge,
    )

private fun firstAttempt(): SignInAttempt = attemptOf("A".repeat(43), FIRST_CHALLENGE, 1)

private fun secondAttempt(): SignInAttempt = attemptOf("Y".repeat(43), SECOND_CHALLENGE, 2)

private fun goodLink(code: String): String = "breaker://handoff?code=" + code

private fun appKeyText(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1.toByte() })

private fun allZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

/** Hands out the given attempts in order, so each start gets a real attempt. */
private class ScriptedStarter(private val attempts: List<SignInAttempt>) : SignInStarter {
    var calls: Int = 0
        private set

    override fun begin(): SignInAttempt {
        val made = attempts[calls]
        calls++
        return made
    }
}

/** Counts the calls and, as the receiver that owns the attempt, wipes it before it replies. */
private class RecordingReceiver(private val reply: HandoffReceipt) : HandoffReceiver {
    var calls: Int = 0
        private set

    override fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt {
        calls++
        attempt.wipe()
        return reply
    }
}

private class ThrowingReceiver : HandoffReceiver {
    override fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt =
        throw IllegalStateException("the receiver failed")
}

internal class SignInStartWiringTest {

    @Test
    fun `a blank server gives NoServer and drops the older attempt without making a new one`() {
        val first = firstAttempt()
        val starter = ScriptedStarter(listOf(first))
        val holder = SignInHolder(starter)
        val wiring = SignInStartWiring(holder)
        wiring.start("https://h.example")
        assertFalse("wiring: the older attempt must not be wiped before the blank start", first.isWiped)
        assertFalse("wiring: the older secret must be non zero before the blank start", allZero(first.copySecretBytes()))
        assertEquals("wiring: an empty server must give NoServer", SignInStartOutcome.NoServer, wiring.start(""))
        assertEquals("wiring: a blank server must give NoServer", SignInStartOutcome.NoServer, wiring.start("   "))
        assertEquals("wiring: a blank start must not make a new attempt", 1, starter.calls)
        assertFalse("wiring: a blank start must empty the holder", holder.hasAttempt())
        assertTrue("wiring: a blank start must wipe the older attempt", first.isWiped)
        assertTrue("wiring: the older secret must read zero after the blank start", allZero(first.copySecretBytes()))
        assertTrue("wiring: the older private key must read zero after the blank start", allZero(first.privateKey.copyBytes()))
    }

    @Test
    fun `a good server opens the exact link and the holder keeps the attempt`() {
        val attempt = firstAttempt()
        val holder = SignInHolder(ScriptedStarter(listOf(attempt)))
        val wiring = SignInStartWiring(holder)
        val outcome = wiring.start("https://h.example:8443/breaker")
        assertTrue("wiring: a good server must give an open outcome", outcome is SignInStartOutcome.Open)
        val url = (outcome as SignInStartOutcome.Open).url
        assertEquals(
            "wiring: the link must be the sign-in page with the challenge and the key in the fragment",
            "https://h.example:8443/breaker/signin.html#challenge=" + FIRST_CHALLENGE + "&app_key=" + appKeyText(),
            url,
        )
        assertTrue("wiring: the holder must keep the attempt after the link is made", holder.hasAttempt())
        assertFalse("wiring: making the link must not wipe the attempt", attempt.isWiped)
    }

    @Test
    fun `a plain http server with a trailing slash gives the page without a double slash`() {
        val wiring = SignInStartWiring(SignInHolder(ScriptedStarter(listOf(firstAttempt()))))
        val outcome = wiring.start("http://h.example:8080/") as SignInStartOutcome.Open
        assertEquals(
            "wiring: a trailing slash must not double the path",
            "http://h.example:8080/signin.html#challenge=" + FIRST_CHALLENGE + "&app_key=" + appKeyText(),
            outcome.url,
        )
    }

    @Test
    fun `a refused address gives BadAddress and wipes the attempt`() {
        val first = firstAttempt()
        val second = secondAttempt()
        val starter = ScriptedStarter(listOf(first, second))
        val holder = SignInHolder(starter)
        val wiring = SignInStartWiring(holder)
        assertFalse("wiring: the first secret must be non zero before the refusal", allZero(first.copySecretBytes()))
        assertEquals("wiring: a non http address must give BadAddress", SignInStartOutcome.BadAddress, wiring.start("ftp://h.example"))
        assertTrue("wiring: a refused address must wipe its attempt", first.isWiped)
        assertTrue("wiring: the refused secret must read zero", allZero(first.copySecretBytes()))
        assertFalse("wiring: a refused address must empty the holder", holder.hasAttempt())
        assertEquals("wiring: a query on the server must give BadAddress", SignInStartOutcome.BadAddress, wiring.start("http://h.example/?x=1"))
        assertTrue("wiring: the second refused attempt must be wiped", second.isWiped)
        assertFalse("wiring: the second refusal must empty the holder", holder.hasAttempt())
        assertEquals("wiring: both refused starts must have begun an attempt", 2, starter.calls)
    }

    @Test
    fun `a second start replaces the older attempt and wipes it`() {
        val first = firstAttempt()
        val second = secondAttempt()
        val holder = SignInHolder(ScriptedStarter(listOf(first, second)))
        val wiring = SignInStartWiring(holder)
        wiring.start("https://h.example")
        assertFalse("wiring: the first attempt must not be wiped by its own start", first.isWiped)
        val outcome = wiring.start("https://h.example") as SignInStartOutcome.Open
        assertTrue("wiring: the second start must wipe the first attempt", first.isWiped)
        assertTrue("wiring: the first secret must read zero after the second start", allZero(first.copySecretBytes()))
        assertFalse("wiring: the second attempt must be alive", second.isWiped)
        assertTrue("wiring: the link must carry the second challenge", outcome.url.contains("#challenge=" + SECOND_CHALLENGE + "&app_key="))
        assertFalse("wiring: the link must not carry the first challenge", outcome.url.contains(FIRST_CHALLENGE))
        assertTrue("wiring: the holder must hold the second attempt", holder.hasAttempt())
    }

    @Test
    fun `a good code with the default receiver gives NOT_FINISHED and empties the holder`() {
        val attempt = firstAttempt()
        val holder = SignInHolder(ScriptedStarter(listOf(attempt)))
        val wiring = SignInStartWiring(holder)
        wiring.start("https://h.example")
        assertFalse("wiring: the secret must be non zero before the return", allZero(attempt.copySecretBytes()))
        assertEquals("wiring: the default receiver must give NOT_FINISHED", SignInReturnState.NOT_FINISHED, wiring.returned(goodLink(CODE_ONE)))
        assertFalse("wiring: the return must empty the holder", holder.hasAttempt())
        assertTrue("wiring: the default receiver must wipe the attempt", attempt.isWiped)
        assertTrue("wiring: the wiped secret must read zero", allZero(attempt.copySecretBytes()))
    }

    @Test
    fun `every hostile return gives IGNORED and leaves the attempt alive`() {
        val attempt = firstAttempt()
        val receiver = RecordingReceiver(HandoffReceipt.TAKEN)
        val holder = SignInHolder(ScriptedStarter(listOf(attempt)))
        val wiring = SignInStartWiring(holder, receiver)
        wiring.start("https://h.example")
        val hostile: List<String?> = listOf(
            null,
            "",
            "Breaker://handoff?code=" + CODE_ONE,
            "breaker://handoff?code=" + "Q".repeat(42),
            "breaker://handoff?code=" + "Q".repeat(44),
            goodLink(CODE_ONE) + "&x=1",
            goodLink(CODE_ONE) + "#f",
            "https://h.example/?code=" + CODE_ONE,
            goodLink(CODE_ONE) + " ",
        )
        for (link in hostile) {
            assertEquals("wiring: a hostile return must give IGNORED", SignInReturnState.IGNORED, wiring.returned(link))
        }
        assertEquals("wiring: a hostile return must not call the receiver", 0, receiver.calls)
        assertTrue("wiring: the attempt must still be held after hostile returns", holder.hasAttempt())
        assertFalse("wiring: the attempt must not be wiped by hostile returns", attempt.isWiped)
        assertFalse("wiring: the private key must not be zeroed by hostile returns", allZero(attempt.privateKey.copyBytes()))
    }

    @Test
    fun `a good code with no attempt gives NO_ATTEMPT and calls no receiver`() {
        val receiver = RecordingReceiver(HandoffReceipt.TAKEN)
        val holder = SignInHolder(ScriptedStarter(listOf(firstAttempt())))
        val wiring = SignInStartWiring(holder, receiver)
        assertEquals("wiring: a code with no attempt must give NO_ATTEMPT", SignInReturnState.NO_ATTEMPT, wiring.returned(goodLink(CODE_ONE)))
        assertEquals("wiring: a second code with no attempt must give NO_ATTEMPT", SignInReturnState.NO_ATTEMPT, wiring.returned(goodLink(CODE_TWO)))
        assertEquals("wiring: no attempt must mean no receiver call", 0, receiver.calls)
        assertFalse("wiring: no attempt must leave the holder empty", holder.hasAttempt())
    }

    @Test
    fun `the receiver reply TAKEN gives TAKEN and NOT_FINISHED gives NOT_FINISHED`() {
        val taken = SignInStartWiring(SignInHolder(ScriptedStarter(listOf(firstAttempt()))), RecordingReceiver(HandoffReceipt.TAKEN))
        taken.start("https://h.example")
        assertEquals("wiring: a TAKEN receipt must give TAKEN", SignInReturnState.TAKEN, taken.returned(goodLink(CODE_ONE)))
        val unfinished = SignInStartWiring(SignInHolder(ScriptedStarter(listOf(secondAttempt()))), RecordingReceiver(HandoffReceipt.NOT_FINISHED))
        unfinished.start("https://h.example")
        assertEquals("wiring: a NOT_FINISHED receipt must give NOT_FINISHED", SignInReturnState.NOT_FINISHED, unfinished.returned(goodLink(CODE_TWO)))
    }

    @Test
    fun `a receiver that throws propagates out of returned and the attempt is wiped`() {
        val attempt = firstAttempt()
        val holder = SignInHolder(ScriptedStarter(listOf(attempt)))
        val wiring = SignInStartWiring(holder, ThrowingReceiver())
        wiring.start("https://h.example")
        try {
            wiring.returned(goodLink(CODE_ONE))
            fail("wiring: a throwing receiver must propagate out of returned")
        } catch (expected: IllegalStateException) {
            assertTrue("wiring: a throwing receiver must leave the attempt wiped", attempt.isWiped)
        }
        assertFalse("wiring: a throwing receiver must leave the holder empty", holder.hasAttempt())
    }

    @Test
    fun `cancel wipes the waiting attempt and empties the holder`() {
        val attempt = firstAttempt()
        val holder = SignInHolder(ScriptedStarter(listOf(attempt)))
        val wiring = SignInStartWiring(holder)
        wiring.start("https://h.example")
        assertFalse("wiring: the secret must be non zero before cancel", allZero(attempt.copySecretBytes()))
        wiring.cancel()
        assertTrue("wiring: cancel must wipe the attempt", attempt.isWiped)
        assertTrue("wiring: the cancelled secret must read zero", allZero(attempt.copySecretBytes()))
        assertFalse("wiring: cancel must empty the holder", holder.hasAttempt())
    }

    @Test
    fun `an open outcome prints a fixed text and no link`() {
        val text = SignInStartOutcome.Open("https://h.example/signin.html#challenge=" + FIRST_CHALLENGE + "&app_key=abc").toString()
        assertEquals("wiring: the open outcome must print a fixed text", "SignInStartOutcome.Open(redacted)", text)
    }
}
