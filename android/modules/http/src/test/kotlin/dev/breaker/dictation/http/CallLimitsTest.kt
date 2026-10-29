package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CallLimitsTest {

    /** The refusal message of [build], or a failure when [build] accepts its values. */
    private fun refusalOf(build: () -> CallLimits): String {
        try {
            build()
        } catch (expected: IllegalArgumentException) {
            return expected.message.orEmpty()
        }
        fail("out-of-range limits were accepted")
        return ""
    }

    /** Asserts that [build] refuses its values with a message that holds no digit. */
    private fun refused(build: () -> CallLimits) {
        val message = refusalOf(build)
        assertTrue("the refusal message holds no digit", message.none { it.isDigit() })
    }

    @Test
    fun `the constants hold the numbers of the card`() {
        assertEquals(600_000, CallLimits.MAX_TIMEOUT_MS)
        assertEquals(16_777_216L, CallLimits.MAX_BYTES_REPLY)
        assertEquals(1_073_741_824L, CallLimits.MAX_STREAM_REPLY)
    }

    @Test
    fun `a connect and a read timeout of 1 and of 600000 are accepted`() {
        assertEquals(1, CallLimits(connectTimeoutMs = 1, readTimeoutMs = 1, maxReplyBytes = 1L).connectTimeoutMs)
        assertEquals(600_000, CallLimits(connectTimeoutMs = 600_000, readTimeoutMs = 600_000, maxReplyBytes = 1L).readTimeoutMs)
    }

    @Test
    fun `a connect timeout of 0 and of 600001 is refused`() {
        refused { CallLimits(connectTimeoutMs = 0, readTimeoutMs = 1, maxReplyBytes = 1L) }
        refused { CallLimits(connectTimeoutMs = 600_001, readTimeoutMs = 1, maxReplyBytes = 1L) }
    }

    @Test
    fun `a read timeout of 0 and of 600001 is refused`() {
        refused { CallLimits(connectTimeoutMs = 1, readTimeoutMs = 0, maxReplyBytes = 1L) }
        refused { CallLimits(connectTimeoutMs = 1, readTimeoutMs = 600_001, maxReplyBytes = 1L) }
    }

    @Test
    fun `a reply cap of 0 and of one byte over MAX_STREAM_REPLY is refused`() {
        refused { CallLimits(connectTimeoutMs = 1, readTimeoutMs = 1, maxReplyBytes = 0L) }
        refused { CallLimits(connectTimeoutMs = 1, readTimeoutMs = 1, maxReplyBytes = CallLimits.MAX_STREAM_REPLY + 1L) }
    }

    @Test
    fun `a reply cap of 1 and of MAX_STREAM_REPLY is accepted`() {
        assertEquals(1L, CallLimits(connectTimeoutMs = 1, readTimeoutMs = 1, maxReplyBytes = 1L).maxReplyBytes)
        assertEquals(
            CallLimits.MAX_STREAM_REPLY,
            CallLimits(connectTimeoutMs = 1, readTimeoutMs = 1, maxReplyBytes = CallLimits.MAX_STREAM_REPLY).maxReplyBytes,
        )
    }

    @Test
    fun `a reply cap above MAX_BYTES_REPLY is accepted here because only the send call refuses it`() {
        val cap = CallLimits.MAX_BYTES_REPLY + 1L
        assertEquals(cap, CallLimits(connectTimeoutMs = 1, readTimeoutMs = 1, maxReplyBytes = cap).maxReplyBytes)
    }

    @Test
    fun `refusal messages are fixed and hold no digit`() {
        val timeoutLow = refusalOf { CallLimits(connectTimeoutMs = 0, readTimeoutMs = 1, maxReplyBytes = 1L) }
        val timeoutHigh = refusalOf { CallLimits(connectTimeoutMs = 600_001, readTimeoutMs = 1, maxReplyBytes = 1L) }
        val capLow = refusalOf { CallLimits(connectTimeoutMs = 1, readTimeoutMs = 1, maxReplyBytes = 0L) }
        assertEquals("a timeout is refused with the same sentence for any bad value", timeoutLow, timeoutHigh)
        assertTrue("a timeout and a cap have different sentences", timeoutLow != capLow)
        assertTrue("the timeout sentence holds no digit", timeoutLow.none { it.isDigit() })
        assertTrue("the cap sentence holds no digit", capLow.none { it.isDigit() })
    }
}
