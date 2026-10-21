package dev.breaker.dictation.stt.server

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbortHandleTest {

    @Test
    fun `abort runs the registered action once`() {
        val runs = AtomicInteger(0)
        val handle = AbortHandle()
        handle.onAbort { runs.incrementAndGet() }
        assertEquals(0, runs.get())
        handle.abort()
        assertEquals(1, runs.get())
    }

    @Test
    fun `a second abort does nothing`() {
        val runs = AtomicInteger(0)
        val handle = AbortHandle()
        handle.onAbort { runs.incrementAndGet() }
        handle.abort()
        handle.abort()
        assertEquals(1, runs.get())
    }

    @Test
    fun `onAbort after abort runs at once and only once`() {
        val runs = AtomicInteger(0)
        val handle = AbortHandle()
        handle.abort()
        handle.onAbort { runs.incrementAndGet() }
        assertEquals(1, runs.get())
        handle.abort()
        assertEquals(1, runs.get())
    }

    @Test
    fun `isAborted is false before abort and true after it`() {
        val handle = AbortHandle()
        assertFalse(handle.isAborted)
        handle.abort()
        assertTrue(handle.isAborted)
    }
}
