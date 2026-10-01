package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The mic indicator: that a screen bound to it never shows "not recording"
 * while the microphone is open, and never shows "recording" once it is shut.
 */
class MicCaptureIndicatorTest {

    @Test
    fun `the indicator is live for the whole capture and dark once it ends`() {
        val indicator = RecordingIndicator()
        val source = FakeMicSource(script = silenceThenSpeech(totalMs = 400))
        val capture = MicCapture(source = source, indicator = indicator)
        // Starts true and is cleared by any frame delivered while the
        // indicator is dark, so the assertion below fails if the indicator
        // was ever wrong during the capture.
        val liveDuringCapture = AtomicBoolean(true)
        val framesDelivered = AtomicBoolean(false)

        assertFalse("the indicator was live before any capture", indicator.isRecording)
        capture.start(AudioListener {
            framesDelivered.set(true)
            if (!indicator.isRecording) liveDuringCapture.set(false)
        })
        val wasLiveAtStart = indicator.isRecording
        // Wait for a frame to actually arrive before stopping. start() returns
        // immediately and the frame comes off the capture thread, so stopping
        // in the same breath can tear the session down before any frame is
        // ever delivered - and the check that a frame WAS delivered would then
        // be reporting the teardown race rather than the indicator.
        val firstFrameBy = System.currentTimeMillis() + 10_000
        while (!framesDelivered.get() && System.currentTimeMillis() < firstFrameBy) {
            Thread.sleep(2)
        }
        capture.stop()

        assertTrue(
            "no frame reached the listener before stop(), so this capture proved " +
                "nothing about the indicator being live WHILE audio was flowing",
            framesDelivered.get(),
        )
        assertTrue("the indicator was not live at the start of a capture", wasLiveAtStart)
        assertTrue(
            "the indicator was dark while audio was being delivered",
            liveDuringCapture.get(),
        )
        assertFalse("the indicator stayed live after stop()", indicator.isRecording)
    }

    @Test
    fun `the indicator goes live before the device is read`() {
        // Otherwise there is a window where the microphone is open and the
        // screen says nothing, which is exactly the abuse the indicator is for.
        val indicator = RecordingIndicator()
        var liveWhenDeviceOpened: Boolean? = null
        val source = FakeMicSource(script = speech(320)).apply {
            onOpen = { liveWhenDeviceOpened = indicator.isRecording }
        }
        val capture = MicCapture(source = source, indicator = indicator)
        try {
            capture.start(AudioListener { })
        } finally {
            capture.stop()
        }
        assertEquals(
            "the indicator was still dark when the device was opened; it must go " +
                "live first or there is a window where the mic is open and unannounced",
            true,
            liveWhenDeviceOpened,
        )
    }

    @Test
    fun `a microphone that cannot be opened never shows a recording indicator`() {
        val indicator = RecordingIndicator()
        val source = FakeMicSource(script = speech(320)).apply {
            openFailure = MicSourceException("no permission")
        }
        val capture = MicCapture(source = source, indicator = indicator)
        try {
            capture.start(AudioListener { })
            fail("expected a microphone that cannot be opened to fail loudly")
        } catch (e: MicSourceException) {
            assertTrue(e.message!!.contains("no permission"))
        }
        assertFalse(
            "the indicator was live after a failed open; the microphone is shut",
            indicator.isRecording,
        )
    }

    @Test
    fun `a listener added mid-capture is told the indicator is already live`() {
        val indicator = RecordingIndicator()
        val capture = MicCapture(
            source = FakeMicSource(script = silenceThenSpeech(totalMs = 400)),
            indicator = indicator,
        )
        capture.start(AudioListener { })
        try {
            var reported: Boolean? = null
            indicator.addListener { reported = it }
            assertEquals(
                "a listener that binds after capture started was not told the " +
                    "indicator is live, so the screen would show nothing",
                true,
                reported,
            )
        } finally {
            capture.stop()
        }
    }
}
