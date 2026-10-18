package dev.breaker.dictation.service

import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.audio.MicSourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Protects the capture-ended signal: the source reports its end once per take, never when the app
 * asked for the end, and always passes the real source's behaviour through unchanged.
 */
internal class ReportingMicSourceTest {

    private val inner = FakeMicSource(sampleRateHz = 48000, channelCount = 2)
    private var ended = 0
    private val source = ReportingMicSource(inner) { ended += 1 }

    @Test
    fun `rate, channels and read results pass through`() {
        inner.readResult = 480
        assertEquals("app: the sample rate must come from the inner source", 48000, source.sampleRateHz)
        assertEquals("app: the channel count must come from the inner source", 2, source.channelCount)
        assertEquals("app: the read result must come from the inner source", 480, source.read(ShortArray(8), 0, 8))
        inner.readResult = -3
        assertEquals("app: a negative read result must pass through", -3, source.read(ShortArray(8), 0, 8))
        assertEquals("app: a negative read result must be reported by the read itself", 1, ended)
    }

    @Test
    fun `a negative read reports once, returns the value unchanged and a second one reports nothing more`() {
        inner.readResult = -3
        assertEquals("app: the negative value must come back unchanged", -3, source.read(ShortArray(8), 0, 8))
        assertEquals("app: a negative read must report once", 1, ended)
        inner.readResult = -7
        assertEquals("app: a second negative value must come back unchanged", -7, source.read(ShortArray(8), 0, 8))
        assertEquals("app: a second negative read in the same take must not report again", 1, ended)
        source.close()
        assertEquals("app: the close after a negative read must not report again", 1, ended)
    }

    @Test
    fun `a negative read after a stop request reports nothing but still returns the value`() {
        inner.readResult = -3
        source.markStopRequested()
        assertEquals("app: the negative value must come back unchanged", -3, source.read(ShortArray(8), 0, 8))
        assertEquals("app: a negative read the app caused by stopping must report nothing", 0, ended)
    }

    @Test
    fun `a negative read is reported again after rearm`() {
        inner.readResult = -3
        source.read(ShortArray(8), 0, 8)
        source.rearm()
        source.read(ShortArray(8), 0, 8)
        assertEquals("app: after rearm a negative read must report again", 2, ended)
    }

    @Test
    fun `a zero read and a positive read report nothing`() {
        inner.readResult = 0
        assertEquals("app: a zero read must come back unchanged", 0, source.read(ShortArray(8), 0, 8))
        inner.readResult = 5
        assertEquals("app: a positive read must come back unchanged", 5, source.read(ShortArray(8), 0, 8))
        assertEquals("app: neither a zero nor a positive read may report", 0, ended)
    }

    @Test
    fun `open and close each reach the inner source exactly once`() {
        source.open()
        source.close()
        assertEquals("app: open must reach the inner source once", 1, inner.opens)
        assertEquals("app: close must reach the inner source once", 1, inner.closes)
    }

    @Test
    fun `close with no stop request reports once`() {
        source.open()
        source.close()
        assertEquals("app: a close nobody asked for must report once", 1, ended)
    }

    @Test
    fun `close after a stop request reports nothing`() {
        source.open()
        source.markStopRequested()
        source.close()
        assertEquals("app: a close the app asked for must report nothing", 0, ended)
    }

    @Test
    fun `a second close reports nothing more`() {
        source.close()
        source.close()
        assertEquals("app: a second close must not report again", 1, ended)
    }

    @Test
    fun `an open that fails reports once and rethrows the same error`() {
        val failure = FakeMicSource.micFailure()
        inner.openError = failure
        val thrown = assertThrows("app: open must rethrow", MicSourceException::class.java) { source.open() }
        assertSame("app: open must rethrow the inner error itself", failure, thrown)
        assertEquals("app: an open that fails must report once", 1, ended)
        source.close()
        assertEquals("app: the close after a failed open must not report again", 1, ended)
    }

    @Test
    fun `a read that fails reports once and rethrows the same error`() {
        val failure = FakeMicSource.micFailure()
        inner.readError = failure
        val thrown = assertThrows("app: read must rethrow", MicSourceException::class.java) { source.read(ShortArray(4), 0, 4) }
        assertSame("app: read must rethrow the inner error itself", failure, thrown)
        assertEquals("app: a read that fails must report once", 1, ended)
    }

    @Test
    fun `a failure after a stop request reports nothing but still rethrows`() {
        inner.readError = FakeMicSource.micFailure()
        source.markStopRequested()
        assertThrows("app: read must rethrow", MicSourceException::class.java) { source.read(ShortArray(4), 0, 4) }
        assertEquals("app: a failure the app caused by stopping must report nothing", 0, ended)
    }

    @Test
    fun `an inner close that throws still reports once and propagates`() {
        val failure = IllegalStateException("release failed")
        inner.closeError = failure
        val thrown = assertThrows("app: close must propagate", IllegalStateException::class.java) { source.close() }
        assertSame("app: close must propagate the inner error itself", failure, thrown)
        assertEquals("app: a close that throws must still report once", 1, ended)
    }

    @Test
    fun `rearm lets the next take report again`() {
        source.close()
        source.rearm()
        source.close()
        assertEquals("app: after rearm the next take must report again", 2, ended)
    }

    @Test
    fun `rearm clears a stop request`() {
        source.markStopRequested()
        source.close()
        source.rearm()
        source.close()
        assertEquals("app: after rearm a close nobody asked for must report", 1, ended)
    }

    @Test
    fun `the report comes after the inner close`() {
        val order: MutableList<String> = ArrayList()
        val ordered = ReportingMicSource(object : MicSource {
            override val sampleRateHz: Int = 16000
            override val channelCount: Int = 1
            override fun open() = Unit
            override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int = 0
            override fun close() {
                order.add("inner close")
            }
        }) { order.add("reported") }
        ordered.close()
        assertEquals("app: the report must come after the inner close", listOf("inner close", "reported"), order)
    }

    @Test
    fun `an open that fails with something other than a microphone error is not reported and is rethrown unchanged`() {
        val failure = IllegalStateException("not a microphone error")
        inner.openError = failure
        val thrown = assertThrows("app: open must rethrow", IllegalStateException::class.java) { source.open() }
        assertSame("app: open must rethrow the inner error itself", failure, thrown)
        assertEquals("app: an error that is not a microphone error must not be reported by open", 0, ended)
        source.close()
        assertEquals("app: the close after such an open must still report once", 1, ended)
    }

    @Test
    fun `a read that fails with something other than a microphone error is not reported and is rethrown unchanged`() {
        val failure = IllegalArgumentException("not a microphone error")
        inner.readError = failure
        val thrown = assertThrows("app: read must rethrow", IllegalArgumentException::class.java) { source.read(ShortArray(4), 0, 4) }
        assertSame("app: read must rethrow the inner error itself", failure, thrown)
        assertEquals("app: an error that is not a microphone error must not be reported by read", 0, ended)
        source.close()
        assertEquals("app: the close after such a read must still report once", 1, ended)
    }

    @Test
    fun `an open that fails after a stop request reports nothing but still rethrows`() {
        val failure = FakeMicSource.micFailure()
        inner.openError = failure
        source.markStopRequested()
        val thrown = assertThrows("app: open must rethrow", MicSourceException::class.java) { source.open() }
        assertSame("app: open must rethrow the inner error itself", failure, thrown)
        assertEquals("app: an open failure the app caused by stopping must report nothing", 0, ended)
    }

    /** A decorator whose reports note how many times [inner] had been closed by then. */
    private fun noting(closesAtReport: MutableList<Int>) = ReportingMicSource(inner) { closesAtReport.add(inner.closes) }

    @Test
    fun `a negative read closes the inner source once before it reports and a later close reports nothing more`() {
        val seen: MutableList<Int> = ArrayList()
        val noted = noting(seen)
        inner.readResult = -3
        assertEquals("app: the negative value must come back unchanged", -3, noted.read(ShortArray(8), 0, 8))
        assertEquals("app: a failed device must be closed exactly once by the failed read", 1, inner.closes)
        assertEquals("app: the report must come after the device was closed", listOf(1), seen)
        noted.close()
        assertEquals("app: the owner's close after a failed read must not report again", listOf(1), seen)
    }

    @Test
    fun `a read that throws a microphone error closes the inner source once before it reports and rethrows`() {
        val seen: MutableList<Int> = ArrayList()
        val failure = FakeMicSource.micFailure()
        inner.readError = failure
        val thrown = assertThrows("app: read must rethrow", MicSourceException::class.java) { noting(seen).read(ShortArray(4), 0, 4) }
        assertSame("app: read must rethrow the inner error itself", failure, thrown)
        assertEquals("app: a failed device must be closed exactly once by the failed read", 1, inner.closes)
        assertEquals("app: the report must come after the device was closed", listOf(1), seen)
    }

    @Test
    fun `a close that throws while releasing a failed device is swallowed and the failure is still reported`() {
        inner.closeError = IllegalStateException("release failed")
        inner.readResult = -1
        assertEquals("app: the negative value must come back even when the close fails", -1, source.read(ShortArray(8), 0, 8))
        assertEquals("app: the close must have been tried once", 1, inner.closes)
        assertEquals("app: a close that throws must not stop the report", 1, ended)
        val failure = FakeMicSource.micFailure()
        source.rearm()
        inner.readError = failure
        val thrown = assertThrows("app: read must rethrow the microphone error", MicSourceException::class.java) { source.read(ShortArray(4), 0, 4) }
        assertSame("app: the microphone error, not the close error, must come out", failure, thrown)
        assertEquals("app: the second failed device must be closed once more", 2, inner.closes)
        assertEquals("app: the second failure must be reported too", 2, ended)
    }

    @Test
    fun `a failed read after a stop request closes nothing`() {
        source.markStopRequested()
        inner.readResult = -3
        source.read(ShortArray(8), 0, 8)
        inner.readError = FakeMicSource.micFailure()
        assertThrows("app: read must rethrow", MicSourceException::class.java) { source.read(ShortArray(4), 0, 4) }
        assertEquals("app: the owner's stop closes the source, so a failed read after it must not", 0, inner.closes)
        assertEquals("app: nothing may be reported after a stop request", 0, ended)
    }

    @Test
    fun `a zero read and a positive read close nothing`() {
        inner.readResult = 0
        source.read(ShortArray(8), 0, 8)
        inner.readResult = 5
        source.read(ShortArray(8), 0, 8)
        assertEquals("app: a read that did not fail must not close the source", 0, inner.closes)
    }

    @Test
    fun `an open that fails closes nothing because nothing was opened`() {
        inner.openError = FakeMicSource.micFailure()
        assertThrows("app: open must rethrow", MicSourceException::class.java) { source.open() }
        assertEquals("app: an open that failed must not close the source", 0, inner.closes)
        assertEquals("app: an open that fails must still report once", 1, ended)
    }

    @Test
    fun `a second failed read in the same take closes nothing more and the next take closes again`() {
        inner.readResult = -3
        source.read(ShortArray(8), 0, 8)
        source.read(ShortArray(8), 0, 8)
        assertEquals("app: a take that was already reported must not close the device twice", 1, inner.closes)
        source.rearm()
        source.read(ShortArray(8), 0, 8)
        assertEquals("app: after rearm the next failed device must be closed", 2, inner.closes)
        assertEquals("app: after rearm the next failure must be reported", 2, ended)
    }
}
