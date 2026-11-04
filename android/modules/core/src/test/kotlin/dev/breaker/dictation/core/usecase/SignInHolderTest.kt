package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.port.DefaultHandoffReceiver
import dev.breaker.dictation.core.port.HandoffReceipt
import dev.breaker.dictation.core.port.HandoffReceiver
import dev.breaker.dictation.core.port.SignInStarter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The holder rule of the website sign-in: one attempt in memory, wiped when it is
 * replaced, abandoned, or when its receiver throws, and handed over exactly once.
 * Two attempts and two codes are used, so a state that coincides in a fresh holder fails.
 */
class SignInHolderTest {
    @Test
    fun `begin keeps the new attempt and returns it`() {
        val holder = SignInHolder(PairStarter())
        assertFalse("a new holder must start empty", holder.hasAttempt())
        val made = holder.begin()
        assertTrue("begin must keep the attempt it made", holder.hasAttempt())
        assertFalse("the returned attempt must not be wiped", made.isWiped)
        assertEquals("begin must return the first fixed attempt", FIRST_CHALLENGE, made.challenge)
    }

    @Test
    fun `a second begin wipes the first attempt and returns a different one`() {
        val starter = PairStarter()
        val holder = SignInHolder(starter)
        val first = holder.begin()
        assertFalse("the first attempt must hold its secret bytes before the second begin", allZero(first.copySecretBytes()))
        val second = holder.begin()
        assertEquals("the starter must be asked twice", 2, starter.calls)
        assertNotSame("the second begin returned the first attempt", first, second)
        assertTrue("the second begin did not wipe the first attempt", first.isWiped)
        assertTrue("the first attempt secret bytes are not zero after the second begin", allZero(first.copySecretBytes()))
        assertTrue("the first attempt private key is not zero after the second begin", allZero(first.privateKey.copyBytes()))
        assertFalse("the second attempt must not be wiped", second.isWiped)
        assertEquals("the holder must keep the second attempt", SECOND_CHALLENGE, second.challenge)
        assertTrue("the holder must still hold an attempt", holder.hasAttempt())
    }

    @Test
    fun `abandon wipes the attempt and empties the holder`() {
        val holder = SignInHolder(PairStarter())
        val made = holder.begin()
        assertFalse("the attempt must hold its secret bytes before abandon", allZero(made.copySecretBytes()))
        holder.abandon()
        assertFalse("abandon must empty the holder", holder.hasAttempt())
        assertTrue("abandon must wipe the attempt", made.isWiped)
        assertTrue("abandon must zero the secret bytes", allZero(made.copySecretBytes()))
        assertTrue("abandon must zero the private key", allZero(made.privateKey.copyBytes()))
    }

    @Test
    fun `abandon is safe when the holder is already empty`() {
        val holder = SignInHolder(PairStarter())
        holder.abandon()
        assertFalse("abandon on an empty holder must leave it empty", holder.hasAttempt())
        val made = holder.begin()
        holder.abandon()
        holder.abandon()
        assertTrue("the attempt must stay wiped after repeated abandon", made.isWiped)
        assertFalse("the holder must stay empty after repeated abandon", holder.hasAttempt())
    }

    @Test
    fun `every hostile link is ignored and leaves the attempt alive and unwiped`() {
        val holder = SignInHolder(PairStarter())
        val receiver = RecordingReceiver()
        val made = holder.begin()
        hostileLinks().forEachIndexed { index, link ->
            val outcome = holder.receive(link, receiver)
            assertSame("hostile link number $index must give Ignored", HandoffOutcome.Ignored, outcome)
        }
        assertEquals("a hostile link must not reach the receiver", 0, receiver.codes.size)
        assertTrue("a hostile link must leave the attempt in the holder", holder.hasAttempt())
        assertFalse("a hostile link must not wipe the attempt", made.isWiped)
        assertFalse("a hostile link must not zero the secret bytes", allZero(made.copySecretBytes()))
    }

    @Test
    fun `a well formed code with no attempt gives NoAttempt and calls no receiver`() {
        val holder = SignInHolder(PairStarter())
        val receiver = RecordingReceiver()
        val outcome = holder.receive(goodLink(codeOne()), receiver)
        assertSame("a code with no attempt must give NoAttempt", HandoffOutcome.NoAttempt, outcome)
        assertEquals("the receiver must not be called without an attempt", 0, receiver.codes.size)
        assertFalse("the holder must stay empty", holder.hasAttempt())
    }

    @Test
    fun `a well formed code hands the waiting attempt to the receiver once and empties the holder`() {
        val holder = SignInHolder(PairStarter())
        val made = holder.begin()
        val receiver = RecordingReceiver(holderHasAttempt = { holder.hasAttempt() })
        val outcome = holder.receive(goodLink(codeOne()), receiver)
        assertTrue("a well formed code with an attempt must give Handed", outcome is HandoffOutcome.Handed)
        assertEquals("the receiver must be called exactly once", 1, receiver.codes.size)
        assertEquals("the receiver must get the code from the link", codeOne(), receiver.codes[0])
        assertSame("the receiver must get the same attempt object", made, receiver.attempts[0])
        assertFalse("the holder must be empty while the receiver runs", receiver.holderHadAttemptDuringCall)
        assertFalse("the holder must be empty after the hand over", holder.hasAttempt())
    }

    @Test
    fun `a second code after a hand over gives NoAttempt`() {
        val holder = SignInHolder(PairStarter())
        val receiver = RecordingReceiver()
        holder.begin()
        holder.receive(goodLink(codeOne()), receiver)
        val again = holder.receive(goodLink(codeTwo()), receiver)
        assertSame("a second code after the hand over must give NoAttempt", HandoffOutcome.NoAttempt, again)
        assertEquals("the receiver must not be called a second time", 1, receiver.codes.size)
        assertFalse("the holder must stay empty after the hand over", holder.hasAttempt())
    }

    @Test
    fun `the TAKEN and NOT_FINISHED receipts are passed through`() {
        for (receipt in HandoffReceipt.values()) {
            val holder = SignInHolder(PairStarter())
            holder.begin()
            val outcome = holder.receive(goodLink(codeOne()), RecordingReceiver(receipt))
            assertEquals("the $receipt receipt must come back in Handed", receipt, receiptOf(outcome))
        }
    }

    @Test
    fun `a receiver that throws gets the attempt wiped and the same exception rethrown`() {
        val holder = SignInHolder(PairStarter())
        val made = holder.begin()
        assertFalse("the attempt must hold its secret bytes before the hand over", allZero(made.copySecretBytes()))
        val failure = IllegalStateException("the receiver failed")
        val receiver = ThrowingReceiver(failure)
        try {
            holder.receive(goodLink(codeOne()), receiver)
            fail("the receiver exception was swallowed")
        } catch (caught: IllegalStateException) {
            assertSame("the same exception must be rethrown", failure, caught)
        }
        assertEquals("the receiver must be called once", 1, receiver.calls)
        assertTrue("a throwing receiver must leave the attempt wiped", made.isWiped)
        assertTrue("a throwing receiver must zero the secret bytes", allZero(made.copySecretBytes()))
        assertTrue("a throwing receiver must zero the private key", allZero(made.privateKey.copyBytes()))
        assertFalse("the holder must be empty after a throwing receiver", holder.hasAttempt())
    }

    @Test
    fun `the holder does not wipe the attempt after a normal return`() {
        val holder = SignInHolder(PairStarter())
        val made = holder.begin()
        holder.receive(goodLink(codeOne()), RecordingReceiver())
        assertFalse("the holder must not wipe the attempt after a normal return", made.isWiped)
        assertFalse("the holder must not zero the secret bytes after a normal return", allZero(made.copySecretBytes()))
        assertFalse("the holder must not zero the private key after a normal return", allZero(made.privateKey.copyBytes()))
    }

    @Test
    fun `the default receiver wipes the attempt and reports not finished`() {
        val made = firstAttempt()
        assertFalse("the attempt must hold its secret bytes before the default receiver", allZero(made.copySecretBytes()))
        val receipt = DefaultHandoffReceiver.onHandoff(codeOne(), made)
        assertEquals("the default receiver must report NOT_FINISHED", HandoffReceipt.NOT_FINISHED, receipt)
        assertTrue("the default receiver must wipe the attempt", made.isWiped)
        assertTrue("the default receiver must zero the secret bytes", allZero(made.copySecretBytes()))
        assertTrue("the default receiver must zero the private key", allZero(made.privateKey.copyBytes()))
    }

    @Test
    fun `no outcome or attempt prints the code or any byte`() {
        val holder = SignInHolder(PairStarter())
        val made = holder.begin()
        val code = codeOne()
        val outcome = holder.receive(goodLink(code), RecordingReceiver())
        assertEquals("the Handed outcome must print its receipt only", "HandoffOutcome.Handed(TAKEN)", outcome.toString())
        val printed = listOf(outcome.toString(), HandoffOutcome.Ignored.toString(), HandoffOutcome.NoAttempt.toString(), made.toString())
        for (text in printed) {
            assertFalse("a printed outcome or attempt holds the code", text.contains(code))
            assertFalse("a printed outcome or attempt holds the challenge", text.contains(made.challenge))
            assertFalse("a printed outcome or attempt shows an array identity", text.contains("[B@"))
        }
    }

    @Test
    fun `begin when the starter throws leaves the holder empty and the old attempt wiped`() {
        val starter = SwitchableStarter()
        val holder = SignInHolder(starter)
        val old = holder.begin()
        assertFalse("the old attempt must hold its secret bytes before the failed begin", allZero(old.copySecretBytes()))
        starter.broken = true
        try {
            holder.begin()
            fail("the starter failure was swallowed")
        } catch (expected: IllegalStateException) {
            assertTrue("the starter failure must reach the caller", expected.message.orEmpty().isNotEmpty())
        }
        assertTrue("a failed begin must still wipe the old attempt", old.isWiped)
        assertTrue("a failed begin must zero the old secret bytes", allZero(old.copySecretBytes()))
        assertFalse("a failed begin must leave the holder empty", holder.hasAttempt())
    }
}

private const val FIRST_CHALLENGE = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
private const val SECOND_CHALLENGE = "h0MHWX2Mnc_cEYThDk9bpQ7-lifZvd4YEyafEtXRNd4"

private fun allZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

private fun codeOne(): String = "Q".repeat(43)

private fun codeTwo(): String = "R".repeat(43)

private fun goodLink(code: String): String = "breaker://handoff?code=" + code

private fun receiptOf(outcome: HandoffOutcome): HandoffReceipt? = (outcome as? HandoffOutcome.Handed)?.receipt

private fun hostileLinks(): List<String?> {
    val good = codeOne()
    return listOf(
        null,
        "",
        "Breaker://handoff?code=$good",
        "breaker://HANDOFF?code=$good",
        "https://handoff?code=$good",
        "breaker://other?code=$good",
        "breaker://user@handoff?code=$good",
        "breaker://handoff:8080?code=$good",
        "breaker://handoff/x?code=$good",
        "breaker://handoff?code=$good#x",
        "breaker://handoff?code=$good&extra=1",
        "breaker://handoff?code=$good&code=$good",
        "breaker://handoff?code=",
        "breaker://handoff?code=${"Q".repeat(42)}",
        "breaker://handoff?code=${"Q".repeat(44)}",
        "breaker://handoff?code=${"!".repeat(43)}",
        "breaker://handoff?code=$good/",
        "breaker://handoff?code=$good ",
    )
}

private fun firstAttempt(): SignInAttempt = buildAttempt("A".repeat(43), FIRST_CHALLENGE, 1)

private fun secondAttempt(): SignInAttempt = buildAttempt("Y".repeat(43), SECOND_CHALLENGE, 2)

private fun buildAttempt(typedText: String, challenge: String, marker: Int): SignInAttempt =
    SignInAttempt(typedText.toByteArray(Charsets.US_ASCII), SignInPrivateKey(ByteArray(32) { (marker + 2).toByte() }), ByteArray(32) { marker.toByte() }, challenge)

private class PairStarter : SignInStarter {
    var calls: Int = 0
        private set

    override fun begin(): SignInAttempt {
        calls++
        return if (calls % 2 == 1) firstAttempt() else secondAttempt()
    }
}

private class SwitchableStarter : SignInStarter {
    var broken: Boolean = false

    override fun begin(): SignInAttempt {
        if (broken) throw IllegalStateException("the starter could not begin")
        return firstAttempt()
    }
}

private class RecordingReceiver(
    private val reply: HandoffReceipt = HandoffReceipt.TAKEN,
    private val holderHasAttempt: () -> Boolean = { false },
) : HandoffReceiver {
    val codes: MutableList<String> = mutableListOf()
    val attempts: MutableList<SignInAttempt> = mutableListOf()
    var holderHadAttemptDuringCall: Boolean = false
        private set

    override fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt {
        codes += code
        attempts += attempt
        holderHadAttemptDuringCall = holderHasAttempt()
        return reply
    }
}

private class ThrowingReceiver(private val failure: RuntimeException) : HandoffReceiver {
    var calls: Int = 0
        private set

    override fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt {
        calls++
        throw failure
    }
}
