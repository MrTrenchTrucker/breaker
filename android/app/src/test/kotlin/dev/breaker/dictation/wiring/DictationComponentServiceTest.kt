package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.service.DisarmReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DictationComponentServiceTest {
    @Test
    fun `the service ending on its own while a capture runs drops the capture and stops the microphone`() {
        val built = Built()
        built.listen()
        built.controller.serviceEnded()
        assertEquals("app: the service ending should drop the dictation", DictationState.IDLE, built.runner.sessionState)
        awaitBounded("the microphone to be closed by the capture stop", built.mic.closed)
        built.assertMicWasStopped()
        assertFalse("app: the service ending should leave the controller unarmed", built.controller.isArmed)
        assertEquals("app: the service ending must not halt the service", 0, built.launcher.halts)
        built.component.close()
        assertEquals("app: closing after the service ended must not halt anything", 0, built.launcher.halts)
    }

    @Test
    fun `after close the service ending no longer reaches the runner`() {
        val built = Built()
        built.component.close()
        built.listen()
        built.controller.serviceEnded()
        assertEquals(
            "app: a closed component must not hear the service end, so the new capture goes on",
            DictationState.RECORDING,
            built.runner.sessionState,
        )
        built.runner.cancel()
        awaitBounded("the second capture to be closed", built.mic.closed)
        built.assertMicWasStopped()
    }

    @Test
    fun `switching off from the notification and then the service ending drops the capture`() {
        val built = Built()
        built.listen()
        built.controller.disarm(DisarmReason.USER_WORD)
        assertEquals("app: the switch-off should halt the service once", 1, built.launcher.halts)
        assertEquals(
            "app: the switch-off alone leaves the capture to the service end that follows",
            DictationState.RECORDING,
            built.runner.sessionState,
        )
        built.controller.serviceEnded()
        assertEquals("app: the service end after a switch-off should drop the capture", DictationState.IDLE, built.runner.sessionState)
        awaitBounded("the microphone to be closed after the service ended", built.mic.closed)
        built.assertMicWasStopped()
        assertEquals("app: the service end must not halt the service again", 1, built.launcher.halts)
        assertFalse("app: the switch-off should leave the controller unarmed", built.controller.isArmed)
    }
}
