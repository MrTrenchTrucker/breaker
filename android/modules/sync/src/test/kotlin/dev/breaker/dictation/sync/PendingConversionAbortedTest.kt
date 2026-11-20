package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The swallowed throwable of PendingConversion.pass() is counted in aborted, failed stays zero,
 * and pass() itself never throws. UNCOMPILED: hand-traced, not run by any build. */
class PendingConversionAbortedTest {

    /** A transport whose fetch() throws, so the pass swallows the throwable. */
    private class ThrowingTransport : PendingSealedTransport {
        var calls = 0
            private set

        override fun fetch(): PullTransportResult {
            calls++
            throw IllegalStateException("android_sync: transport broke")
        }
    }

    /** A swallowed throwable is counted in aborted; failed stays 0; pass() never throws. */
    @Test
    fun `a swallowed throwable is counted in aborted, failed stays zero, pass never throws`() {
        val keys = FakeRecordKeys()
        keys.version = 2
        val queue = RecordingQueueProbe()
        val uploader = CountingUploader()
        val transport = ThrowingTransport()
        val conversion = PendingConversion(transport, queue, uploader, keys, RefusedKeyVersion())

        var thrown: Throwable? = null
        try {
            conversion.pass()
        } catch (t: Throwable) {
            thrown = t
        }
        assertNull("android_sync: pass never throws", thrown)
        assertEquals("android_sync: the fetch was attempted once", 1, transport.calls)

        val counts = conversion.counts
        assertEquals("android_sync: the swallowed throw is counted in aborted", 1, counts.aborted)
        assertEquals("android_sync: failed stays zero", 0, counts.failed)
        assertEquals("android_sync: nothing converted", 0, counts.converted)
    }
}