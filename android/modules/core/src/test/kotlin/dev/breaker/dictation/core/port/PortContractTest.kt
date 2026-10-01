package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.EncryptedText
import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.model.PhraseKind
import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.model.WrappedDek
import dev.breaker.dictation.core.testing.FakeTextCommitter
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemoryHistoryStore
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import dev.breaker.dictation.core.testing.aTranscription
import dev.breaker.dictation.core.usecase.DictateUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ports an adapter has to implement.
 *
 * These tests exist so the shape of every port is exercised once against a
 * hand-written adapter. If a port changes, the adapter written against the old
 * shape stops compiling here, which is the point: the domain's contract moves
 * and every implementation is forced to move with it.
 */
class PortContractTest {
    @Test
    fun `an stt engine adapter can be written against the port`() {
        val engine = RecordingSttEngine.succeeding("words")
        val result = engine.transcribe(
            SttRequest(
                pcm = FloatArray(AudioFormat.SAMPLE_RATE_HZ),
                wavBytes = FakeWavEncoder.encode(FloatArray(AudioFormat.SAMPLE_RATE_HZ)),
                model = "small",
                language = "en",
            ),
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `an audio source adapter can be written against the port`() {
        var received = 0
        val source = object : AudioSource {
            override fun start(listener: AudioListener) {
                listener.onFrame(FloatArray(16) { 0.2f })
            }

            override fun stop() {
                received++
            }
        }
        source.start { samples -> received += samples.size }
        source.stop()
        assertEquals(17, received)
        assertEquals(16_000, AudioSource.SAMPLE_RATE_HZ)
    }

    @Test
    fun `a wav encoder adapter can be written against the port`() {
        val bytes: ByteArray = FakeWavEncoder.encode(FloatArray(160))
        assertEquals("RIFF160", String(bytes, Charsets.US_ASCII))
    }

    @Test
    fun `a connectivity probe adapter can be written against the port`() {
        val probe = ScriptedConnectivityProbe(reachable = true)
        assertTrue(probe.isServerReachable())
        probe.reachable = false
        assertFalse(probe.isServerReachable())
        assertEquals(2, probe.callCount)
    }

    @Test
    fun `a text committer adapter can be written against the port`() {
        val committer = FakeTextCommitter()
        val result = committer.commit(CommitRequest("text"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `a history store adapter can be written against the port`() {
        val store = InMemoryHistoryStore()
        val t: Transcription = aTranscription()
        store.save(t)
        assertEquals(listOf(t), store.list(10))
        assertTrue(store.delete(t.id))
        assertEquals(emptyList<Transcription>(), store.list(10))
        assertFalse(store.delete(t.id))
    }

    @Test
    fun `a settings store adapter can be written against the port`() {
        val store = InMemorySettingsStore()
        assertEquals(AppSettings(), store.load())
        val changed = AppSettings(mode = dev.breaker.dictation.core.model.SttMode.LOCAL)
        store.save(changed)
        assertEquals(changed, store.load())
    }

    @Test
    fun `a formatter adapter can be written against the port`() {
        val formatter = dev.breaker.dictation.core.testing.BracketingFormatter
        assertEquals("[words]", formatter.format("words"))
    }

    @Test
    fun `a clock and an id source adapter can be written against the port`() {
        val clock: Clock = dev.breaker.dictation.core.testing.FixedClock(instant = 42L)
        val ids: IdSource = dev.breaker.dictation.core.testing.SequentialIds("x")
        assertEquals(42L, clock.nowEpochMillis())
        assertEquals("x-1", ids.newId())
        assertEquals("x-2", ids.newId())
    }

    @Test
    fun `a crypto service adapter can be written against the port`() {
        // The real implementation is the crypto module's job; this pins the
        // shape the domain expects, including the rule that the key never
        // leaves the adapter's hands.
        val service = object : CryptoService {
            private val key = DataEncryptionKey(ByteArray(32) { 1 })

            override fun unwrapDek(wrappedDek: WrappedDek, password: String): DataEncryptionKey = key
            override fun encrypt(plaintext: String, dek: DataEncryptionKey): CipherText =
                CipherText(plaintext.toByteArray(), ByteArray(12), ByteArray(16))

            override fun decrypt(cipherText: CipherText, dek: DataEncryptionKey): String =
                String(cipherText.ciphertext)

            override fun toEncryptedText(cipherText: CipherText): EncryptedText =
                EncryptedText("c", "n", "t")
        }
        val key = service.unwrapDek(WrappedDek(ByteArray(8), ByteArray(16)), "password")
        val cipher = service.encrypt("secret note", key)
        assertEquals("secret note", service.decrypt(cipher, key))
        assertEquals(EncryptedText("c", "n", "t"), service.toEncryptedText(cipher))
    }

    @Test
    fun `either engine can fill either slot of the routing and the result follows the engine`() {
        // The two engines the domain routes between are the same port type:
        // that is what lets routing swap them without the domain noticing. Put
        // each engine in each slot and check the outcome is the engine's own.
        fun dictateWith(local: SttEngine, server: SttEngine, mode: SttMode): DictationResult =
            DictateUseCase(
                settings = InMemorySettingsStore(AppSettings(mode = mode)),
                probe = ScriptedConnectivityProbe(reachable = true),
                localEngine = local,
                serverEngine = server,
                serverFormatter = PassThroughFormatter,
                wavEncoder = FakeWavEncoder,
                clock = FixedClock(),
                ids = SequentialIds(),
                localFormatter = PassThroughFormatter,
            ).dictate(DictationSession().arm().startRecording(), FloatArray(160) { 0.1f })

        val works: SttEngine = RecordingSttEngine.succeeding("a")
        val breaks: SttEngine = RecordingSttEngine.failing(SttError.TIMEOUT)

        assertTrue(dictateWith(local = works, server = breaks, mode = SttMode.LOCAL) is DictationResult.Success)
        assertTrue(dictateWith(local = breaks, server = works, mode = SttMode.LOCAL) is DictationResult.Failure)
        assertTrue(dictateWith(local = breaks, server = works, mode = SttMode.SERVER) is DictationResult.Success)
        assertTrue(dictateWith(local = works, server = breaks, mode = SttMode.SERVER) is DictationResult.Failure)
    }

    @Test
    fun `a phrase training and an update port are writable against the domain`() {
        val training = object : PhraseTraining {
            override fun recordSample(kind: PhraseKind) =
                dev.breaker.dictation.core.model.PhraseSample(kind, FloatArray(160))

            override fun upload(samples: List<dev.breaker.dictation.core.model.PhraseSample>) = Unit
            override fun downloadModel(kind: PhraseKind) = null
        }
        assertEquals(PhraseKind.WAKE, training.recordSample(PhraseKind.WAKE).kind)

        val updater = object : UpdateChecker {
            override fun check(force: Boolean) = null
            override fun install(release: ReleaseInfo) = Unit
            override fun rollback() = Unit
        }
        assertNull(updater.check(force = true))
    }

    @Test
    fun `a phrase trigger port is writable against the domain`() {
        var heard: String? = null
        val trigger = object : PhraseTrigger {
            override val isListening: Boolean = true
            override fun start(onPhrase: (PhraseEvent) -> Unit) {
                onPhrase(PhraseEvent.Send(1_200L))
            }

            override fun stop() = Unit
        }
        trigger.start { event ->
            heard = when (event) {
                PhraseEvent.Wake -> "WAKE"
                is PhraseEvent.Send -> "${event.kind}@${event.trimBeforeMs}"
            }
        }
        assertEquals("SEND@1200", heard)
    }

    @Test
    fun `a sync service port is writable against the domain`() {
        val sync = object : SyncService {
            val queue = mutableListOf<Transcription>()
            override fun pushPending() = dev.breaker.dictation.core.model.SyncReport(queue.size, 0)
            override fun enqueue(transcription: Transcription) {
                queue += transcription
            }
        }
        sync.enqueue(aTranscription(source = TranscriptionSource.SERVER))
        assertEquals(dev.breaker.dictation.core.model.SyncReport(1, 0), sync.pushPending())
    }
}
