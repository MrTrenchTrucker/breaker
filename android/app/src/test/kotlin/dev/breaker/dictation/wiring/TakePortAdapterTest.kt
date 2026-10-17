package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The adapter over the real runner and its fakes: every member must reach the runner. */
class TakePortAdapterTest {
    @Test
    fun `the session state and begin are the runner's`() {
        val rig = Rig()
        val take: TakePort = TakePortAdapter(rig.runner)
        assertEquals("app: an idle runner should read IDLE", DictationState.IDLE, take.sessionState)
        assertEquals("app: begin should answer what the runner answers", BeginResult.Recording, take.begin())
        assertEquals("app: begin should start the audio once", 1, rig.audio.starts)
        assertEquals("app: the state should follow the runner", DictationState.RECORDING, take.sessionState)
    }

    @Test
    fun `finish keeps the whole take and answers the text`() {
        val rig = Rig()
        val take: TakePort = TakePortAdapter(rig.runner)
        take.begin()
        rig.speak()
        val result = take.finish()
        assertTrue("app: finish over speech should answer ReadyToSend, got $result", result is FinishResult.ReadyToSend)
        assertEquals("app: finish should answer the engine's text", "hello world", (result as FinishResult.ReadyToSend).transcription.text)
        assertEquals("app: finish should leave the text waiting", DictationState.SENDING, take.sessionState)
    }

    @Test
    fun `send commits once through the runner and answers its result`() {
        val committer = FakeCommitter()
        val rig = Rig(committer = committer)
        val take: TakePort = TakePortAdapter(rig.runner)
        take.begin()
        rig.speak()
        take.finish()
        val sent = take.send()
        assertNotNull("app: send with a waiting text should answer a result", sent)
        assertEquals("app: send should answer the commit outcome", CommitOutcome.COMMITTED, sent!!.outcome.outcome)
        assertEquals("app: send should commit exactly once", 1, committer.commits)
        assertEquals("app: send should save to history once", 1, rig.history.saved.size)
        assertEquals("app: the dictation should be over after the send", DictationState.IDLE, take.sessionState)
    }

    @Test
    fun `send with nothing waiting answers null`() {
        val rig = Rig()
        assertNull("app: send without a take should answer null", TakePortAdapter(rig.runner).send())
    }

    @Test
    fun `cancel drops the take`() {
        val rig = Rig()
        val take: TakePort = TakePortAdapter(rig.runner)
        take.begin()
        take.cancel()
        assertEquals("app: cancel should leave the session idle", DictationState.IDLE, take.sessionState)
        assertEquals("app: cancel should stop the audio once", 1, rig.audio.stops)
        assertEquals("app: a begin after a cancel should record again", BeginResult.Recording, take.begin())
    }

    @Test
    fun `a refused begin is passed through and not turned into recording`() {
        val rig = Rig()
        val take: TakePort = TakePortAdapter(rig.runner)
        take.begin()
        assertEquals(
            "app: a begin while recording should answer the runner's refusal",
            BeginResult.Refused(RunnerSentences.BUSY),
            take.begin(),
        )
        assertEquals("app: a refused begin must not start the audio again", 1, rig.audio.starts)
    }

    @Test
    fun `a failed begin is passed through with its sentence`() {
        val rig = Rig()
        rig.audio.startError = IllegalStateException("the microphone failed")
        assertEquals(
            "app: a begin whose audio cannot start should answer the runner's failure",
            BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD),
            TakePortAdapter(rig.runner).begin(),
        )
    }

    @Test
    fun `a failed finish is passed through with its sentence`() {
        val rig = Rig()
        assertEquals(
            "app: a finish with nothing to finish should answer the runner's failure",
            FinishResult.Failed(RunnerSentences.NOT_LISTENING),
            TakePortAdapter(rig.runner).finish(),
        )
    }
}
