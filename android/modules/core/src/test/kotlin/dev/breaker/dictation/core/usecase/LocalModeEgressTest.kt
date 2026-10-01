package dev.breaker.dictation.core.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The egress rule as a pure function: the transcript of an on-device dictation
 * never reaches a cloud formatter, whatever else is switched on.
 */
class LocalModeEgressTest {
    @Test
    fun `an on-device dictation never allows the cloud`() {
        assertFalse(
            "on-device is the privacy boundary; cloud formatting must stay off",
            LocalModeEgress.mayUseCloud(onDevice = true),
        )
    }

    @Test
    fun `a server dictation allows the cloud`() {
        assertTrue(
            "the server path is the cloud path and must stay reachable",
            LocalModeEgress.mayUseCloud(onDevice = false),
        )
    }

    @Test
    fun `formatting switched on does not hand a phone transcript to the cloud formatter`() {
        // The state a privacy-motivated switch to the phone leaves behind: the
        // formatting preference is still on, the engine is now on-device.
        assertFalse(LocalModeEgress.mayCleanUpTranscript(onDevice = true, formattingEnabled = true))
    }

    @Test
    fun `formatting switched on reaches the cloud formatter on the server path`() {
        assertTrue(LocalModeEgress.mayCleanUpTranscript(onDevice = false, formattingEnabled = true))
    }

    @Test
    fun `formatting switched off never reaches the cloud formatter`() {
        assertFalse(LocalModeEgress.mayCleanUpTranscript(onDevice = false, formattingEnabled = false))
        assertFalse(LocalModeEgress.mayCleanUpTranscript(onDevice = true, formattingEnabled = false))
    }

    @Test
    fun `no formatting preference lets a phone transcript reach the cloud`() {
        for (formattingEnabled in listOf(false, true)) {
            assertFalse(
                "on-device allowed cloud formatting with formattingEnabled=$formattingEnabled",
                LocalModeEgress.mayUseCloud(onDevice = true),
            )
            assertFalse(
                "on-device sent the transcript to a cloud formatter with formattingEnabled=$formattingEnabled",
                LocalModeEgress.mayCleanUpTranscript(onDevice = true, formattingEnabled = formattingEnabled),
            )
        }
    }

    @Test
    fun `the full truth table is written out`() {
        // (onDevice, formattingEnabled) -> may the transcript go to a cloud formatter
        val expected = mapOf(
            (false to false) to false,
            (false to true) to true,
            (true to false) to false,
            (true to true) to false,
        )

        expected.forEach { (input, allowed) ->
            assertEquals(
                "onDevice=${input.first}, formattingEnabled=${input.second}",
                allowed,
                LocalModeEgress.mayCleanUpTranscript(onDevice = input.first, formattingEnabled = input.second),
            )
        }
    }
}
