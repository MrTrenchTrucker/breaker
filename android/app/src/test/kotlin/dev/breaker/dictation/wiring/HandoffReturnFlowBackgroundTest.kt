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

private const val HRB_VIEW = "android.intent.action.VIEW"
private val HRB_CODE: String = "Q".repeat(43)
private val HRB_LINK: String = "breaker://handoff?code=" + HRB_CODE

/** A background that only queues: the block runs when the test says so. */
private class HrbQueuedBackground : Background {
    val queue: MutableList<() -> Unit> = mutableListOf()

    override fun submit(block: () -> Unit) {
        queue.add(block)
    }

    fun runAll() {
        val blocks: List<() -> Unit> = queue.toList()
        queue.clear()
        for (block in blocks) block()
    }
}

/** A background whose scheduling fails with [failure]. */
private class HrbRefusingBackground(private val failure: Throwable) : Background {
    var submits: Int = 0

    override fun submit(block: () -> Unit) {
        submits++
        throw failure
    }
}

/** A failure that is an Error, not an Exception, such as a thread that cannot be started. */
private class HrbSchedulingError : Error("cannot schedule")

private class HrbStarter(private val attempt: SignInAttempt) : SignInStarter {
    override fun begin(): SignInAttempt = attempt
}

private class HrbReceiver(private val reply: HandoffReceipt) : HandoffReceiver {
    var calls: Int = 0

    override fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt {
        calls++
        attempt.wipe()
        return reply
    }
}

private class HrbThrowingReceiver(private val failure: Throwable) : HandoffReceiver {
    override fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt = throw failure
}

private fun hrbAttempt(): SignInAttempt =
    SignInAttempt(
        "A".repeat(43).toByteArray(Charsets.US_ASCII),
        SignInPrivateKey(ByteArray(32) { 3 }),
        ByteArray(32) { 1 },
        "C".repeat(43),
    )

/**
 * The return flow with a background. A good link is queued and nothing is recorded until the block
 * runs. A link the intent rule refuses is recorded at once. When the background cannot take the
 * block, for an Exception or an Error, the attempt is wiped and NOT_FINISHED is recorded once.
 */
class HandoffReturnFlowBackgroundTest {
    private val attempt = hrbAttempt()
    private val holder = SignInHolder(HrbStarter(attempt))
    private val store = SignInReturnStore()
    private var heard = 0

    init {
        store.listen { heard++ }
    }

    private fun flowOf(receiver: HandoffReceiver, background: Background?): HandoffReturnFlow {
        val wiring = SignInStartWiring(holder, receiver)
        wiring.start("https://h.example")
        if (background == null) return HandoffReturnFlow(wiring, store)
        return HandoffReturnFlow(wiring, store, background)
    }

    @Test
    fun `a good link answers true at once and records nothing until the block runs`() {
        val queued = HrbQueuedBackground()
        val receiver = HrbReceiver(HandoffReceipt.TAKEN)
        val flow = flowOf(receiver, queued)
        assertTrue("wiring: handle must answer true at once", flow.handle(HRB_VIEW, HRB_LINK))
        assertEquals("wiring: exactly one block must be queued", 1, queued.queue.size)
        assertEquals("wiring: the receiver must not run before the block", 0, receiver.calls)
        assertEquals("wiring: the listener must not be called before the block", 0, heard)
        assertNull("wiring: nothing may be recorded before the block", store.take())
    }

    @Test
    fun `running the block records TAKEN and calls the listener once`() {
        val queued = HrbQueuedBackground()
        val flow = flowOf(HrbReceiver(HandoffReceipt.TAKEN), queued)
        flow.handle(HRB_VIEW, HRB_LINK)
        queued.runAll()
        assertEquals("wiring: one call to the listener", 1, heard)
        assertEquals("wiring: the block must record TAKEN", SignInReturnState.TAKEN, store.take())
    }

    @Test
    fun `running the block records NOT_FINISHED when the receiver did not finish`() {
        val queued = HrbQueuedBackground()
        val flow = flowOf(HrbReceiver(HandoffReceipt.NOT_FINISHED), queued)
        flow.handle(HRB_VIEW, HRB_LINK)
        queued.runAll()
        assertEquals("wiring: one call to the listener", 1, heard)
        assertEquals(
            "wiring: the block must record NOT_FINISHED",
            SignInReturnState.NOT_FINISHED,
            store.take(),
        )
    }

    @Test
    fun `a good link with no waiting attempt records NO_ATTEMPT when the block runs`() {
        val queued = HrbQueuedBackground()
        val wiring = SignInStartWiring(holder, HrbReceiver(HandoffReceipt.TAKEN))
        val flow = HandoffReturnFlow(wiring, store, queued)
        flow.handle(HRB_VIEW, HRB_LINK)
        assertNull("wiring: nothing is recorded before the block runs", store.take())
        queued.runAll()
        assertEquals("wiring: the block must record NO_ATTEMPT", SignInReturnState.NO_ATTEMPT, store.take())
    }

    @Test
    fun `a link the intent rule refuses is recorded as IGNORED at once without the background`() {
        val queued = HrbQueuedBackground()
        val flow = flowOf(HrbReceiver(HandoffReceipt.TAKEN), queued)
        assertTrue("wiring: a null action must answer true", flow.handle(null, HRB_LINK))
        assertEquals(
            "wiring: a refused link must be recorded at once",
            SignInReturnState.IGNORED,
            store.take(),
        )
        assertTrue("wiring: null data must answer true", flow.handle(HRB_VIEW, null))
        assertEquals("wiring: null data must be recorded at once", SignInReturnState.IGNORED, store.take())
        assertEquals("wiring: the background must not be used", 0, queued.queue.size)
        assertEquals("wiring: the listener must be called once for each record", 2, heard)
        assertFalse("wiring: a refused link must keep the attempt", attempt.isWiped)
    }

    @Test
    fun `a background that cannot take the block wipes the attempt and records NOT_FINISHED once`() {
        val refusing = HrbRefusingBackground(IllegalStateException("stopped"))
        val receiver = HrbReceiver(HandoffReceipt.TAKEN)
        val flow = flowOf(receiver, refusing)
        assertTrue("wiring: handle must still answer true", flow.handle(HRB_VIEW, HRB_LINK))
        assertEquals("wiring: one scheduling attempt", 1, refusing.submits)
        assertTrue("wiring: the attempt must be wiped", attempt.isWiped)
        assertFalse("wiring: the holder must be empty", holder.hasAttempt())
        assertEquals("wiring: the listener must be called once", 1, heard)
        assertEquals("wiring: NOT_FINISHED must be recorded", SignInReturnState.NOT_FINISHED, store.take())
        assertNull("wiring: only one state may be recorded", store.take())
        assertEquals("wiring: the receiver must not run", 0, receiver.calls)
    }

    @Test
    fun `a background that fails with an Error wipes the attempt and records NOT_FINISHED once`() {
        val refusing = HrbRefusingBackground(HrbSchedulingError())
        val flow = flowOf(HrbReceiver(HandoffReceipt.TAKEN), refusing)
        assertTrue("wiring: handle must still answer true", flow.handle(HRB_VIEW, HRB_LINK))
        assertTrue("wiring: the attempt must be wiped", attempt.isWiped)
        assertEquals("wiring: the listener must be called once", 1, heard)
        assertEquals("wiring: NOT_FINISHED must be recorded", SignInReturnState.NOT_FINISHED, store.take())
    }

    @Test
    fun `a receiver that throws an Exception in the queued block records nothing`() {
        val queued = HrbQueuedBackground()
        val flow = flowOf(HrbThrowingReceiver(IllegalStateException("receiver failed")), queued)
        flow.handle(HRB_VIEW, HRB_LINK)
        queued.runAll()
        assertEquals("wiring: nothing may call the listener", 0, heard)
        assertNull("wiring: nothing may be recorded", store.take())
        assertTrue("wiring: the attempt must be wiped", attempt.isWiped)
    }

    @Test
    fun `the inline default records before handle returns`() {
        val flow = flowOf(HrbReceiver(HandoffReceipt.TAKEN), null)
        assertTrue("wiring: handle must answer true", flow.handle(HRB_VIEW, HRB_LINK))
        assertEquals("wiring: the listener must be called before handle returns", 1, heard)
        assertEquals(
            "wiring: the state must be recorded before handle returns",
            SignInReturnState.TAKEN,
            store.take(),
        )
    }

    /** With the inline default an Error from the receiver reaches the flow, which records NOT_FINISHED. */
    @Test
    fun `with the inline default an Error thrown by the receiver is recorded as NOT_FINISHED`() {
        val flow = flowOf(HrbThrowingReceiver(HrbSchedulingError()), null)
        assertTrue("wiring: handle must answer true", flow.handle(HRB_VIEW, HRB_LINK))
        assertTrue("wiring: the attempt must be wiped", attempt.isWiped)
        assertEquals("wiring: the listener must be called once", 1, heard)
        assertEquals("wiring: NOT_FINISHED must be recorded", SignInReturnState.NOT_FINISHED, store.take())
    }
}
