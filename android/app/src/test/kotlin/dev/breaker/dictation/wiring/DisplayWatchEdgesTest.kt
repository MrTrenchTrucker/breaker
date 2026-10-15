package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

internal class DisplayWatchEdgesTest {

    @Test
    fun `another display id calls onChanged never and asks for no snapshot`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.answer(DISPLAY_TURNED)
        val asked: Int = port.snapshotCalls
        port.fire(DEFAULT_DISPLAY_ID + 1)
        assertEquals("app: a callback for another display must not call onChanged", 0, changes)
        assertEquals("app: a callback for another display must not read the snapshot", asked, port.snapshotCalls)
    }

    @Test
    fun `a callback after stop calls onChanged never`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        watch.stop()
        port.answer(DISPLAY_TURNED)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a stopped watch must not call onChanged even when the port still calls", 0, changes)
    }

    @Test
    fun `a restart takes a fresh baseline and a change made while stopped is not forwarded`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        watch.stop()
        port.answer(DISPLAY_TURNED)
        watch.start()
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: the fresh baseline must not forward the change made while stopped", 0, changes)
        port.answer(DisplaySnapshot(rotation = 2, physicalWidthPx = 1080, physicalHeightPx = 2400))
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a callback with another snapshot after the restart must call onChanged once", 1, changes)
    }

    @Test
    fun `a refused start leaves the watch stopped and a callback then calls nothing`() {
        val port = FakeDisplayPort()
        port.allowStart = false
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.answer(DISPLAY_TURNED)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a callback after a refused start must not call onChanged", 0, changes)
        watch.stop()
        assertEquals("app: a stop after a refused start must not reach the port", 0, port.stopCalls)
    }

    @Test
    fun `the next start after a refused start registers the port again`() {
        val port = FakeDisplayPort()
        port.allowStart = false
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.allowStart = true
        watch.start()
        assertEquals("app: the next start after a refusal must register the port again", 2, port.startCalls)
        port.answer(DISPLAY_TURNED)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: the retried watch must forward the change once", 1, changes)
    }

    @Test
    fun `an unreadable display at a callback is forwarded and the next readable one differs`() {
        val port = FakeDisplayPort()
        port.script.addAll(listOf(DISPLAY_BASE, null, DISPLAY_BASE))
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: an unreadable display must be forwarded once", 1, changes)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a readable display after an unreadable one must differ and be forwarded", 2, changes)
    }

    @Test
    fun `an unreadable display after an unreadable one is forwarded each time`() {
        val port = FakeDisplayPort()
        port.answer(null)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: an unreadable display after an unreadable baseline must call onChanged once", 1, changes)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a second unreadable display in a row must call onChanged again", 2, changes)
    }

    @Test
    fun `a throwing onChanged does not escape the callback`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var calls = 0
        val watch = DisplayWatch(port) {
            calls += 1
            throw RuntimeException("the tile refused")
        }
        watch.start()
        port.answer(DISPLAY_TURNED)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: the throwing onChanged must be called once", 1, calls)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: the new snapshot must be kept after the throw", 1, calls)
    }

    @Test
    fun `a throwing snapshot at start is read as unreadable and a readable one after it differs`() {
        val port = FakeDisplayPort()
        port.snapshotError = RuntimeException("the display refused")
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        assertEquals("app: a throwing snapshot must not stop the start", 1, port.startCalls)
        port.snapshotError = null
        port.answer(DISPLAY_BASE)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a readable snapshot after an unreadable baseline must be forwarded", 1, changes)
    }

    @Test
    fun `a throwing snapshot at a callback is read as unreadable and forwarded`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.snapshotError = RuntimeException("the display refused")
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a callback whose snapshot throws must be forwarded once", 1, changes)
    }

    @Test
    fun `a throwing port start does not escape and leaves the watch stopped`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        port.startError = RuntimeException("the platform refused")
        val watch = DisplayWatch(port) { }
        watch.start()
        assertEquals("app: the throwing start must still have been asked", 1, port.startCalls)
        watch.stop()
        assertEquals("app: a stop after a throwing start must not reach the port", 0, port.stopCalls)
    }

    @Test
    fun `a throwing port stop does not escape and leaves the watch stopped`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.stopError = RuntimeException("the platform refused")
        watch.stop()
        assertEquals("app: the throwing stop must still have been asked once", 1, port.stopCalls)
        port.answer(DISPLAY_TURNED)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a callback after a stop that threw must call onChanged never", 0, changes)
    }

    @Test
    fun `a display change made while the port registers is forwarded by the first callback`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        port.onRegister = { port.answer(DISPLAY_TURNED) }
        watch.start()
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a change made during the registration must reach onChanged", 1, changes)
    }
}
