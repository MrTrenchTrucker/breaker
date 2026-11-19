package dev.breaker.dictation.training.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainingFailureTest {
    private val failed: List<Pair<TrainingResult<Unit>, TrainingFailure>> = listOf(
        TrainingResult.NotSignedIn to TrainingFailure.NOT_SIGNED_IN,
        TrainingResult.NoServer to TrainingFailure.NO_SERVER,
        TrainingResult.Refused to TrainingFailure.REFUSED,
        TrainingResult.TooLarge to TrainingFailure.TOO_LARGE,
        TrainingResult.Network to TrainingFailure.NETWORK,
        TrainingResult.Unavailable to TrainingFailure.UNAVAILABLE,
    )

    @Test
    fun `each failed result maps to its own failure code`() {
        for ((result, expected) in failed) {
            assertEquals("for $result", expected, result.failureOrNull())
        }
    }

    @Test
    fun `the six failed results map to six different codes`() {
        val codes = failed.map { (result, _) -> result.failureOrNull() }.toSet()
        assertEquals(TrainingFailure.entries.toSet(), codes)
    }

    @Test
    fun `a worked result has no failure and its value reaches the ok branch`() {
        val ok: TrainingResult<String> = TrainingResult.Ok("kept")
        assertNull(ok.failureOrNull())
        assertEquals("kept", ok.fold(onOk = { value -> value }, onFailed = { "failed" }))
    }

    @Test
    fun `a failed result reaches the failed branch and not the ok branch`() {
        val network: TrainingResult<String> = TrainingResult.Network
        assertEquals("NETWORK", network.fold(onOk = { "ok" }, onFailed = { failure -> failure.name }))
    }
}
