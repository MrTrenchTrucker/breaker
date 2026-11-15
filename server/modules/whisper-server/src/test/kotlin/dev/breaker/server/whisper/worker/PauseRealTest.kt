package dev.breaker.server.whisper.worker

import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

internal class PauseRealTest {

    @Test
    fun `the real pause waits for the time it is given`(): Unit = runBlocking {
        val before = System.nanoTime()
        Pause.REAL.pause(Duration.ofMillis(150))
        val waitedMs = (System.nanoTime() - before) / 1_000_000
        assertTrue(
            "whisper-server: the real pause returned after $waitedMs ms, before the 150 ms asked for",
            waitedMs >= 120,
        )
    }
}
