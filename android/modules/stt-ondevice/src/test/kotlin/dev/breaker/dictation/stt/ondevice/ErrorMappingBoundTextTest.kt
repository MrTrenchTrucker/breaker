package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The two failures of the decode bound: the whole user-facing sentence of each,
 * compared as one value. The sentences are literals here on purpose, so a
 * reworded sentence fails a test that names the function. Nothing here waits.
 */
class ErrorMappingBoundTextTest {

    @Test
    fun `decodeTimedOut returns exactly its sentence`() {
        assertEquals(
            "decodeTimedOut must return its exact failure",
            SttResult.Failure(SttError.OTHER, "The on-device engine took too long to transcribe the audio."),
            ErrorMapping.decodeTimedOut(),
        )
    }

    @Test
    fun `decodeBusy returns exactly its sentence`() {
        assertEquals(
            "decodeBusy must return its exact failure",
            SttResult.Failure(
                SttError.OTHER,
                "The on-device engine is still working on an earlier recording; try again in a moment.",
            ),
            ErrorMapping.decodeBusy(),
        )
    }

    @Test
    fun `the timeout, busy and decode failures are three different failures`() {
        assertNotEquals("timeout and busy must differ", ErrorMapping.decodeTimedOut(), ErrorMapping.decodeBusy())
        assertNotEquals("timeout and decode failure must differ", ErrorMapping.decodeTimedOut(), ErrorMapping.decodeFailed())
        assertNotEquals("busy and decode failure must differ", ErrorMapping.decodeBusy(), ErrorMapping.decodeFailed())
    }

    @Test
    fun `neither sentence carries a model id or a path`() {
        for (failure in listOf(ErrorMapping.decodeTimedOut(), ErrorMapping.decodeBusy())) {
            val detail = failure.detail.orEmpty()
            assertFalse("a failure sentence must not carry a path: $detail", detail.contains('/') || detail.contains('\\'))
            assertFalse("a failure sentence must not carry a model id: $detail", detail.contains("tiny"))
        }
    }
}
