package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.service.DictationServiceController
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records each level it is handed, with the thread that handed it over, and completes on the first one. */
private class RecordingLevelSink : LevelSink {
    val levels = CopyOnWriteArrayList<Float>()
    val threads = CopyOnWriteArrayList<Thread>()
    val first = CompletableDeferred<Float>()

    override fun publish(level: Float) {
        levels.add(level)
        threads.add(Thread.currentThread())
        first.complete(level)
    }
}

internal class DictationComponentLevelTest {

    @Test
    fun `a take publishes a level from the dispatch thread and not from the test thread`() {
        val sink = RecordingLevelSink()
        val mic = ScriptedMic()
        val controller = DictationServiceController(SwitchPermission(true), RecordingLauncher())
        val component = DictationComponent(
            settings = FakeSettingsStore(AppSettings(mode = SttMode.LOCAL)),
            history = RecordingHistoryStore(),
            clock = fixedClock,
            ids = SequenceIds(),
            probe = FakeProbe(),
            localEngine = FakeSttEngine(SttResult.Success("hello world")),
            serverEngine = FakeSttEngine(SttResult.Success("from the server")),
            localFormatter = CountingFormatter(),
            serverFormatter = CountingFormatter(),
            wavEncoder = FixedWavEncoder(),
            committer = FakeCommitter(),
            micSource = mic,
            controller = controller,
            levelSink = sink,
        )
        controller.adopt()
        assertEquals("app: the scripted take should start", BeginResult.Recording, component.runner.begin())
        awaitBounded("the capture to read twice", mic.secondReadEntered)
        component.runner.cancel()

        val level = awaitBounded("a level from the take", sink.first)
        assertTrue("app: the level was $level, expected above 0 and at most 1", level > 0f && level <= 1f)
        assertNotSame("app: the level was handed over on the test thread", Thread.currentThread(), sink.threads.first())

        val capture = awaitBounded("the capture thread to read", mic.firstReadThread)
        capture.join(5_000L)
        assertFalse("app: the capture thread did not end", capture.isAlive)
        component.close()
    }
}
