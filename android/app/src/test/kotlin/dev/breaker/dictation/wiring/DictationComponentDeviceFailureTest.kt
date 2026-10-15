package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.service.DictationServiceController
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The component over a scripted microphone, with the device failure handler given by the test (or none). */
private class DeviceComponent(mic: ScriptedMic, handler: ((() -> Unit) -> Unit)?, onEnded: () -> Unit, onTaken: () -> Unit) {
    val launcher = RecordingLauncher()
    val controller = DictationServiceController(SwitchPermission(true), launcher)
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
        onTakeEnded = onEnded,
        onMicTaken = onTaken,
        onDeviceFailure = handler,
    )

    init {
        controller.adopt()
    }
}

internal class DictationComponentDeviceFailureTest {

    @Test
    fun `a device failure with a handler hands the handler the fallback once while the session still records`() {
        val mic = ScriptedMic()
        mic.readError = MicSourceException("the microphone stopped")
        val handed = CompletableDeferred<() -> Unit>()
        var handlerCalls = 0
        var stateAtHandler: DictationState? = null
        lateinit var device: DeviceComponent
        device = DeviceComponent(
            mic,
            handler = { fallback ->
                handlerCalls += 1
                stateAtHandler = device.component.runner.sessionState
                handed.complete(fallback)
            },
            onEnded = {},
            onTaken = {},
        )
        assertEquals("device: the capture starts", BeginResult.Recording, device.component.runner.begin())
        awaitBounded("the device failure handler", handed)
        assertEquals("device: the handler is called once", 1, handlerCalls)
        assertEquals("device: the session still records when the handler runs", DictationState.RECORDING, stateAtHandler)
        device.component.close()
    }

    @Test
    fun `a device failure with a handler runs the end only when the fallback is called`() {
        val mic = ScriptedMic()
        mic.readError = MicSourceException("the microphone stopped")
        val handed = CompletableDeferred<() -> Unit>()
        var endedCount = 0
        val device = DeviceComponent(mic, handler = { fallback -> handed.complete(fallback) }, onEnded = { endedCount += 1 }, onTaken = {})
        device.component.runner.begin()
        val fallback: () -> Unit = awaitBounded("the device failure handler", handed)
        assertEquals("device: the end does not run before the handler answers", 0, endedCount)
        fallback()
        assertEquals("device: the fallback runs the end once", 1, endedCount)
        assertEquals("device: the fallback idles the session", DictationState.IDLE, device.component.runner.sessionState)
        device.component.close()
    }

    @Test
    fun `a device failure with no handler runs the old end at once and leaves the session idle`() {
        val mic = ScriptedMic()
        mic.readError = MicSourceException("the microphone stopped")
        val ended = CompletableDeferred<Unit>()
        var endedCount = 0
        val device = DeviceComponent(mic, handler = null, onEnded = { endedCount += 1; ended.complete(Unit) }, onTaken = {})
        device.component.runner.begin()
        awaitBounded("the take end", ended)
        assertEquals("device: with no handler the end runs once", 1, endedCount)
        assertEquals("device: with no handler the session is idle", DictationState.IDLE, device.component.runner.sessionState)
        assertTrue("device: with no handler the microphone is released", mic.closed.isCompleted)
        device.component.close()
    }

    @Test
    fun `a taken microphone never reaches the device failure handler`() {
        val mic = ScriptedMic()
        mic.readError = MicSourceException("another app took the microphone", null, MicSourceException.Reason.MICROPHONE_TAKEN)
        val taken = CompletableDeferred<Unit>()
        var handlerCalls = 0
        val device = DeviceComponent(mic, handler = { handlerCalls += 1 }, onEnded = {}, onTaken = { taken.complete(Unit) })
        device.component.runner.begin()
        awaitBounded("the taken hook", taken)
        assertEquals("device: a taken microphone never calls the device failure handler", 0, handlerCalls)
        device.component.close()
    }

    @Test
    fun `a user stop never calls the device failure handler`() {
        val mic = ScriptedMic()
        var handlerCalls = 0
        val device = DeviceComponent(mic, handler = { handlerCalls += 1 }, onEnded = {}, onTaken = {})
        device.component.runner.begin()
        awaitBounded("the capture to read twice", mic.secondReadEntered)
        device.component.runner.cancel()
        awaitBounded("the capture to be closed after the cancel", mic.closed)
        assertEquals("device: a user stop never calls the device failure handler", 0, handlerCalls)
        device.component.close()
    }
}
