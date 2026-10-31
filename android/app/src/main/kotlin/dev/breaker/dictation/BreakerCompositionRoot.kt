package dev.breaker.dictation

import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.audio.Pcm16WavEncoder
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.format.RuleBasedFormatter
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.LaunchResult
import dev.breaker.dictation.service.MicPermission
import dev.breaker.dictation.service.ServiceLauncher
import dev.breaker.dictation.settings.SettingsFileStore
import dev.breaker.dictation.stt.ondevice.LocalModelStore
import dev.breaker.dictation.stt.ondevice.ModelLoader
import dev.breaker.dictation.stt.ondevice.OnDeviceSttEngine
import dev.breaker.dictation.stt.server.SttServerEngine
import dev.breaker.dictation.transport.TcpConnectivityProbe
import dev.breaker.dictation.wiring.DictationComponent
import dev.breaker.dictation.wiring.LazyHistoryStore
import dev.breaker.dictation.wiring.LevelSink
import dev.breaker.dictation.wiring.RECOGNIZER_FACTORY
import dev.breaker.dictation.wiring.UuidIdSource
import dev.breaker.dictation.wiring.appMicSource
import java.io.File

/**
 * The single place modules are composed into each other.
 *
 * This composition root takes a plain-JVM `filesDir`, the history store and the microphone service
 * controller, never an Android context, so the graph it builds can be constructed and asserted on
 * the test JVM: a test gives it a temporary directory and fakes and receives back the same exposed
 * collaborators its production counterpart would.
 *
 * What it wires: a [FileCredentialRefHolder] bound to `credential-ref`, exposed as the platform
 * keystore port; a `SettingsFileStore` bound to `settings.properties`, composed with that same
 * keystore and exposed under the core port type; the history store; the [modelStore] that holds
 * the downloaded speech models; and the [dictation] parts.
 *
 * The history store can be handed in as a supplier. The root then exposes a [LazyHistoryStore]
 * over it, so reading the settings or the keystore never builds the database. A store handed in as
 * a value is exposed as it is.
 *
 * The on-device engine is the real one over [modelStore], with the real recognizer from the swap point in
 * `Swaps.kt`. The text committer has no default: the app hands in the real one, made by appTextCommitter in
 * AndroidSwaps.kt. The microphone defaults to the unavailable placeholder from the swap point in `Swaps.kt`,
 * whose open fails with a plain sentence. The app hands in the real microphone, made by appMicSource(audioManager)
 * in AndroidSwaps.kt, and a microphone can be handed in through [micSource]. The server engine is the real
 * [SttServerEngine] over the stored server address and the session-token seam: its key is the signed-in
 * user's token, read on every call. Until a session is plugged in, the seam answers null and the engine
 * answers that the key is not set. Both formatter slots take the rule-based formatter.
 */
class BreakerCompositionRoot(
    filesDir: File,
    val historyStore: HistoryStore,
    private val serviceController: DictationServiceController,
    private val committer: TextCommitter,
    private val micSource: MicSource = appMicSource(),
    private val serverEngineFactory: (serverUrl: () -> String, apiKey: () -> String?) -> SttEngine = REAL_SERVER_ENGINE,
    private val sessionToken: () -> String? = NO_SESSION,
) {
    /** Takes an already built history store; the microphone service is never switched on. */
    constructor(
        filesDir: File,
        historyStore: HistoryStore,
        committer: TextCommitter,
        serverEngineFactory: (serverUrl: () -> String, apiKey: () -> String?) -> SttEngine = REAL_SERVER_ENGINE,
        sessionToken: () -> String? = NO_SESSION,
    ) :
        this(
            filesDir,
            historyStore,
            neverArmedController(),
            committer,
            serverEngineFactory = serverEngineFactory,
            sessionToken = sessionToken,
        )

    /** Takes a supplier; it is called by the first use of the history store, once. */
    constructor(
        filesDir: File,
        history: () -> HistoryStore,
        serviceController: DictationServiceController,
        committer: TextCommitter,
        micSource: MicSource = appMicSource(),
        serverEngineFactory: (serverUrl: () -> String, apiKey: () -> String?) -> SttEngine = REAL_SERVER_ENGINE,
        sessionToken: () -> String? = NO_SESSION,
    ) :
        this(filesDir, LazyHistoryStore(history), serviceController, committer, micSource, serverEngineFactory, sessionToken)

    val keystore: dev.breaker.dictation.settings.Keystore =
        FileCredentialRefHolder(File(filesDir, "credential-ref"))

    val settingsStore: SettingsStore =
        SettingsFileStore(File(filesDir, "settings.properties"), keystore)

    /** Where the speech models are kept: the on-device engine reads it and the download writes it. */
    val modelStore: LocalModelStore = LocalModelStore(File(filesDir, "models"))

    /** The stored server address, read on every call. The connectivity probe reads the same provider. */
    private val serverUrl: () -> String = { settingsStore.load().serverUrl }

    /**
     * The engine's key is the signed-in user's token. Until a session is plugged in, the seam answers null
     * and the engine answers that the key is not set. The seam is called on every request and its value is
     * never kept.
     */
    private val serverApiKey: () -> String? = { sessionToken() }

    /** Called when a take ends by itself (the microphone stopped). Set by the tile host. */
    var onTakeEnded: (() -> Unit)? = null

    /** Called when the microphone was taken by another app. Set by the tile host. */
    var onMicTaken: (() -> Unit)? = null

    /** Takes the level of each microphone frame. Set by the tile host. */
    var levelSink: LevelSink? = null

    /** Takes a device failure, with the fallback that the take end runs. Set by the tile host. */
    var onDeviceFailure: ((() -> Unit) -> Unit)? = null

    /** The dictation parts, built on first use. */
    val dictation: DictationComponent by lazy {
        DictationComponent(
            settings = settingsStore,
            history = historyStore,
            clock = SystemClockAdapter,
            ids = UuidIdSource(),
            probe = TcpConnectivityProbe(
                serverUrlProvider = serverUrl,
                clock = SystemClockAdapter,
            ),
            localEngine = OnDeviceSttEngine(ModelLoader(store = modelStore, factory = RECOGNIZER_FACTORY)),
            serverEngine = serverEngineFactory(serverUrl, serverApiKey),
            localFormatter = RuleBasedFormatter(),
            serverFormatter = RuleBasedFormatter(),
            wavEncoder = Pcm16WavEncoder(),
            committer = committer,
            micSource = micSource,
            controller = serviceController,
            onTakeEnded = { onTakeEnded?.invoke() },
            onMicTaken = { onMicTaken?.invoke() },
            levelSink = LevelSink { level -> levelSink?.publish(level) },
            onDeviceFailure = { fallback -> onDeviceFailure?.invoke(fallback) ?: fallback() },
        )
    }
}

/** A controller that can never switch the service on: the permission is off and the launcher refuses. */
private fun neverArmedController(): DictationServiceController =
    DictationServiceController(
        permission = MicPermission { false },
        launcher = object : ServiceLauncher {
            override fun launch(): LaunchResult = LaunchResult.Refused

            override fun halt() {
                // Nothing was started, so there is nothing to stop.
            }
        },
    )

/** The real server engine: the address and the key come through the two providers, read on every call. */
private val REAL_SERVER_ENGINE: (serverUrl: () -> String, apiKey: () -> String?) -> SttEngine =
    { serverUrl, apiKey -> SttServerEngine(serverUrl, apiKey) }

/** No session is plugged in yet: the session seam answers null. */
private val NO_SESSION: () -> String? = { null }
