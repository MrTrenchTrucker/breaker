package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.core.model.DictationState
import org.junit.Assert.assertEquals
import org.junit.Test

class DictationComponentTakenHookTest {
    @Test
    fun `a taken microphone ends its take through the take hook while the session still records`() {
        // The cut now lands in the take-end hook, on the dispatch thread; at that moment the
        // runner is still recording, and the run went through the taken hook, not the else path.
        var stateAtHook: DictationState? = null
        var micTakenCount = 0
        var takeEndedCount = 0
        val mic = ScriptedMic()
        mic.readError = MicSourceException("another app took the microphone", null, MicSourceException.Reason.MICROPHONE_TAKEN)
        val builtBox = arrayOfNulls<Built>(1)
        val built = Built(
            mic = mic,
            onMicTaken = {
                stateAtHook = builtBox[0]?.runner?.sessionState
                micTakenCount += 1
            },
            onTakeEnded = { takeEndedCount += 1 },
        )
        builtBox[0] = built
        assertEquals("app: the capture should start", BeginResult.Recording, built.runner.begin())
        built.awaitCaptureThreadEnd()
        assertEquals("app: a taken take must run the taken hook once", 1, micTakenCount)
        assertEquals("app: the taken hook must fire while the session still records", DictationState.RECORDING, stateAtHook)
        assertEquals("app: the taken path does not fall through to the wrapper end", 0, takeEndedCount)
        built.component.close()
    }

    @Test
    fun `a device failure keeps the existing end, not the taken hook`() {
        // Not a taken take: the else path runs the capture-ended call and the tile end; the taken hook
        // is never reached. Same rig shape as the taken test but with no taken reason.
        var micTakenCount = 0
        var takeEndedCount = 0
        val mic = ScriptedMic()
        mic.readError = MicSourceException("the microphone stopped")
        val built = Built(
            mic = mic,
            onMicTaken = { micTakenCount += 1 },
            onTakeEnded = { takeEndedCount += 1 },
        )
        assertEquals("app: the capture should start", BeginResult.Recording, built.runner.begin())
        built.assertEndedBySelfThenPayStop()
        assertEquals("app: a device failure must not run the taken hook", 0, micTakenCount)
        assertEquals("app: the wrapper end still runs once for a device failure", 1, takeEndedCount)
        built.component.close()
    }

    @Test
    fun `a user stop does not call the taken hook`() {
        var count = 0
        val built = Built(onMicTaken = { count += 1 })
        built.listen()
        built.runner.cancel()
        awaitBounded("the capture to be closed after the cancel", built.mic.closed)
        assertEquals("app: a user stop must not run the taken hook", 0, count)
        built.assertMicWasStopped()
        built.component.close()
    }
}
