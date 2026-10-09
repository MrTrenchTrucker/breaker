package dev.breaker.dictation

import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.audio.Pcm16WavEncoder
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.format.RuleBasedFormatter
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.LaunchResult
import dev.breaker.dictation.service.MicPermission
import dev.breaker.dictation.service.ServiceLauncher
import dev.breaker.dictation.settings.SettingsFileStore
import dev.breaker.dictation.transport.TcpConnectivityProbe
import dev.breaker.dictation.wiring.DictationComponent
import dev.breaker.dictation.wiring.LOCAL_UNAVAILABLE_DETAIL
import dev.breaker.dictation.wiring.LazyHistoryStore
import dev.breaker.dictation.wiring.SERVER_UNAVAILABLE_DETAIL
import dev.breaker.dictation.wiring.UnavailableMicSource
import dev.breaker.dictation.wiring.UnavailableSttEngine
import dev.breaker.dictation.wiring.UnavailableTextCommitter
import dev.breaker.dictation.wiring.UuidIdSource
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
 * keystore and exposed under the core port type; the history store; and the [dictation] parts.
 *
 * The history store can be handed in as a supplier. The root then exposes a [LazyHistoryStore]
 * over it, so reading the settings or the keystore never builds the database. A store handed in as
 * a value is exposed as it is.
 *
 * The slots whose real adapters are not built yet (both engines, the text committer and, unless
 * one is handed in, the microphone) are the "unavailable" ones: each fails with a plain sentence.
 * A real microphone source drops in through [micSource] without touching anything else. Both
 * formatter slots take the rule-based formatter: the server path is never reached while the server
 * engine slot fails, and the text stays on the phone.
 */
class BreakerCompositionRoot(
    filesDir: File,
    val historyStore: HistoryStore,
    private val serviceController: DictationServiceController,
    private val micSource: MicSource = UnavailableMicSource(),
) {
    /** Takes an already built history store; the microphone service is never switched on. */
    constructor(filesDir: File, historyStore: HistoryStore) :
        this(filesDir, historyStore, neverArmedController())

    /** Takes a supplier; it is called by the first use of the history store, once. */
    constructor(filesDir: File, history: () -> HistoryStore, serviceController: DictationServiceController) :
        this(filesDir, LazyHistoryStore(history), serviceController)

    val keystore: dev.breaker.dictation.settings.Keystore =
        FileCredentialRefHolder(File(filesDir, "credential-ref"))

    val settingsStore: SettingsStore =
        SettingsFileStore(File(filesDir, "settings.properties"), keystore)

    /** The dictation parts, built on first use. */
    val dictation: DictationComponent by lazy {
        DictationComponent(
            settings = settingsStore,
            history = historyStore,
            clock = SystemClockAdapter,
            ids = UuidIdSource(),
            probe = TcpConnectivityProbe(
                serverUrlProvider = { settingsStore.load().serverUrl },
                clock = SystemClockAdapter,
            ),
            localEngine = UnavailableSttEngine(SttError.LOCAL_MODEL_MISSING, LOCAL_UNAVAILABLE_DETAIL),
            serverEngine = UnavailableSttEngine(SttError.OTHER, SERVER_UNAVAILABLE_DETAIL),
            localFormatter = RuleBasedFormatter(),
            serverFormatter = RuleBasedFormatter(),
            wavEncoder = Pcm16WavEncoder(),
            committer = UnavailableTextCommitter(),
            micSource = micSource,
            controller = serviceController,
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
