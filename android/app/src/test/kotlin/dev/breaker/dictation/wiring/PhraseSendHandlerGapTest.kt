package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.PhraseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** One handler over manual queues, with the detectors it makes kept in [triggers]. */
private class PhraseGapRig {
    val background = ManualBackground()
    val main = ManualMain()
    val streams = PhraseFakeStreams(true)
    val triggers: MutableList<PhraseFakeTrigger> = ArrayList()
    var startError: RuntimeException? = null
    val handler: PhraseSendHandler = PhraseSendHandler(background, main, streams) { _ ->
        PhraseFakeTrigger().also {
            it.startError = startError
            triggers.add(it)
        }
    }
    val take = PhraseFakeTake()
    var homeSends: Int = 0

    fun homePort(): PhraseTakePort = PhraseTakePort(take, handler) { homeSends += 1 }
}

/**
 * Tests for the spoken send: a send posted to main must be dropped when its take ends first, the handler must
 * offer the stream of the running take, a new begin must stop the detector of the take before it, a send must
 * stop the detector, and a detector that fails to start must leave nothing behind. Each test names, in its own KDoc, the one production change that makes it fail.
 */
class PhraseSendHandlerGapTest {

    /**
     * Fails when the block that onEvent posts to main runs the send without first checking that its take is
     * still the running one.
     */
    @Test
    fun `a spoken send posted to main is dropped when its take ends before main runs it`() {
        val rig = PhraseGapRig()
        val port = rig.homePort()
        port.begin()
        rig.triggers.single().say(PhraseEvent.Send(null))
        assertEquals("the spoken send was not posted to main before the take ended", 1, rig.main.pending)
        port.resetAfterTaken()
        rig.main.drain()
        assertEquals("a spoken send ran on main after its take had ended", 0, rig.homeSends)
    }

    /**
     * Fails when currentStream answers null in place of the stream of the take that runs.
     */
    @Test
    fun `the handler offers the stream of the take that runs to the tee`() {
        val rig = PhraseGapRig()
        rig.homePort().begin()
        val offered = rig.handler.currentStream()
        assertNotNull("the handler offered no word stream while its take ran", offered)
        assertSame(
            "the handler offered a stream that is not the stream of its take",
            rig.streams.streams.single(),
            offered,
        )
    }

    /**
     * Fails when begun no longer stops the take that ran before it, so the detector of the earlier take keeps
     * listening after a later begin.
     */
    @Test
    fun `a begin stops the detector of the take that ran before it`() {
        val rig = PhraseGapRig()
        val port = rig.homePort()
        port.begin()
        port.begin()
        assertEquals("the detector of the earlier take was not stopped by the later begin", 1, rig.triggers[0].stops)
        assertEquals("the detector of the later take was stopped before its own end", 0, rig.triggers[1].stops)
    }

    /**
     * Fails when send no longer stops the detector before it passes the send on to the take.
     */
    @Test
    fun `a send stops the detector before the take sends`() {
        val rig = PhraseGapRig()
        val port = rig.homePort()
        port.begin()
        port.send()
        assertEquals("a send left the phrase detector listening", 1, rig.triggers.single().stops)
        assertEquals("the take did not end its calls with the send", "send", rig.take.calls.last())
    }

    /**
     * Fails when the catch branch of begun no longer stops the detector of a take whose start threw, which
     * leaves a stream open for the tee after the failed start.
     */
    @Test
    fun `a detector that fails to start leaves no stream and is stopped`() {
        val rig = PhraseGapRig()
        rig.startError = IllegalStateException("the detector refused to start")
        val answer: BeginResult = rig.homePort().begin()
        assertEquals("a phrase detector that failed to start changed the begin answer", BeginResult.Recording, answer)
        assertEquals("the detector of the failed start was not stopped", 1, rig.triggers.single().stops)
        assertNull("a failed start left a word stream for the tee", rig.handler.currentStream())
    }
}
