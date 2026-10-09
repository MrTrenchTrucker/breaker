package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exact words of the three sentences the service controller answers with. They are shown to the
 * user as they are, so a change of wording must be a deliberate edit of this test.
 */
internal class ServiceSentencesTest {

    @Test
    fun `the missing microphone permission sentence has its agreed words`() {
        assertEquals(
            "app: the missing-permission sentence changed",
            "Breaker cannot listen yet because microphone access is off.",
            ServiceSentences.MIC_PERMISSION_MISSING,
        )
    }

    @Test
    fun `the arm refused sentence has its agreed words`() {
        assertEquals(
            "app: the arm-refused sentence changed",
            "Android did not let Breaker start listening just now.",
            ServiceSentences.ARM_REFUSED,
        )
    }

    @Test
    fun `the cold start refused sentence has its agreed words`() {
        assertEquals(
            "app: the cold-start-refused sentence changed",
            "Open Breaker once to switch dictation on.",
            ServiceSentences.COLD_START_REFUSED,
        )
    }
}
