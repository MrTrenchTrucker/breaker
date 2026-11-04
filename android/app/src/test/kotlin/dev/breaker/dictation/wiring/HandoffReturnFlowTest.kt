package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.port.HandoffReceipt
import dev.breaker.dictation.core.port.HandoffReceiver
import dev.breaker.dictation.core.port.SignInStarter
import dev.breaker.dictation.core.usecase.SignInHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIRST_CHALLENGE = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
private const val SECOND_CHALLENGE = "h0MHWX2Mnc_cEYThDk9bpQ7-lifZvd4YEyafEtXRNd4"
private val CODE_ONE: String = "Q".repeat(43)
private val CODE_TWO: String = "R".repeat(43)
private const val VIEW = "android.intent.action.VIEW"
private const val MAIN = "android.intent.action.MAIN"

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

private fun allZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

/** Hands out the given attempts in order, so each start gets a real attempt. */
private class FlowScriptedStarter(private val attempts: List<SignInAttempt>) : SignInStarter {
    private var calls: Int = 0

    override fun begin(): SignInAttempt {
        val made = attempts[calls]
        calls++
        return made
    }
}

/** Counts the calls and, as the receiver that owns the attempt, wipes it before it replies. */
private class FlowRecordingReceiver(private val reply: HandoffReceipt) : HandoffReceiver {
    var calls: Int = 0
        private set

    override fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt {
        calls++
        attempt.wipe()
        return reply
    }
}

private class FlowThrowingReceiver : HandoffReceiver {
    override fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt =
        throw IllegalStateException("the receiver failed")
}

internal class HandoffReturnFlowTest {

    @Test
    fun `handle answers true and records NOT_FINISHED for a good code with the default receiver`() {
        val attempt = firstAttempt()
        val holder = SignInHolder(FlowScriptedStarter(listOf(attempt)))
        val store = SignInReturnStore()
        val wiring = SignInStartWiring(holder)
        val flow = HandoffReturnFlow(wiring, store)
        wiring.start("https://h.example")
        assertTrue("wiring: the return activity must finish after a good code", flow.handle(VIEW, goodLink(CODE_ONE)))
        assertEquals("wiring: the default receiver must be recorded as NOT_FINISHED", SignInReturnState.NOT_FINISHED, store.take())
        assertFalse("wiring: the holder must be empty after the return", holder.hasAttempt())
        assertTrue("wiring: the attempt must be wiped after the return", attempt.isWiped)
    }

    @Test
    fun `handle answers true and records TAKEN when the receiver takes the code`() {
        val holder = SignInHolder(FlowScriptedStarter(listOf(secondAttempt())))
        val store = SignInReturnStore()
        val wiring = SignInStartWiring(holder, FlowRecordingReceiver(HandoffReceipt.TAKEN))
        val flow = HandoffReturnFlow(wiring, store)
        wiring.start("https://h.example")
        assertTrue("wiring: the return activity must finish after a taken code", flow.handle(VIEW, goodLink(CODE_TWO)))
        assertEquals("wiring: a TAKEN receipt must be recorded as TAKEN", SignInReturnState.TAKEN, store.take())
    }

    @Test
    fun `handle answers true and records IGNORED for a hostile link and keeps the attempt`() {
        val attempt = firstAttempt()
        val holder = SignInHolder(FlowScriptedStarter(listOf(attempt)))
        val store = SignInReturnStore()
        val wiring = SignInStartWiring(holder)
        val flow = HandoffReturnFlow(wiring, store)
        wiring.start("https://h.example")
        assertTrue("wiring: a hostile link must still finish the return activity", flow.handle(VIEW, "breaker://handoff?code=" + "Q".repeat(42)))
        assertEquals("wiring: a hostile link must be recorded as IGNORED", SignInReturnState.IGNORED, store.take())
        assertTrue("wiring: a hostile link must leave the attempt held", holder.hasAttempt())
        assertFalse("wiring: a hostile link must not wipe the attempt", attempt.isWiped)
    }

    @Test
    fun `handle answers true and records NO_ATTEMPT for a code with no attempt`() {
        val receiver = FlowRecordingReceiver(HandoffReceipt.TAKEN)
        val store = SignInReturnStore()
        val flow = HandoffReturnFlow(SignInStartWiring(SignInHolder(FlowScriptedStarter(emptyList())), receiver), store)
        assertTrue("wiring: a code with no attempt must still finish the return activity", flow.handle(VIEW, goodLink(CODE_TWO)))
        assertEquals("wiring: a code with no attempt must be recorded as NO_ATTEMPT", SignInReturnState.NO_ATTEMPT, store.take())
        assertEquals("wiring: a code with no attempt must not call the receiver", 0, receiver.calls)
    }

    @Test
    fun `handle answers true and records nothing when the receiver throws`() {
        val attempt = firstAttempt()
        val store = SignInReturnStore()
        val wiring = SignInStartWiring(SignInHolder(FlowScriptedStarter(listOf(attempt))), FlowThrowingReceiver())
        val flow = HandoffReturnFlow(wiring, store)
        wiring.start("https://h.example")
        assertTrue("wiring: a throwing receiver must not stop the return activity from finishing", flow.handle(VIEW, goodLink(CODE_ONE)))
        assertNull("wiring: a throwing receiver must record nothing", store.take())
        assertTrue("wiring: a throwing receiver must leave the attempt wiped", attempt.isWiped)
        assertTrue("wiring: the wiped secret must read zero", allZero(attempt.copySecretBytes()))
    }

    @Test
    fun `handle answers true and records IGNORED for a null action another action or null data`() {
        val attempt = secondAttempt()
        val holder = SignInHolder(FlowScriptedStarter(listOf(attempt)))
        val store = SignInReturnStore()
        val wiring = SignInStartWiring(holder)
        val flow = HandoffReturnFlow(wiring, store)
        wiring.start("https://h.example")
        assertTrue("wiring: a null action must finish the return activity", flow.handle(null, goodLink(CODE_ONE)))
        assertEquals("wiring: a null action must be recorded as IGNORED", SignInReturnState.IGNORED, store.take())
        assertTrue("wiring: a main action must finish the return activity", flow.handle(MAIN, goodLink(CODE_ONE)))
        assertEquals("wiring: a main action must be recorded as IGNORED", SignInReturnState.IGNORED, store.take())
        assertTrue("wiring: null data must finish the return activity", flow.handle(VIEW, null))
        assertEquals("wiring: null data must be recorded as IGNORED", SignInReturnState.IGNORED, store.take())
        assertTrue("wiring: none of the refused inputs may wipe the attempt", holder.hasAttempt())
        assertFalse("wiring: none of the refused inputs may wipe the attempt object", attempt.isWiped)
    }
}
