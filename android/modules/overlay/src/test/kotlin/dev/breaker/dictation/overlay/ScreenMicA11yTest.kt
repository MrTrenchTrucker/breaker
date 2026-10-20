package dev.breaker.dictation.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScreenMicA11y: the pure decision behind the sendA11y guard in ScreenMic.kt. The JVM suite cannot build
 * a View, so the platform's enabled state arrives as a Boolean and the decision is checked here; only
 * ScreenMicView.sendA11y reads the manager and touches the platform. (a) with accessibility off nothing
 * may be sent; (b) with accessibility on an event may be sent. Every assertion message is prefixed
 * `android_overlay: ` like the neighbours.
 */
internal class ScreenMicA11yTest {

    /** A failure here means an event is allowed while accessibility is off, so the platform would throw IllegalStateException and the app would die. */
    @Test
    fun `accessibility off sends nothing`() {
        assertFalse("android_overlay: with accessibility off no screen-reader event may be sent", ScreenMicA11y.shouldSend(false))
    }

    /** A failure here means an event is refused while accessibility is on, so the mic would stop reporting to a screen reader. */
    @Test
    fun `accessibility on sends`() {
        assertTrue("android_overlay: with accessibility on a screen-reader event may be sent", ScreenMicA11y.shouldSend(true))
    }
}
