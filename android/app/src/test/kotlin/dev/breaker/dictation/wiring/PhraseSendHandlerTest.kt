package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.model.WordUpdate
import dev.breaker.dictation.core.port.PhraseTrigger
import dev.breaker.dictation.core.usecase.SendResult
import dev.breaker.dictation.stt.ondevice.FedWordStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A word stream that counts what it is offered; [feedError] makes every offer throw. */
internal class PhraseFakeWordStream : FedWordStream {
    var feeds: Int = 0
        private set
    var feedError: Throwable? = null
    var feedThread: Thread? = null
        private set

    override val isRunning: Boolean get() = false
    override val droppedChunks: Long get() = 0L
    override val droppedSamples: Long get() = 0L

    override fun start(onUpdate: (WordUpdate) -> Unit) {}

    override fun stop() {}

    override fun feed(samples: FloatArray) {
        feeds += 1
        feedThread = Thread.currentThread()
        feedError?.let { throw it }
    }
}

/** A detector that keeps its callback, counts its stops and can refuse to start. */
internal class PhraseFakeTrigger : PhraseTrigger {
    private var listening: Boolean = false
    private var callback: ((PhraseEvent) -> Unit)? = null
    var stops: Int = 0
        private set
    var startError: RuntimeException? = null

    override val isListening: Boolean get() = listening

    override fun start(onPhrase: (PhraseEvent) -> Unit) {
        startError?.let { throw it }
        listening = true
        callback = onPhrase
    }

    override fun stop() {
        stops += 1
        listening = false
    }

    /** Reports [event] on the caller's thread, as a detector reports on its own. */
    fun say(event: PhraseEvent) {
        callback?.invoke(event)
    }
}

/** Answers with one new stream per take while [answers] is set, and with null otherwise. */
internal class PhraseFakeStreams(private val answers: Boolean) : PhraseStreamSource {
    val streams: MutableList<PhraseFakeWordStream> = ArrayList()

    override fun newStream(): FedWordStream? {
        if (!answers) return null
        val stream = PhraseFakeWordStream()
        streams.add(stream)
        return stream
    }
}

/** A take port that keeps a call log and the trims it was finished with. */
internal class PhraseFakeTake : TakePort {
    override var sessionState: DictationState = DictationState.IDLE
    val calls: MutableList<String> = ArrayList()
    val trims: MutableList<Long?> = ArrayList()

    override fun begin(): BeginResult {
        calls.add("begin")
        sessionState = DictationState.RECORDING
        return BeginResult.Recording
    }

    override fun finish(): FinishResult {
        calls.add("finish")
        return FinishResult.ReadyToSend(tileTranscription("the words"))
    }

    override fun finishTrimmed(trimBeforeMs: Long?): FinishResult {
        calls.add("finishTrimmed")
        trims.add(trimBeforeMs)
        return FinishResult.ReadyToSend(tileTranscription("the words"))
    }

    override fun send(): SendResult? {
        calls.add("send")
        return null
    }

    override fun cancel() {
        calls.add("cancel")
    }

    override fun resetAfterTaken() {
        calls.add("reset")
    }
}

/** One handler over manual queues, with the detectors it makes kept in [triggers]. */
private class PhraseRig(answers: Boolean = true) {
    val background = ManualBackground()
    val main = ManualMain()
    val streams = PhraseFakeStreams(answers)
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
    var tileSends: Int = 0

    fun homePort(): PhraseTakePort = PhraseTakePort(take, handler) { homeSends += 1 }

    fun tilePort(): PhraseTakePort = PhraseTakePort(take, handler) { tileSends += 1 }

    /** Runs the take worker and then main until both are empty. */
    fun settle() {
        background.runAll()
        main.drain()
    }
}

class PhraseSendHandlerTest {

    @Test
    fun `a second spoken send in one take is dropped on the home surface`() {
        val rig = PhraseRig()
        val port = rig.homePort()
        port.begin()
        rig.triggers.last().say(PhraseEvent.Send(100L))
        rig.triggers.last().say(PhraseEvent.Send(200L))
        rig.settle()
        assertEquals("a second spoken send in one take was not dropped on home", 1, rig.homeSends)
    }

    @Test
    fun `a second spoken send in one take is dropped on the tile surface`() {
        val rig = PhraseRig()
        val port = rig.tilePort()
        port.begin()
        rig.triggers.last().say(PhraseEvent.Send(100L))
        rig.triggers.last().say(PhraseEvent.Send(200L))
        rig.settle()
        assertEquals("a second spoken send in one take was not dropped on the tile", 1, rig.tileSends)
    }

    @Test
    fun `a fresh take accepts its first spoken send`() {
        val rig = PhraseRig()
        val port = rig.homePort()
        port.begin()
        rig.triggers.last().say(PhraseEvent.Send(100L))
        rig.settle()
        port.resetAfterTaken()
        port.begin()
        rig.triggers.last().say(PhraseEvent.Send(300L))
        rig.settle()
        assertEquals("the first spoken send of a fresh take was dropped", 2, rig.homeSends)
    }

    @Test
    fun `the trim of an accepted spoken send reaches the finish of its take`() {
        val rig = PhraseRig()
        val port = rig.homePort()
        port.begin()
        rig.triggers.last().say(PhraseEvent.Send(1234L))
        rig.settle()
        rig.background.submit { port.finish() }
        rig.background.runAll()
        assertEquals("the trim of the spoken send did not reach the finish", listOf<Long?>(1234L), rig.take.trims)
    }

    @Test
    fun `a spoken send reaches the surface only on main and not on the detector thread`() {
        val rig = PhraseRig()
        val port = rig.homePort()
        port.begin()
        rig.triggers.last().say(PhraseEvent.Send(5L))
        assertEquals("the surface send ran on the detector thread, not on main", 0, rig.homeSends)
        rig.main.drain()
        assertEquals("the surface send did not run on main", 1, rig.homeSends)
    }

    @Test
    fun `an end that happens by itself stops the detector on the take worker`() {
        val rig = PhraseRig()
        rig.homePort().begin()
        val trigger = rig.triggers.last()
        rig.handler.onTakeEnded()
        assertEquals("the detector was stopped on the caller's thread, not on the take worker", 0, trigger.stops)
        rig.background.runAll()
        assertEquals("the self-end did not stop the detector", 1, trigger.stops)
    }

    @Test
    fun `a second stop after a self-end does nothing`() {
        val rig = PhraseRig()
        rig.homePort().begin()
        val trigger = rig.triggers.last()
        rig.handler.onTakeEnded()
        rig.background.runAll()
        rig.handler.onTakeEnded()
        rig.handler.stop()
        rig.background.runAll()
        assertEquals("the detector was stopped more than once", 1, trigger.stops)
    }

    @Test
    fun `a cancel stops the detector`() {
        val rig = PhraseRig()
        val port = rig.homePort()
        port.begin()
        val trigger = rig.triggers.last()
        port.cancel()
        assertEquals("a cancel left the phrase detector listening", 1, trigger.stops)
    }

    @Test
    fun `a finish stops the detector, as a device failure and a microphone yield both do`() {
        val rig = PhraseRig()
        val port = rig.homePort()
        port.begin()
        val trigger = rig.triggers.last()
        rig.background.submit { port.finish() }
        rig.background.runAll()
        assertEquals("a finish left the phrase detector listening", 1, trigger.stops)
    }

    @Test
    fun `a reset after a take stops the detector`() {
        val rig = PhraseRig()
        val port = rig.homePort()
        port.begin()
        val trigger = rig.triggers.last()
        port.resetAfterTaken()
        assertEquals("a reset left the phrase detector listening", 1, trigger.stops)
    }

    @Test
    fun `a detector that fails to start does not take the take down`() {
        val rig = PhraseRig()
        rig.startError = IllegalStateException("the detector refused to start")
        val port = rig.homePort()
        val answer: BeginResult? = try {
            port.begin()
        } catch (t: Throwable) {
            null
        }
        assertEquals("a phrase detector that failed to start took the take down", BeginResult.Recording, answer)
    }

    @Test
    fun `with no stream the spoken send is off and no detector is started`() {
        val rig = PhraseRig(answers = false)
        rig.homePort().begin()
        assertEquals("the off path started a phrase detector", 0, rig.triggers.size)
        assertNull("the off path gave the tee a word stream", rig.handler.currentStream())
        assertEquals("the off path posted a send", 0, rig.main.pending)
        assertNull("the off path left a trim", rig.handler.takePendingTrim())
    }

    @Test
    fun `with no stream the take begins, finishes and cancels as it does without phrases`() {
        val rig = PhraseRig(answers = false)
        val port = rig.homePort()
        val answer: BeginResult? = port.begin()
        rig.background.submit { port.finish() }
        rig.background.runAll()
        port.cancel()
        assertEquals("the take changed while the spoken send is off", listOf("begin", "finishTrimmed", "cancel"), rig.take.calls)
        assertEquals("the begin answer changed while the spoken send is off", BeginResult.Recording, answer)
    }
}
