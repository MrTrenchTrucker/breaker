package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A trained phrase model comes from at least one sample: one is the lowest legal count. */
class TrainedPhraseModelSamplesTest {
    private fun trained(samples: Int) = TrainedPhraseModel(
        kind = PhraseKind.WAKE,
        model = PhraseModel(PhraseKind.WAKE, byteArrayOf(1), "abc"),
        samplesUsed = samples,
    )

    @Test
    fun `a model trained on one sample is accepted`() {
        assertEquals(1, trained(1).samplesUsed)
    }

    @Test
    fun `a model trained on zero or fewer samples is refused`() {
        assertThrows(IllegalArgumentException::class.java) { trained(0) }
        assertThrows(IllegalArgumentException::class.java) { trained(-1) }
    }
}
