package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicCapture
import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.usecase.DictateUseCase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnavailableSlotsTest {
    private val localSlot = UnavailableSttEngine(SttError.LOCAL_MODEL_MISSING, STAND_IN_LOCAL_DETAIL)
    private val serverSlot = UnavailableSttEngine(SttError.OTHER, SERVER_UNAVAILABLE_DETAIL)

    private fun request(samples: Int = 160, model: String = "small") =
        SttRequest(pcm = FloatArray(samples) { 0.1f }, wavBytes = byteArrayOf(1), model = model, language = "en")

    private fun dictateIn(mode: SttMode, local: SttEngine, server: SttEngine, probe: FakeProbe = FakeProbe()): DictationResult =
        DictateUseCase(
            settings = FakeSettingsStore(AppSettings(mode = mode)),
            probe = probe,
            localEngine = local,
            serverEngine = server,
            serverFormatter = CountingFormatter(),
            wavEncoder = FixedWavEncoder(),
            clock = fixedClock,
            ids = SequenceIds(),
            localFormatter = CountingFormatter(),
        ).dictate(DictationSession().arm().startRecording(), speech())

    @Test
    fun `the sentences the slots give are the plain words the user is shown`() {
        assertEquals("app: the server slot sentence changed", "Server transcription is not available yet.", SERVER_UNAVAILABLE_DETAIL)
        assertEquals("app: the microphone slot sentence changed", "The microphone is not available yet.", MIC_UNAVAILABLE_MESSAGE)
    }

    @Test
    fun `an engine slot answers its failure whatever it is asked and never a success`() {
        for (asked in listOf(request(), request(samples = 3_200, model = "large"))) {
            assertEquals(
                "app: the on-device slot should answer its own failure for any request",
                SttResult.Failure(SttError.LOCAL_MODEL_MISSING, STAND_IN_LOCAL_DETAIL),
                localSlot.transcribe(asked),
            )
            assertEquals(
                "app: the server slot should answer its own failure for any request",
                SttResult.Failure(SttError.OTHER, SERVER_UNAVAILABLE_DETAIL),
                serverSlot.transcribe(asked),
            )
        }
        assertFalse("app: the on-device slot must never succeed", localSlot.transcribe(request()).isSuccess)
        assertFalse("app: the server slot must never succeed", serverSlot.transcribe(request()).isSuccess)
    }

    @Test
    fun `the on-device slot ends a local dictation without trying the server or the probe`() {
        val server = FakeSttEngine(SttResult.Success("never"))
        val probe = FakeProbe()
        val result = dictateIn(SttMode.LOCAL, localSlot, server, probe)
        assertTrue("app: a local dictation over the slot should fail, got $result", result is DictationResult.Failure)
        val failure = result as DictationResult.Failure
        assertEquals("app: the failure should be the no-fallback error", SttError.LOCAL_MODEL_MISSING, failure.error)
        assertEquals("app: the failure should carry the slot's sentence", STAND_IN_LOCAL_DETAIL, failure.detail)
        assertEquals("app: a missing local model must not reach the server engine", 0, server.calls)
        assertEquals("app: a local dictation must not ask the probe", 0, probe.asks)
    }

    @Test
    fun `automatic routing with the server out of reach lands on the on-device slot and stops there`() {
        val server = FakeSttEngine(SttResult.Success("never"))
        val result = dictateIn(SttMode.AUTO, localSlot, server, FakeProbe(reachable = false))
        assertTrue("app: automatic routing over the slot should fail, got $result", result is DictationResult.Failure)
        assertEquals("app: the failure should be the no-fallback error", SttError.LOCAL_MODEL_MISSING, (result as DictationResult.Failure).error)
        assertEquals("app: automatic routing must not fall through to the server engine", 0, server.calls)
    }

    @Test
    fun `the server slot ends a server dictation with its sentence and leaves the phone engine alone`() {
        val local = FakeSttEngine(SttResult.Success("never"))
        val result = dictateIn(SttMode.SERVER, local, serverSlot)
        assertTrue("app: a server dictation over the slot should fail, got $result", result is DictationResult.Failure)
        val failure = result as DictationResult.Failure
        assertEquals("app: the server slot error should be OTHER", SttError.OTHER, failure.error)
        assertEquals("app: the failure should carry the server slot's sentence", SERVER_UNAVAILABLE_DETAIL, failure.detail)
        assertEquals("app: a server dictation must not reach the on-device engine", 0, local.calls)
    }

    @Test
    fun `the microphone slot cannot be opened, reads an error code and closes harmlessly`() {
        val slot = UnavailableMicSource()
        assertEquals("app: the microphone slot should be 16 kHz", 16_000, slot.sampleRateHz)
        assertEquals("app: the microphone slot should be mono", 1, slot.channelCount)
        val thrown = assertThrows(MicSourceException::class.java) { slot.open() }
        assertEquals("app: opening the slot should say why", "The microphone is not available yet.", thrown.message)
        assertEquals("app: reading the slot should give the error code", -1, slot.read(ShortArray(8), 0, 8))
        slot.close()
        slot.close()
    }

    @Test
    fun `a capture over the microphone slot fails at the start and never records`() {
        val capture = MicCapture(UnavailableMicSource())
        assertThrows(MicSourceException::class.java) { capture.start(AudioListener { }) }
        assertFalse("app: a capture that failed to open must not report capturing", capture.isCapturing)
    }

    @Test
    fun `the id source mints a valid unique id each time`() {
        val ids = UuidIdSource()
        val first = ids.newId()
        val second = ids.newId()
        assertEquals("app: the id should read back as the same UUID", first, UUID.fromString(first).toString())
        assertNotEquals("app: two ids should differ", first, second)
    }
}
