package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

internal class DisplayWatchTest {

    @Test
    fun `a start registers the port once and a second start registers nothing more`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        val watch = DisplayWatch(port) { }
        watch.start()
        watch.start()
        assertEquals("app: a second start must not register the port again", 1, port.startCalls)
    }

    @Test
    fun `a stop unregisters the port once`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        val watch = DisplayWatch(port) { }
        watch.start()
        watch.stop()
        assertEquals("app: a stop must unregister the port once", 1, port.stopCalls)
    }

    @Test
    fun `a stop with no start unregisters nothing`() {
        val port = FakeDisplayPort()
        val watch = DisplayWatch(port) { }
        watch.stop()
        assertEquals("app: a stop before any start must not reach the port", 0, port.stopCalls)
    }

    @Test
    fun `a second stop unregisters nothing more`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        val watch = DisplayWatch(port) { }
        watch.start()
        watch.stop()
        watch.stop()
        assertEquals("app: a second stop must not unregister again", 1, port.stopCalls)
    }

    @Test
    fun `one callback for the default display with a new snapshot calls onChanged once`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.answer(DISPLAY_TURNED)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: one changed callback must call onChanged once", 1, changes)
    }

    @Test
    fun `a callback whose snapshot equals the baseline calls onChanged never`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: an unchanged display must not call onChanged", 0, changes)
    }

    @Test
    fun `a refresh rate switch gives the same snapshot and no call`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        // The mode moves from 60 Hz to 120 Hz. The refresh rate is not a field of the snapshot,
        // so the port answers the same rotation and the same physical size.
        port.answer(DisplaySnapshot(rotation = 0, physicalWidthPx = 1080, physicalHeightPx = 2400))
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a refresh rate switch must not call onChanged", 0, changes)
    }

    @Test
    fun `a rotation change with the size the same calls onChanged once`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.answer(DisplaySnapshot(rotation = 3, physicalWidthPx = 1080, physicalHeightPx = 2400))
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a rotation change must call onChanged once", 1, changes)
    }

    @Test
    fun `a width change with the rest the same calls onChanged once`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.answer(DisplaySnapshot(rotation = 0, physicalWidthPx = 1200, physicalHeightPx = 2400))
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a width change must call onChanged once", 1, changes)
    }

    @Test
    fun `a height change with the rest the same calls onChanged once`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.answer(DisplaySnapshot(rotation = 0, physicalWidthPx = 1080, physicalHeightPx = 2000))
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: a height change must call onChanged once", 1, changes)
    }

    @Test
    fun `after one forwarded change a second callback with that same new snapshot calls onChanged no more`() {
        val port = FakeDisplayPort()
        port.answer(DISPLAY_BASE)
        var changes = 0
        val watch = DisplayWatch(port) { changes += 1 }
        watch.start()
        port.answer(DISPLAY_TURNED)
        port.fire(DEFAULT_DISPLAY_ID)
        port.fire(DEFAULT_DISPLAY_ID)
        assertEquals("app: the same new snapshot must not be forwarded twice", 1, changes)
    }

    @Test
    fun `the default display id is zero`() {
        assertEquals("app: the default display id must be the platform value zero", 0, DEFAULT_DISPLAY_ID)
    }
}
