package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.FedWordStream
import dev.breaker.dictation.stt.ondevice.KeywordLoad
import dev.breaker.dictation.stt.ondevice.KeywordModel
import dev.breaker.dictation.stt.ondevice.ModelLoader
import dev.breaker.dictation.stt.ondevice.SherpaTranscriptionException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The keyword source over a scripted loader and a counting stream maker: when it answers a stream and when it answers null. */
class KeywordPhraseSourceTest {

    private val model = KeywordModel("kws-gigaspeech-3m", File("model-directory"), "digest")

    private fun ready(): KeywordLoad = KeywordLoad.Ready(model)

    private fun refused(why: ModelLoader.Refusal): KeywordLoad = KeywordLoad.Refused(why, "refused")

    /** A source whose loader answers [loads] in order, one per call, and whose maker counts its calls. */
    private class Rig(private val loads: List<() -> KeywordLoad>, private val make: (KeywordModel) -> FedWordStream = { PhraseFakeWordStream() }) {
        var loadCalls: Int = 0
            private set
        val made: MutableList<KeywordModel> = ArrayList()
        val streams: MutableList<FedWordStream> = ArrayList()

        val source: PhraseStreamSource = KeywordPhraseSource(
            load = { loads[loadCalls++ % loads.size]() },
            create = { m ->
                made.add(m)
                make(m).also { streams.add(it) }
            },
        )
    }

    @Test
    fun `a ready model gives the stream the maker made for that model`() {
        val rig = Rig(listOf({ ready() }))
        val stream: FedWordStream? = rig.source.newStream()
        assertSame("app: the source did not answer the made stream", rig.streams.single(), stream)
        assertSame("app: the maker was not given the loaded model", model, rig.made.single())
    }

    @Test
    fun `every refusal of the loader answers null and makes no stream`() {
        for (why in ModelLoader.Refusal.values()) {
            val rig = Rig(listOf({ refused(why) }))
            assertNull("app: the source answered a stream for the refusal $why", rig.source.newStream())
            assertEquals("app: a stream was made for the refusal $why", 0, rig.made.size)
        }
    }

    @Test
    fun `a stream that cannot be made because the model fails a check answers null`() {
        val rig = Rig(listOf({ ready() }), { throw SherpaTranscriptionException("the keyword model files are missing or empty") })
        assertNull("app: a transcription failure reached the take", rig.source.newStream())
        assertEquals("app: the maker was not asked", 1, rig.made.size)
    }

    @Test
    fun `a missing native class answers null and does not throw into the take`() {
        val rig = Rig(listOf({ ready() }), { throw NoClassDefFoundError("a native class") })
        assertNull("app: a linkage error reached the take", rig.source.newStream())
    }

    @Test
    fun `any other exception from the maker answers null`() {
        val rig = Rig(listOf({ ready() }), { throw IllegalStateException("broken") })
        assertNull("app: a runtime exception reached the take", rig.source.newStream())
    }

    @Test
    fun `a loader that throws an exception or a linkage error answers null and makes no stream`() {
        val boom = Rig(listOf({ throw IllegalStateException("loader broke") }))
        assertNull("app: a loader exception reached the take", boom.source.newStream())
        val link = Rig(listOf({ throw UnsatisfiedLinkError("no library") }))
        assertNull("app: a loader linkage error reached the take", link.source.newStream())
        assertEquals("app: a stream was made after a failed load", 0, boom.made.size + link.made.size)
    }

    @Test
    fun `the loader is asked on every call so a model installed later is picked up and one removed later answers null`() {
        val rig = Rig(listOf({ refused(ModelLoader.Refusal.NOT_INSTALLED) }, { ready() }, { refused(ModelLoader.Refusal.NOT_INSTALLED) }))
        assertNull("app: a missing model answered a stream", rig.source.newStream())
        val picked: FedWordStream? = rig.source.newStream()
        assertSame("app: a model installed later was not picked up", rig.streams.single(), picked)
        assertNull("app: a model removed later still answered a stream", rig.source.newStream())
        assertEquals("app: the loader was not asked once per call", 3, rig.loadCalls)
    }

    @Test
    fun `each take gets its own new stream`() {
        val rig = Rig(listOf({ ready() }))
        val first: FedWordStream? = rig.source.newStream()
        val second: FedWordStream? = rig.source.newStream()
        assertNotSame("app: two takes were given the same stream", first, second)
        assertEquals("app: the maker was not asked once per take", 2, rig.made.size)
    }
}
