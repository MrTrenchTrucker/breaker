package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Formatter
import dev.breaker.dictation.core.testing.BracketingFormatter
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Formatting in the dictation flow: which formatter runs for which route, and
 * that a cloud-capable formatter never sees a transcript from the phone.
 *
 * The route is what decides, not the mode the user picked: automatic routing
 * that falls back to the phone is on-device too, so it must behave exactly like
 * on-device mode does.
 */
class DictateUseCaseFormattingTest {
    /** A formatter that tags its output and remembers what it was given. */
    private class TaggingFormatter(private val tag: String) : Formatter {
        val inputs = mutableListOf<String>()

        override fun format(rawText: String): String {
            inputs += rawText
            return "$tag($rawText)"
        }
    }

    private class Rig(
        val useCase: DictateUseCase,
        val settings: InMemorySettingsStore,
        val probe: ScriptedConnectivityProbe,
        val cloud: TaggingFormatter,
        val onDevice: TaggingFormatter,
    )

    private fun rig(
        mode: SttMode,
        reachable: Boolean = true,
        formattingEnabled: Boolean = true,
    ): Rig {
        val settings = InMemorySettingsStore(AppSettings(mode = mode, formattingEnabled = formattingEnabled))
        val probe = ScriptedConnectivityProbe(reachable)
        val cloud = TaggingFormatter("cloud")
        val onDevice = TaggingFormatter("device")
        val useCase = DictateUseCase(
            settings = settings,
            probe = probe,
            localEngine = RecordingSttEngine.succeeding("hello world"),
            serverEngine = RecordingSttEngine.succeeding("hello world"),
            serverFormatter = cloud,
            wavEncoder = FakeWavEncoder,
            clock = FixedClock(),
            ids = SequentialIds(),
            localFormatter = onDevice,
        )
        return Rig(useCase, settings, probe, cloud, onDevice)
    }

    private fun Rig.dictate(): DictationResult.Success {
        val result = useCase.dictate(
            DictationSession().arm().startRecording(),
            FloatArray(AudioFormat.SAMPLE_RATE_HZ) { 0.1f },
        )
        assertTrue("expected a success, got $result", result is DictationResult.Success)
        return result as DictationResult.Success
    }

    // ── the server path ───────────────────────────────────────────────────

    @Test
    fun `on the server path with formatting on the cloud formatter is used once on the raw text`() {
        val rig = rig(SttMode.SERVER)

        val result = rig.dictate()

        assertEquals("cloud(hello world)", result.transcription.text)
        assertEquals(TranscriptionSource.SERVER, result.transcription.source)
        assertEquals(listOf("hello world"), rig.cloud.inputs)
        assertEquals(emptyList<String>(), rig.onDevice.inputs)
    }

    @Test
    fun `on the server path with formatting off the text is kept as dictated`() {
        val rig = rig(SttMode.SERVER, formattingEnabled = false)

        val result = rig.dictate()

        assertEquals("hello world", result.transcription.text)
        assertEquals(emptyList<String>(), rig.cloud.inputs)
        assertEquals(emptyList<String>(), rig.onDevice.inputs)
    }

    @Test
    fun `automatic routing to a reachable server formats in the cloud`() {
        val rig = rig(SttMode.AUTO, reachable = true)

        val result = rig.dictate()

        assertEquals("cloud(hello world)", result.transcription.text)
        assertEquals(listOf("hello world"), rig.cloud.inputs)
        assertEquals(emptyList<String>(), rig.onDevice.inputs)
    }

    // ── the on-device path ────────────────────────────────────────────────

    @Test
    fun `on-device mode with formatting on uses the on-device formatter and never the cloud one`() {
        // The server is reachable and formatting is still switched on from
        // before the move to the phone: the transcript must stay on the phone.
        val rig = rig(SttMode.LOCAL, reachable = true, formattingEnabled = true)

        val result = rig.dictate()

        assertEquals("device(hello world)", result.transcription.text)
        assertEquals(TranscriptionSource.LOCAL, result.transcription.source)
        assertEquals("the cloud formatter saw a phone transcript", emptyList<String>(), rig.cloud.inputs)
        assertEquals(listOf("hello world"), rig.onDevice.inputs)
    }

    @Test
    fun `on-device mode with formatting off keeps the text as dictated`() {
        val rig = rig(SttMode.LOCAL, formattingEnabled = false)

        val result = rig.dictate()

        assertEquals("hello world", result.transcription.text)
        assertEquals(emptyList<String>(), rig.cloud.inputs)
        assertEquals(emptyList<String>(), rig.onDevice.inputs)
    }

    @Test
    fun `automatic routing that falls back to the phone is on-device too`() {
        val rig = rig(SttMode.AUTO, reachable = false, formattingEnabled = true)

        val result = rig.dictate()

        assertEquals(TranscriptionSource.LOCAL, result.transcription.source)
        assertEquals("device(hello world)", result.transcription.text)
        assertEquals("the cloud formatter saw a phone transcript", emptyList<String>(), rig.cloud.inputs)
        assertEquals(listOf("hello world"), rig.onDevice.inputs)
    }

    @Test
    fun `automatic routing to the phone with formatting off keeps the text as dictated`() {
        val rig = rig(SttMode.AUTO, reachable = false, formattingEnabled = false)

        val result = rig.dictate()

        assertEquals("hello world", result.transcription.text)
        assertEquals(emptyList<String>(), rig.cloud.inputs)
        assertEquals(emptyList<String>(), rig.onDevice.inputs)
    }

    @Test
    fun `an on-device formatter that leaves the text alone keeps the phone transcript as dictated`() {
        val cloud = TaggingFormatter("cloud")
        val useCase = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
            probe = ScriptedConnectivityProbe(reachable = true),
            localEngine = RecordingSttEngine.succeeding("hello world"),
            serverEngine = RecordingSttEngine.succeeding("hello world"),
            serverFormatter = cloud,
            wavEncoder = FakeWavEncoder,
            clock = FixedClock(),
            ids = SequentialIds(),
            localFormatter = PassThroughFormatter,
        )

        val result = useCase.dictate(DictationSession().arm().startRecording(), FloatArray(160) { 0.1f })

        assertTrue(result is DictationResult.Success)
        assertEquals("hello world", (result as DictationResult.Success).transcription.text)
        assertEquals("the cloud formatter saw a phone transcript", emptyList<String>(), cloud.inputs)
    }

    // Adapted from LocalModeEgressTest in the base fork's app module (app/src/test/kotlin/dev/breaker/dictation/LocalModeEgressTest.kt)
    @Test
    fun `the server formatter is never invoked when the route is on-device`() {
        // Every way to end up on the phone, with the formatting preference both ways and a
        // reachable server, so nothing but the route can be the reason it stays away.
        val onDeviceRoutes = listOf<Pair<String, (Boolean) -> Rig>>(
            "on-device mode" to { formatting: Boolean -> rig(SttMode.LOCAL, reachable = true, formattingEnabled = formatting) },
            "automatic routing, server unreachable" to { formatting: Boolean ->
                rig(SttMode.AUTO, reachable = false, formattingEnabled = formatting)
            },
        )

        for ((label, build) in onDeviceRoutes) {
            for (formatting in listOf(true, false)) {
                val rig = build(formatting)
                val result = rig.dictate()

                assertEquals("$label, formatting=$formatting: the route", TranscriptionSource.LOCAL, result.transcription.source)
                assertEquals(
                    "$label, formatting=$formatting: the server formatter was invoked",
                    0,
                    rig.cloud.inputs.size,
                )
            }
        }

        // Control: the same spy does see a transcript that took the server route.
        val server = rig(SttMode.SERVER)
        server.dictate()
        assertEquals("the spy must be able to see a server-route transcript", 1, server.cloud.inputs.size)
    }

    @Test
    fun `the on-device formatter has no default and cannot be left out`() {
        // A Kotlin default argument compiles to an extra constructor that takes a bit mask and a
        // marker type, so if any parameter has a default that constructor exists.
        val constructors = DictateUseCase::class.java.declaredConstructors
        assertTrue(
            "expected the nine-parameter constructor, found ${constructors.map { it.parameterTypes.size }}",
            constructors.any { it.parameterTypes.size == 9 },
        )
        val withDefaults = constructors.filter { constructor ->
            constructor.parameterTypes.any { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
        }
        assertTrue(
            "a constructor parameter has a default, so it can be left out: ${withDefaults.map { it.parameterTypes.size }}",
            withDefaults.isEmpty(),
        )
    }

    // ── what the decision must not cost ───────────────────────────────────

    @Test
    fun `choosing a formatter adds no probe and no settings read`() {
        val local = rig(SttMode.LOCAL)
        local.dictate()
        assertEquals("on-device mode must not probe", 0, local.probe.callCount)
        assertEquals("settings are read once per dictation", 1, local.settings.loadCount)

        val auto = rig(SttMode.AUTO, reachable = true)
        auto.dictate()
        assertEquals("automatic routing probes once and formatting adds no second probe", 1, auto.probe.callCount)
        assertEquals(1, auto.settings.loadCount)
    }

    @Test
    fun `formatting is on unless the user turns it off`() {
        assertTrue(AppSettings().formattingEnabled)
    }

    // ── the formatter really is applied to the dictated text ──────────────

    @Test
    fun `the formatter's output becomes the transcription text, formatted exactly once, on the server path`() {
        val useCase = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.SERVER)),
            probe = ScriptedConnectivityProbe(reachable = true),
            localEngine = RecordingSttEngine.succeeding("unused"),
            serverEngine = RecordingSttEngine.succeeding("hello world"),
            serverFormatter = BracketingFormatter,
            wavEncoder = FakeWavEncoder,
            clock = FixedClock(),
            ids = SequentialIds(),
            localFormatter = PassThroughFormatter,
        )

        val result = useCase.dictate(DictationSession().arm().startRecording(), FloatArray(160) { 0.1f })

        assertEquals("[hello world]", (result as DictationResult.Success).transcription.text)
    }

    @Test
    fun `the on-device formatter's output becomes the transcription text, formatted exactly once, on the phone`() {
        val useCase = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
            probe = ScriptedConnectivityProbe(reachable = true),
            localEngine = RecordingSttEngine.succeeding("hello world"),
            serverEngine = RecordingSttEngine.succeeding("unused"),
            serverFormatter = TaggingFormatter("cloud"),
            wavEncoder = FakeWavEncoder,
            clock = FixedClock(),
            ids = SequentialIds(),
            localFormatter = BracketingFormatter,
        )

        val result = useCase.dictate(DictationSession().arm().startRecording(), FloatArray(160) { 0.1f })

        assertEquals("[hello world]", (result as DictationResult.Success).transcription.text)
    }
}
