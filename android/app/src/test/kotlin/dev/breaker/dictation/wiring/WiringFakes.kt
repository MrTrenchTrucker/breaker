package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.port.ConnectivityProbe
import dev.breaker.dictation.core.port.Formatter
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.IdSource
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.core.port.WavEncoder
import dev.breaker.dictation.core.usecase.DictateUseCase
import dev.breaker.dictation.core.usecase.SendUseCase
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.LaunchResult
import dev.breaker.dictation.service.MicPermission
import dev.breaker.dictation.service.ReportingMicSource
import dev.breaker.dictation.service.ServiceLauncher
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Waits for [signal] for a bounded time and fails by name when it never arrives. */
internal fun <T> awaitBounded(what: String, signal: CompletableDeferred<T>): T =
    try {
        runBlocking { withTimeout(5_000L) { signal.await() } }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("app: timed out waiting for $what")
    }

/** A fifth of a second of quiet-but-not-silent speech, as the audio port delivers it. */
internal fun speech(): FloatArray = FloatArray(3_200) { 0.2f }

internal class FakeSettingsStore(var current: AppSettings = AppSettings()) : SettingsStore {
    override fun load(): AppSettings = current

    override fun save(settings: AppSettings) {
        current = settings
    }
}

/** A history store that keeps a call log, the saved rows and the answers it was told to give. */
internal class RecordingHistoryStore : HistoryStore {
    val calls: MutableList<String> = ArrayList()
    val saved: MutableList<Transcription> = ArrayList()
    var listResult: List<Transcription> = emptyList()
    var deleteResult: Boolean = true
    var lastLimit: Int? = null
    var lastDeletedId: String? = null

    override fun save(transcription: Transcription) {
        calls.add("save")
        saved.add(transcription)
    }

    override fun list(limit: Int): List<Transcription> {
        calls.add("list")
        lastLimit = limit
        return listResult
    }

    override fun delete(id: String): Boolean {
        calls.add("delete")
        lastDeletedId = id
        return deleteResult
    }
}

internal class FakeProbe(var reachable: Boolean = false) : ConnectivityProbe {
    var asks: Int = 0
        private set

    override fun isServerReachable(): Boolean {
        asks += 1
        return reachable
    }
}

internal class FakeSttEngine(var result: SttResult) : SttEngine {
    var calls: Int = 0
        private set

    override fun transcribe(request: SttRequest): SttResult {
        calls += 1
        return result
    }
}

internal class FakeCommitter(var result: CommitOutcomeResult = CommitOutcomeResult(CommitOutcome.COMMITTED)) : TextCommitter {
    var commits: Int = 0
        private set

    override fun commit(request: CommitRequest): CommitOutcomeResult {
        commits += 1
        return result
    }
}

/** A formatter that returns the text unchanged and counts how often it was asked. */
internal class CountingFormatter : Formatter {
    var calls: Int = 0
        private set

    override fun format(rawText: String): String {
        calls += 1
        return rawText
    }
}

internal class FixedWavEncoder : WavEncoder {
    override fun encode(pcm: FloatArray): ByteArray = byteArrayOf(1, 2, 3)
}

internal class SequenceIds : IdSource {
    private var next: Int = 0

    override fun newId(): String {
        next += 1
        return "id-$next"
    }
}

internal val fixedClock: Clock = Clock { 1_000L }

internal class SwitchPermission(var granted: Boolean) : MicPermission {
    override fun isRecordAudioGranted(): Boolean = granted
}

/** A launcher that counts launches and halts and lets a test wait for the first halt. */
internal class RecordingLauncher(var result: LaunchResult = LaunchResult.Launched) : ServiceLauncher {
    private val launchCount = AtomicInteger()
    private val haltCount = AtomicInteger()
    val halted = CompletableDeferred<Unit>()

    val launches: Int
        get() = launchCount.get()

    val halts: Int
        get() = haltCount.get()

    override fun launch(): LaunchResult {
        launchCount.incrementAndGet()
        return result
    }

    override fun halt() {
        haltCount.incrementAndGet()
        halted.complete(Unit)
    }
}

/** An audio source with counters; [deliver] plays frames into the listener it was started with. */
internal class FakeAudioSource : AudioSource {
    var starts: Int = 0
        private set
    var stops: Int = 0
        private set
    var startError: RuntimeException? = null
    var stopError: RuntimeException? = null
    var onStop: () -> Unit = {}
    private var listener: AudioListener? = null

    override fun start(listener: AudioListener) {
        starts += 1
        startError?.let { throw it }
        this.listener = listener
    }

    override fun stop() {
        stops += 1
        onStop()
        stopError?.let { throw it }
    }

    fun deliver(samples: FloatArray) {
        listener?.onFrame(samples)
    }
}

/** A microphone source that only counts closes. */
internal class CountingMic : MicSource {
    var closes: Int = 0
        private set

    override val sampleRateHz: Int
        get() = 16_000

    override val channelCount: Int
        get() = 1

    override fun open() {}

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int = 0

    override fun close() {
        closes += 1
    }
}

/**
 * The runner over fakes. [withCapture] adds a real reporting wrapper whose reports are counted in
 * [endedReports] and forwarded to the runner; the fake audio source closes it when it stops, the
 * way the real capture closes the microphone.
 */
internal class Rig(
    mode: SttMode = SttMode.LOCAL,
    localEngine: SttEngine = FakeSttEngine(SttResult.Success("hello world")),
    committer: TextCommitter = FakeCommitter(),
    permissionGranted: Boolean = true,
    launchResult: LaunchResult = LaunchResult.Launched,
    armed: Boolean = true,
    withCapture: Boolean = false,
) {
    val history = RecordingHistoryStore()
    val localFormatter = CountingFormatter()
    val serverFormatter = CountingFormatter()
    val launcher = RecordingLauncher(launchResult)
    val controller = DictationServiceController(SwitchPermission(permissionGranted), launcher)
    val audio = FakeAudioSource()
    private var runnerRef: DictationRunner? = null

    var endedReports: Int = 0
        private set

    val capture: ReportingMicSource? =
        if (withCapture) ReportingMicSource(CountingMic()) { endedReports += 1; runnerRef?.onCaptureEnded() } else null

    val runner: DictationRunner

    init {
        val dictate = DictateUseCase(
            settings = FakeSettingsStore(AppSettings(mode = mode)),
            probe = FakeProbe(),
            localEngine = localEngine,
            serverEngine = FakeSttEngine(SttResult.Success("from the server")),
            serverFormatter = serverFormatter,
            wavEncoder = FixedWavEncoder(),
            clock = fixedClock,
            ids = SequenceIds(),
            localFormatter = localFormatter,
        )
        runner = DictationRunner(dictate, SendUseCase(committer, history), audio, controller, capture)
        runnerRef = runner
        audio.onStop = { capture?.close() }
        if (armed) controller.adopt()
    }

    fun speak() = audio.deliver(speech())

    /** Listens, speaks and finishes with the text ready to send. */
    fun readyToSend() {
        runner.begin()
        speak()
        runner.finish()
    }
}
