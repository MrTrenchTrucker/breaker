package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationRunnerFinishTest {
    @Test
    fun `a finish the use case refuses before it stops the audio still stops the audio and drops the dictation`() {
        val rig = Rig()
        rig.runner.begin()
        rig.speak()
        assertEquals(
            "app: a finish with a negative trim should answer the could-not-finish sentence",
            FinishResult.Failed(RunnerSentences.COULD_NOT_FINISH),
            rig.runner.finish(trimBeforeMs = -1L),
        )
        assertEquals("app: a finish the use case refused must still stop the audio once", 1, rig.audio.stops)
        assertEquals("app: a refused finish should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertNull("app: a refused finish leaves nothing to send", rig.runner.send())
        assertTrue("app: a refused finish must leave the service armed", rig.controller.isArmed)
        assertEquals("app: a refused finish must not halt the service", 0, rig.launcher.halts)
        assertEquals("app: the runner should record again after a refused finish", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the begin after a refused finish must not stop the audio again", 1, rig.audio.stops)
        assertEquals("app: the new capture should have started", 2, rig.audio.starts)
    }

    @Test
    fun `a finish the use case refuses and whose audio stop also fails answers could-not-finish and stops the audio only once`() {
        val rig = Rig()
        rig.runner.begin()
        rig.speak()
        rig.audio.stopError = IllegalStateException("stop failed")
        assertEquals(
            "app: a refused finish whose own audio stop throws should still answer the could-not-finish sentence",
            FinishResult.Failed(RunnerSentences.COULD_NOT_FINISH),
            rig.runner.finish(trimBeforeMs = -1L),
        )
        assertEquals("app: a refused finish must try the audio stop once, and the failing stop must not be swallowed twice", 1, rig.audio.stops)
        assertEquals("app: a refused finish with a failing stop should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertTrue("app: a refused finish with a failing stop must leave the service armed", rig.controller.isArmed)
        rig.audio.stopError = null
        assertEquals("app: the runner should record again after that finish", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the begin after that finish must not stop the audio again", 1, rig.audio.stops)
    }

    @Test
    fun `a finish the use case carried out up to a failing audio stop is not stopped a second time`() {
        val rig = Rig()
        rig.runner.begin()
        rig.speak()
        rig.audio.stopError = IllegalStateException("stop failed")
        assertEquals(
            "app: a finish whose audio stop throws should answer the could-not-finish sentence",
            FinishResult.Failed(RunnerSentences.COULD_NOT_FINISH),
            rig.runner.finish(),
        )
        assertEquals("app: a stop the use case already reached must not be repeated by the runner", 1, rig.audio.stops)
        assertEquals("app: a finish that failed to stop should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertTrue("app: a finish that failed to stop must leave the service armed", rig.controller.isArmed)
    }
}
