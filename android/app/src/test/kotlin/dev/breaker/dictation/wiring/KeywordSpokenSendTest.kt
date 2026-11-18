package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.HeardWord
import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.model.WordUpdate
import dev.breaker.dictation.core.port.PhraseTrigger
import dev.breaker.dictation.phrases.PhraseDetector
import dev.breaker.dictation.stt.ondevice.FedWordStream
import dev.breaker.dictation.stt.ondevice.KeywordLoad
import dev.breaker.dictation.stt.ondevice.KeywordModel
import dev.breaker.dictation.stt.ondevice.ModelLoader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A word stream that keeps the callback of its start, so a test can say what the keyword engine heard. */
private class ScriptedWordStream : FedWordStream {
    private var callback: ((WordUpdate) -> Unit)? = null
    var running: Boolean = false
        private set

    override val isRunning: Boolean get() = running
    override val droppedChunks: Long get() = 0L
    override val droppedSamples: Long get() = 0L

    override fun start(onUpdate: (WordUpdate) -> Unit) {
        callback = onUpdate
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun feed(samples: FloatArray) {}

    /** Reports one hit as the keyword engine does: the words of the phrase, the first at the start time of the hit. */
    fun hear(vararg words: Pair<String, Long>) {
        callback?.invoke(WordUpdate(words.map { HeardWord(it.first, it.second) }, false))
    }

    /** Reports the final update as the keyword engine does: it repeats the words of the last hit only. */
    fun hearFinal(vararg words: Pair<String, Long>) {
        callback?.invoke(WordUpdate(words.map { HeardWord(it.first, it.second) }, true))
    }
}

/** The real detector, wrapped so a test sees every event it reports to the handler, wake events included. */
private class SeenTrigger(private val inner: PhraseTrigger, val seen: MutableList<PhraseEvent>) : PhraseTrigger {
    override val isListening: Boolean get() = inner.isListening

    override fun start(onPhrase: (PhraseEvent) -> Unit) {
        inner.start { event ->
            seen.add(event)
            onPhrase(event)
        }
    }

    override fun stop() {
        inner.stop()
    }
}

/**
 * The real phrase handler and the real phrase detector over the keyword source and a scripted word stream: what the keyword
 * engine hears reaches the spoken send of the take. The engine itself is not run here.
 */
class KeywordSpokenSendTest {

    private val model = KeywordModel("kws-gigaspeech-3m", File("model-directory"), "digest")

    private class Rig(load: () -> KeywordLoad, val stream: ScriptedWordStream = ScriptedWordStream()) {
        val background = ManualBackground()
        val main = ManualMain()
        val handler = PhraseSendHandler(background, main, KeywordPhraseSource(load) { stream })
        var sends: Int = 0

        fun begin() = handler.begun { sends += 1 }

        fun settle() {
            background.runAll()
            main.drain()
        }
    }

    private fun readyRig(): Rig = Rig({ KeywordLoad.Ready(model) })

    @Test
    fun `the send phrase heard at a start time is one send carrying that start`() {
        val rig = readyRig()
        rig.begin()
        rig.stream.hear("and" to 2000L, "I'm" to 2250L, "gone" to 2500L)
        rig.settle()
        assertEquals("app: the heard send phrase did not reach the send once", 1, rig.sends)
        assertEquals("app: the send did not carry the start of the word and", 2000L, rig.handler.takePendingTrim())
    }

    @Test
    fun `the send phrase without the apostrophe is also one send at its start`() {
        val rig = readyRig()
        rig.begin()
        rig.stream.hear("and" to 700L, "im" to 900L, "gone" to 1100L)
        rig.settle()
        assertEquals("app: the spelling without the apostrophe did not send once", 1, rig.sends)
        assertEquals("app: the send did not carry the start of the word and", 700L, rig.handler.takePendingTrim())
    }

    @Test
    fun `the same send heard twice in one take is still one send`() {
        val rig = readyRig()
        rig.begin()
        rig.stream.hear("and" to 2000L, "I'm" to 2250L, "gone" to 2500L)
        rig.stream.hear("and" to 5000L, "I'm" to 5250L, "gone" to 5500L)
        rig.settle()
        assertEquals("app: a second send in one take was not dropped", 1, rig.sends)
        assertEquals("app: the trim is not the one of the first send", 2000L, rig.handler.takePendingTrim())
    }

    @Test
    fun `the wake phrase alone makes no send`() {
        val rig = readyRig()
        rig.begin()
        rig.stream.hear("breaker" to 500L, "breaker" to 1000L)
        rig.settle()
        assertEquals("app: the wake phrase made a send", 0, rig.sends)
        assertNull("app: the wake phrase left a trim", rig.handler.takePendingTrim())
    }

    @Test
    fun `a model that does not load leaves the take with no stream and no send`() {
        val rig = Rig({ KeywordLoad.Refused(ModelLoader.Refusal.NOT_INSTALLED, "refused") })
        rig.begin()
        rig.stream.hear("and" to 2000L, "I'm" to 2250L, "gone" to 2500L)
        rig.settle()
        assertNull("app: a refused model left a stream for the audio tee", rig.handler.currentStream())
        assertEquals("app: a refused model made a send", 0, rig.sends)
        assertEquals("app: the stream of a refused model was started", false, rig.stream.running)
    }

    @Test
    fun `a loaded model gives the audio tee the stream and the detector listens on it`() {
        val rig = readyRig()
        rig.begin()
        assertNotNull("app: the audio tee found no stream", rig.handler.currentStream())
        assertEquals("app: the detector did not start the stream", true, rig.stream.running)
        rig.handler.stop()
        assertEquals("app: the stop did not stop the stream", false, rig.stream.running)
    }

    @Test
    fun `a send reaches the take port as the trim of its finish`() {
        val rig = readyRig()
        val take = PhraseFakeTake()
        val port = PhraseTakePort(take, rig.handler) { rig.sends += 1 }
        port.begin()
        rig.stream.hear("and" to 3000L, "I'm" to 3200L, "gone" to 3400L)
        rig.settle()
        port.finish()
        assertEquals("app: the spoken send did not reach the surface once", 1, rig.sends)
        assertEquals("app: the finish did not carry the trim of the spoken send", listOf<Long?>(3000L), take.trims)
    }

    /** The real handler and detector like [Rig], with the events the detector reports kept in [seen]. */
    private class EventRig(model: KeywordModel) {
        val stream = ScriptedWordStream()
        val seen: MutableList<PhraseEvent> = ArrayList()
        val background = ManualBackground()
        val main = ManualMain()
        val handler = PhraseSendHandler(background, main, KeywordPhraseSource({ KeywordLoad.Ready(model) }) { stream }) { words ->
            SeenTrigger(PhraseDetector(words), seen)
        }
        var sends: Int = 0

        fun begin() = handler.begun { sends += 1 }

        fun settle() {
            background.runAll()
            main.drain()
        }

        fun wake() = stream.hear("breaker" to 500L, "breaker" to 1000L)

        fun send() = stream.hear("and" to 2000L, "I'm" to 2250L, "gone" to 2500L)

        /** The final of a stream whose last hit was the send: only the send words, as the engine repeats its last hit. */
        fun sendFinal() = stream.hearFinal("and" to 2000L, "I'm" to 2250L, "gone" to 2500L)

        fun wakes(): Int = seen.count { it is PhraseEvent.Wake }

        fun sendTrims(): List<Long?> = seen.filterIsInstance<PhraseEvent.Send>().map { it.trimBeforeMs }
    }

    @Test
    fun `a wake then a send then the final repeating the send is one wake and one send`() {
        val rig = EventRig(model)
        rig.begin()
        rig.wake()
        rig.send()
        assertEquals("app: the wake and the send partials did not give one wake", 1, rig.wakes())
        assertEquals("app: the wake and the send partials did not give one send at its start", listOf(2000L), rig.sendTrims())
        rig.sendFinal()
        rig.settle()
        assertEquals("app: the final repeating the send gave a second wake", 1, rig.wakes())
        assertEquals("app: the final repeating the send gave a second send", listOf(2000L), rig.sendTrims())
        assertEquals("app: the handler did not make one send", 1, rig.sends)
        assertEquals("app: the send did not keep its start time", 2000L, rig.handler.takePendingTrim())
    }

    @Test
    fun `a send then the final repeating the send is one send`() {
        val rig = EventRig(model)
        rig.begin()
        rig.send()
        rig.sendFinal()
        rig.settle()
        assertEquals("app: a send with no wake gave a wake", 0, rig.wakes())
        assertEquals("app: the send and its repeating final did not give one send", listOf(2000L), rig.sendTrims())
        assertEquals("app: the handler did not make one send", 1, rig.sends)
        assertEquals("app: the send did not keep its start time", 2000L, rig.handler.takePendingTrim())
    }
}
