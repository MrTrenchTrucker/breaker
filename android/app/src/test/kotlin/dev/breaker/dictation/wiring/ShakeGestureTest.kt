package dev.breaker.dictation.wiring

import dev.breaker.dictation.gesture.ShakePort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** One accelerometer reading as the port delivers it: the three axes in m/s^2 and the time in milliseconds. */
internal data class Reading(val x: Float, val y: Float, val z: Float, val t: Long)

/**
 * One shake as the phone reports it: 50 Hz, 450 ms of an 11 Hz swing of 4.5 g on a 1.2 g baseline along x.
 * [restMs] puts a steady 1.2 g lead-in before the swing, so the filter settles at rest first; [offsetMs] moves
 * every timestamp later. The amplitude is above the 3.5 g of the gesture module's test: at 3.5 g a second shake
 * after a gap does not fire, because the filter's state carries across the gap.
 */
internal fun shakeReadings(offsetMs: Long = 0L, restMs: Long = 0L): List<Reading> {
    val dt = 1.0 / 50.0
    val restCount = Math.ceil(restMs / 1000.0 / dt).toInt()
    val shift = Math.floor(restCount * dt * 1000 + 0.5).toLong()
    val rest = (0 until restCount).map { j ->
        Reading(
            x = (1.2 * 9.81).toFloat(),
            y = 0f,
            z = 0f,
            t = Math.floor(j * dt * 1000 + 0.5).toLong() + offsetMs,
        )
    }
    val count = Math.ceil(0.450 / dt).toInt()
    val swing = (0 until count).map { i ->
        Reading(
            x = ((1.2 + 4.5 * sin(2.0 * PI * 11.0 * i * dt)) * 9.81).toFloat(),
            y = 0f,
            z = 0f,
            t = Math.floor(i * dt * 1000 + 0.5).toLong() + shift + offsetMs,
        )
    }
    return rest + swing
}

/**
 * A sensor port that counts its registrations and can refuse them. It keeps the sample callback it was
 * given, even after a stop, so a test can feed it afterwards and see that the gesture ignores the feed.
 */
internal class FakeShakePort : ShakePort {
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var allowStart: Boolean = true
    var live: Boolean = false
        private set
    private var onSample: ((Float, Float, Float, Long) -> Unit)? = null

    override fun start(onSample: (Float, Float, Float, Long) -> Unit): Boolean {
        startCalls += 1
        this.onSample = onSample
        live = allowStart
        return allowStart
    }

    override fun stop() {
        stopCalls += 1
        live = false
    }

    /** Delivers one reading to the callback the gesture gave the port; nothing happens if the port never got one. */
    fun feed(reading: Reading) {
        onSample?.invoke(reading.x, reading.y, reading.z, reading.t)
    }
}

internal class ShakeGestureTest {

    @Test
    fun `building the gesture registers nothing`() {
        val port = FakeShakePort()
        ShakeGesture(port)
        assertEquals("app: building the gesture must not register the sensor", 0, port.startCalls)
    }

    @Test
    fun `a start registers the port once and a second start registers nothing more`() {
        val port = FakeShakePort()
        val gesture = ShakeGesture(port)
        gesture.start { }
        gesture.start { }
        assertEquals("app: a start must register the port once", 1, port.startCalls)
        assertTrue("app: the port must be live after a start", port.live)
    }

    @Test
    fun `a stop unregisters the port once`() {
        val port = FakeShakePort()
        val gesture = ShakeGesture(port)
        gesture.start { }
        gesture.stop()
        assertEquals("app: a stop must unregister the port once", 1, port.stopCalls)
        assertFalse("app: the port must not be live after a stop", port.live)
    }

    @Test
    fun `a stop before any start is safe and unregisters nothing`() {
        val port = FakeShakePort()
        val gesture = ShakeGesture(port)
        gesture.stop()
        gesture.stop()
        assertEquals("app: a stop before a start must not reach the port", 0, port.stopCalls)
        assertEquals("app: a stop before a start must not register", 0, port.startCalls)
    }

    @Test
    fun `start, stop and start again registers twice`() {
        val port = FakeShakePort()
        val gesture = ShakeGesture(port)
        gesture.start { }
        gesture.stop()
        gesture.start { }
        assertEquals("app: a start after a stop must register again", 2, port.startCalls)
        assertEquals("app: the stop in between must unregister once", 1, port.stopCalls)
    }

    @Test
    fun `a shake fed while started calls the trigger once`() {
        val port = FakeShakePort()
        var triggers = 0
        ShakeGesture(port).start { triggers += 1 }
        for (reading in shakeReadings()) port.feed(reading)
        assertEquals("app: one shake must call the trigger once", 1, triggers)
    }

    @Test
    fun `a shake fed after stop does not call the trigger`() {
        val port = FakeShakePort()
        var triggers = 0
        val gesture = ShakeGesture(port)
        gesture.start { triggers += 1 }
        gesture.stop()
        for (reading in shakeReadings()) port.feed(reading)
        assertEquals("app: a stopped gesture must not call the trigger, even when the port still delivers", 0, triggers)
    }

    @Test
    fun `a refused start does not throw and calls no trigger`() {
        val port = FakeShakePort()
        port.allowStart = false
        var triggers = 0
        val gesture = ShakeGesture(port)
        gesture.start { triggers += 1 }
        for (reading in shakeReadings()) port.feed(reading)
        assertEquals("app: a refused start must call no trigger", 0, triggers)
        assertFalse("app: a refused start must leave the port not live", port.live)
    }

    @Test
    fun `a start after a refusal registers again and the next shake fires`() {
        val port = FakeShakePort()
        port.allowStart = false
        var triggers = 0
        val gesture = ShakeGesture(port)
        gesture.start { triggers += 1 }
        port.allowStart = true
        gesture.start { triggers += 1 }
        assertEquals("app: a start after a refusal must call the port again", 2, port.startCalls)
        for (reading in shakeReadings()) port.feed(reading)
        assertEquals("app: the retried gesture must call the trigger once for one shake", 1, triggers)
    }

    @Test
    fun `a second start with a new trigger replaces the first`() {
        val port = FakeShakePort()
        var first = 0
        var second = 0
        val gesture = ShakeGesture(port)
        gesture.start { first += 1 }
        gesture.start { second += 1 }
        assertEquals("app: the second start must not register the port again", 1, port.startCalls)
        for (reading in shakeReadings()) port.feed(reading)
        assertEquals("app: the replaced trigger must not be called", 0, first)
        assertEquals("app: the new trigger must be called once", 1, second)
    }
}
