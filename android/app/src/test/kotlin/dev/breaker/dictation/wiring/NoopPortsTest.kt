package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NoopPortsTest {
    @Test
    fun `the no-gesture slot never calls the trigger`() {
        var triggers = 0
        val gesture: GesturePort = NoGesture()
        gesture.start { triggers += 1 }
        gesture.start { triggers += 1 }
        assertEquals("app: NoGesture must never call the trigger it is given", 0, triggers)
    }

    @Test
    fun `stopping the no-gesture slot is safe before a start and twice`() {
        val gesture: GesturePort = NoGesture()
        gesture.stop()
        gesture.start { }
        gesture.stop()
        gesture.stop()
        var triggers = 0
        gesture.start { triggers += 1 }
        assertEquals("app: NoGesture must still never call the trigger after stops", 0, triggers)
    }


}
