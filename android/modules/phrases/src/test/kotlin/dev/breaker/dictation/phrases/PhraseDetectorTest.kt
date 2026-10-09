package dev.breaker.dictation.phrases

import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.model.WordUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector's session rules, driven through [ScriptedWordStream]. Everything runs on
 * the test's own thread: no thread, clock or wait is started here.
 */
class PhraseDetectorTest {
    private fun breakers(final: Boolean = false): WordUpdate =
        update(final, "breaker" to 0L, "breaker" to 200L)

    @Test
    fun `isListening is false before start, true while listening, and false after stop`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)

        assertFalse("not listening before start", detector.isListening)
        detector.start { }
        assertTrue("listening after start", detector.isListening)
        detector.stop()
        assertFalse("not listening after stop", detector.isListening)
    }

    @Test
    fun `a second start while listening keeps the first callback and starts the stream once`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val first = mutableListOf<PhraseEvent>()
        val second = mutableListOf<PhraseEvent>()

        detector.start { first += it }
        detector.start { second += it }
        stream.feed(breakers())

        assertEquals("the stream was started once", 1, stream.startCount)
        assertEquals("the first callback stays and gets the event", listOf(PhraseEvent.Wake), first)
        assertTrue("the second callback is never used", second.isEmpty())
    }

    @Test
    fun `stop when not listening is safe and does not stop the stream`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)

        detector.stop()
        detector.stop()

        assertFalse("still not listening", detector.isListening)
        assertEquals("the stream was never started", 0, stream.startCount)
        assertEquals("the stream was never stopped", 0, stream.stopCount)
    }

    @Test
    fun `events are delivered in the order they appear in the update`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val got = mutableListOf<PhraseEvent>()
        detector.start { got += it }

        stream.feed(
            update(false, "breaker" to 0L, "breaker" to 200L, "and" to 1_000L, "i'm" to 1_200L, "gone" to 1_500L),
        )

        assertEquals("wake first, then send with the and offset", listOf(PhraseEvent.Wake, PhraseEvent.Send(1_000L)), got)
    }

    @Test
    fun `no event reaches the callback after stop, even through a captured callback`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val got = mutableListOf<PhraseEvent>()
        detector.start { got += it }

        detector.stop()
        stream.feed(breakers())
        stream.feedThrough(0, breakers())

        assertTrue("nothing is delivered after stop", got.isEmpty())
    }

    @Test
    fun `a stale callback from an old session delivers nothing to the new session`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val old = mutableListOf<PhraseEvent>()
        val fresh = mutableListOf<PhraseEvent>()
        detector.start { old += it }
        detector.stop()
        detector.start { fresh += it }

        stream.feedThrough(0, breakers())
        assertTrue("the stale callback reaches nobody in the new session", fresh.isEmpty())
        assertTrue("the old callback is never called back", old.isEmpty())

        stream.feed(breakers())
        assertEquals("the live callback of the new session delivers once", listOf(PhraseEvent.Wake), fresh)
    }

    @Test
    fun `an update fed through an old session callback does not change what the new session reports`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val first = mutableListOf<PhraseEvent>()
        val second = mutableListOf<PhraseEvent>()
        detector.start { first += it }
        stream.feed(breakers())
        detector.stop()

        detector.start { second += it }
        stream.feedThrough(0, breakers())
        assertTrue("the old callback delivers nothing to the new session", second.isEmpty())

        stream.feed(breakers())
        assertEquals("the new callback reports the wake once", listOf(PhraseEvent.Wake), second)
        assertEquals("the first session reported it once", listOf(PhraseEvent.Wake), first)
    }

    @Test
    fun `stop from inside onPhrase returns, the stream stops once, and the rest of the update is not delivered`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val got = mutableListOf<PhraseEvent>()
        detector.start { event ->
            got += event
            detector.stop()
        }

        stream.feed(
            update(false, "breaker" to 0L, "breaker" to 200L, "and" to 1_000L, "i'm" to 1_200L, "gone" to 1_500L),
        )

        assertEquals("only the wake before the stop was delivered", listOf(PhraseEvent.Wake), got)
        assertEquals("the stream was stopped once", 1, stream.stopCount)
        assertFalse("the detector is not listening", detector.isListening)
    }

    @Test
    fun `stop then start clears the reported set, so the phrase is reported again`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val first = mutableListOf<PhraseEvent>()
        val second = mutableListOf<PhraseEvent>()
        detector.start { first += it }
        stream.feed(breakers())
        stream.feed(breakers())
        assertEquals("the repeat inside the session is silent", listOf(PhraseEvent.Wake), first)

        detector.stop()
        detector.start { second += it }
        stream.feed(breakers())

        assertEquals("after stop then start the wake is reported again", listOf(PhraseEvent.Wake), second)
        assertEquals("two real starts", 2, stream.startCount)
    }

    @Test
    fun `restart gives a fresh dedupe for a new session`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val first = mutableListOf<PhraseEvent>()
        val second = mutableListOf<PhraseEvent>()
        detector.start { first += it }
        stream.feed(breakers())
        detector.stop()

        detector.start { second += it }
        stream.feed(breakers())

        assertEquals("the first session reported once", listOf(PhraseEvent.Wake), first)
        assertEquals("the new session reports it fresh", listOf(PhraseEvent.Wake), second)
    }

    @Test
    fun `the detector delivers on the thread that feeds it`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        var seen: Thread? = null
        detector.start { seen = Thread.currentThread() }

        stream.feed(breakers())

        assertSame("the callback ran on the feeding thread, with no new thread", Thread.currentThread(), seen)
    }

    @Test
    fun `send carries the startMs of and end to end`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val got = mutableListOf<PhraseEvent>()
        detector.start { got += it }

        stream.feed(update(false, "hello" to 0L, "there" to 600L, "and" to 2_500L, "i'm" to 2_700L, "gone" to 3_000L))

        assertEquals("the offset is the start of and", listOf(PhraseEvent.Send(2_500L)), got)
    }

    @Test
    fun `a send reported in a session is not reported again by a second update in the same session`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val got = mutableListOf<PhraseEvent>()
        detector.start { got += it }

        stream.feed(sendUpdate(false))
        stream.feed(sendUpdate(false))

        assertEquals("only the first send is reported", listOf(PhraseEvent.Send(1_000L)), got)
    }

    @Test
    fun `stop then start clears the reported send, so the send is reported again in a non-final update`() {
        val stream = ScriptedWordStream()
        val detector = PhraseDetector(stream)
        val first = mutableListOf<PhraseEvent>()
        val second = mutableListOf<PhraseEvent>()
        detector.start { first += it }
        stream.feed(sendUpdate(false))
        detector.stop()

        detector.start { second += it }
        stream.feed(sendUpdate(false))

        assertEquals("the first session reported the send once", listOf(PhraseEvent.Send(1_000L)), first)
        assertEquals("after stop then start the send is reported again", listOf(PhraseEvent.Send(1_000L)), second)
    }

    private fun sendUpdate(final: Boolean): WordUpdate =
        update(final, "and" to 1_000L, "i'm" to 1_200L, "gone" to 1_500L)
}
