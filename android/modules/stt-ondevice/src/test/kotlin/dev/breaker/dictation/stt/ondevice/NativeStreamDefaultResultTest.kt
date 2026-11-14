package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The result a native stream gives when its adapter does not override it.
 *
 * Every other fake in this module overrides result(), so the default body in NativeStream was never read by a
 * test: a default that dropped the text, or invented pieces, would change nothing. FactoryProbeStream does not
 * override it, so it shows the default as the interface gives it.
 */
class NativeStreamDefaultResultTest {

    @Test
    fun `the default result carries the text of the stream and no pieces and no times`() {
        val result = FactoryProbeStream("hello world").result()
        assertEquals("stt-ondevice: the default result must carry the text of the stream", "hello world", result.text)
        assertEquals("stt-ondevice: the default result has no pieces", emptyList<String>(), result.tokens)
        assertEquals("stt-ondevice: the default result has no times", emptyList<Float>(), result.timestampsSeconds)
    }

    @Test
    fun `the default result of a stream with no text is empty`() {
        val result = FactoryProbeStream("").result()
        assertEquals("stt-ondevice: an empty stream gives an empty text", "", result.text)
        assertEquals("stt-ondevice: an empty stream gives no pieces", 0, result.tokens.size)
        assertEquals("stt-ondevice: an empty stream gives no times", 0, result.timestampsSeconds.size)
    }
}
