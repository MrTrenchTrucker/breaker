package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.WordUpdate
import dev.breaker.dictation.stt.ondevice.FedWordStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A word stream that records how often it was started and stopped. */
private class HandlerForRecordingStream : FedWordStream {
    var starts: Int = 0
        private set
    var stops: Int = 0
        private set
    private var running: Boolean = false

    override val isRunning: Boolean get() = running
    override val droppedChunks: Long get() = 0L
    override val droppedSamples: Long get() = 0L

    override fun start(onUpdate: (WordUpdate) -> Unit) {
        starts += 1
        running = true
    }

    override fun stop() {
        stops += 1
        running = false
    }

    override fun feed(samples: FloatArray) {}
}

/** A source that answers one given stream and counts how often it was asked. */
private class HandlerForCountingSource(private val answer: FedWordStream?) : PhraseStreamSource {
    var asks: Int = 0
        private set

    override fun newStream(): FedWordStream? {
        asks += 1
        return answer
    }
}

/** The handler made by phraseHandlerFor: it uses the source it is given, and with no source it never starts a stream. */
class PhraseHandlerForSourceTest {

    @Test
    fun `a handler made with a source gets that source's stream when a take begins and starts it`() {
        val stream = HandlerForRecordingStream()
        val source = HandlerForCountingSource(stream)
        val handler: PhraseSendHandler = phraseHandlerFor(ManualBackground(), ManualMain(), source)
        handler.begun { }
        assertEquals("app: the source given to phraseHandlerFor was not asked once for the take", 1, source.asks)
        assertSame("app: the audio tee did not get the stream of the given source", stream, handler.currentStream())
        assertEquals("app: the stream of the given source was not started once", 1, stream.starts)
        assertEquals("app: the stream was running after the begin", true, stream.isRunning)
    }

    @Test
    fun `the stream of the given source is stopped when the handler stops`() {
        val stream = HandlerForRecordingStream()
        val handler: PhraseSendHandler = phraseHandlerFor(ManualBackground(), ManualMain(), HandlerForCountingSource(stream))
        handler.begun { }
        handler.stop()
        assertEquals("app: the stop did not stop the stream of the given source", 1, stream.stops)
        assertNull("app: a stopped take still shows a stream to the audio tee", handler.currentStream())
    }

    @Test
    fun `a handler made with no source never has a stream and starts nothing`() {
        val main = ManualMain()
        val handler: PhraseSendHandler = phraseHandlerFor(ManualBackground(), main)
        handler.begun { }
        assertNull("app: the default source gave the audio tee a stream", handler.currentStream())
        assertNull("app: the default source left a trim", handler.takePendingTrim())
        assertEquals("app: the default source posted a send", 0, main.pending)
    }
}
