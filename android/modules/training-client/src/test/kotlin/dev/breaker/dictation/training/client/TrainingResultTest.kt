package dev.breaker.dictation.training.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The typed result: every case is a value, and a case added later has to be handled. */
class TrainingResultTest {

    /**
     * Covers every case with its own tag. This `when` is an expression, so it
     * only compiles while it names every case: a case added to the result later
     * breaks the compile of this test until it is handled here.
     */
    private fun tag(result: TrainingResult<Unit>): String = when (result) {
        is TrainingResult.Ok<*> -> "ok"
        TrainingResult.NotSignedIn -> "not-signed-in"
        TrainingResult.NoServer -> "no-server"
        TrainingResult.Refused -> "refused"
        TrainingResult.TooLarge -> "too-large"
        TrainingResult.Network -> "network"
        TrainingResult.Unavailable -> "unavailable"
    }

    @Test
    fun `an exhaustive when over the result tags each of the seven cases`() {
        assertEquals("ok", tag(TrainingResult.Ok(Unit)))
        assertEquals("not-signed-in", tag(TrainingResult.NotSignedIn))
        assertEquals("no-server", tag(TrainingResult.NoServer))
        assertEquals("refused", tag(TrainingResult.Refused))
        assertEquals("too-large", tag(TrainingResult.TooLarge))
        assertEquals("network", tag(TrainingResult.Network))
        assertEquals("unavailable", tag(TrainingResult.Unavailable))

        val all: List<TrainingResult<Unit>> = listOf(
            TrainingResult.Ok(Unit),
            TrainingResult.NotSignedIn,
            TrainingResult.NoServer,
            TrainingResult.Refused,
            TrainingResult.TooLarge,
            TrainingResult.Network,
            TrainingResult.Unavailable,
        )
        assertEquals(7, all.map { tag(it) }.toSet().size)
    }

    @Test
    fun `each failure object is usable as a unit result and none is Ok`() {
        val failures: List<TrainingResult<Unit>> = listOf(
            TrainingResult.NotSignedIn,
            TrainingResult.NoServer,
            TrainingResult.Refused,
            TrainingResult.TooLarge,
            TrainingResult.Network,
            TrainingResult.Unavailable,
        )
        assertEquals(6, failures.toSet().size)
        assertTrue(failures.none { it is TrainingResult.Ok<*> })
    }

    @Test
    fun `an Ok result is equal by its value`() {
        assertEquals(TrainingResult.Ok(5), TrainingResult.Ok(5))
        assertNotEquals(TrainingResult.Ok(5), TrainingResult.Ok(6))
        assertEquals(5, TrainingResult.Ok(5).value)
    }
}
