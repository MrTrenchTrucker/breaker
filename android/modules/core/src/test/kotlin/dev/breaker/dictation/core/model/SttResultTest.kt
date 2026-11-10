package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SttResult: Failure carries retryAfterSeconds (source compatible, default null). */
class SttResultTest {

    private val sentence = "the server is not taking work"

    private fun failure(retryAfterSeconds: Int?): SttResult.Failure =
        SttResult.failure(SttError.SERVER_UNREACHABLE, sentence, retryAfterSeconds) as SttResult.Failure

    @Test
    fun `Failure defaults retryAfterSeconds to null`() {
        val plain = SttResult.failure(SttError.SERVER_UNREACHABLE, sentence) as SttResult.Failure
        assertNull(plain.retryAfterSeconds)
        assertNull(SttResult.Failure(SttError.OTHER, sentence).retryAfterSeconds)
    }

    @Test
    fun `Failure carries retryAfterSeconds when given`() {
        assertEquals(30, failure(30).retryAfterSeconds)
    }

    @Test
    fun `Failure carries 3600 as given since the core holds the value and the engine bounds it`() {
        assertEquals(3600, failure(3600).retryAfterSeconds)
    }

    @Test
    fun `Failure carries null explicitly`() {
        assertNull(failure(null).retryAfterSeconds)
    }

    @Test
    fun `failure detail is unchanged by retryAfterSeconds`() {
        assertEquals(failure(null).detail, failure(30).detail)
        assertEquals(sentence, failure(30).detail)
    }

    @Test
    fun `two failures that differ only in retryAfterSeconds are not equal`() {
        assertEquals(failure(30), failure(30))
        assertEquals(false, failure(30) == failure(null))
    }
}
