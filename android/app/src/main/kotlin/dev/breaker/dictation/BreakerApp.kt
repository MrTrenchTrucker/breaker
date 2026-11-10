package dev.breaker.dictation

import android.util.Log
import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.ConnectivityProbe
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.history.SqliteHistoryStore
import dev.breaker.dictation.host.OneShotBackground
import dev.breaker.dictation.host.RefusalHold
import dev.breaker.dictation.host.SerialBackground
import dev.breaker.dictation.host.ServiceRefusalNotice
import dev.breaker.dictation.host.TileHost
import dev.breaker.dictation.host.syncScheduler
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.JdkHttpTransport
import dev.breaker.dictation.service.AndroidMicPermission
import dev.breaker.dictation.service.AndroidServiceLauncher
import dev.breaker.dictation.service.DictationNotification
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.sync.PullTransport
import dev.breaker.dictation.sync.QueuedSyncService
import dev.breaker.dictation.sync.SyncUploader
import dev.breaker.dictation.transport.TcpConnectivityProbe
import dev.breaker.dictation.wiring.ArmedSwitch
import dev.breaker.dictation.wiring.Background
import dev.breaker.dictation.wiring.FileOffStore
import dev.breaker.dictation.wiring.SyncHttpUploader
import dev.breaker.dictation.wiring.SyncWorker
import dev.breaker.dictation.wiring.SyncingHistoryStore
import dev.breaker.dictation.wiring.appMicSource
import dev.breaker.dictation.wiring.appTextCommitter
import dev.breaker.dictation.wiring.pullTransport
import dev.breaker.dictation.wiring.syncAllowed
import java.io.File

/**
 * The application entry; the only place the concrete modules are built for the
 * running app.
 *
 * It owns a single [dev.breaker.dictation.BreakerCompositionRoot], constructed
 * lazily from the app's files directory, the microphone service controller and
 * a supplier of the concrete history store, so wiring happens once and the
 * database is opened only when the history is first used. The history store is
 * built here because [SqliteHistoryStore.create] needs an Android context; the
 * composition root itself stays plain JVM and receives the store typed as the
 * core [HistoryStore] port. The app also owns the one start-up purge: it is
 * scheduled on an app-lifetime coroutine scope off the main thread, and a
 * failure is logged, not thrown, so a damaged database can never take the
 * app down with it.
 *
 * It also keeps the user's switch and the floating tile in step with the microphone service: the
 * [armedSwitch] remembers a switch-off the user chose, and the [tileHost] shows the tile while the
 * service is on and hides it when the service goes off. Both are told by the one state observer set in
 * [onCreate].
 */
class BreakerApp : android.app.Application() {

    private val purgeScope = createPurgeScope()

    /** Whether the phone sets its clock automatically; the purge guard reads it to trust or suspect `now`. */
    private val automaticTimeOn: AutomaticTimeOn by lazy {
        AndroidAutomaticTimeOn(applicationContext.contentResolver)
    }

    /** The two instants the purge guard keeps: when the last purge ran and when a deferral began. */
    private val purgeBookkeeping: PurgeBookkeepingStore by lazy {
        PurgeBookkeepingStore(File(filesDir, PurgeBookkeepingStore.FILE_NAME))
    }

    private val history: SqliteHistoryStore by lazy {
        SqliteHistoryStore.create(applicationContext, SystemClockAdapter)
    }

    /** The sync queue, built once per process; every finished take is queued through the decorator. */
    private val syncService: QueuedSyncService by lazy {
        QueuedSyncService.create(
            applicationContext,
            uploader = syncUploader,
            probe = syncProbe,
            pullTransport = pullTransportForSync,
            pullSink = if (syncOn) history else null,
        )
    }

    /** The connectivity probe: the real TCP probe over the stored server address, read on every probe. */
    private val syncProbe: ConnectivityProbe by lazy {
        TcpConnectivityProbe(
            serverUrlProvider = { settingsStore.load().serverUrl },
            clock = SystemClockAdapter,
        )
    }

    /** The app's uploader: the real transport over the stored address, one addressed session per call. */
    private val syncUploader: SyncUploader by lazy {
        SyncHttpUploader(
            transport = JdkHttpTransport({ settingsStore.load().serverUrl }),
            limits = CallLimits(10_000, 30_000, 1_048_576L),
            addressedSession = compositionRoot.addressedSession,
        )
    }

    /** The pull transport: the same pieces the uploader uses, wired only when sync is on. */
    private val pullTransportForSync: PullTransport? by lazy {
        if (syncOn) pullTransport(settingsStore, compositionRoot.addressedSession)
        else null
    }

    /** One serial background thread for sync drains; the scheduler arms the delayed re-runs on it. */
    private val syncBackground: Background = SerialBackground("breaker-sync")

    /** The worker: drains the queue after every save, at app start and on its backoff timer. */
    private val syncWorker: SyncWorker by lazy {
        SyncWorker(
            service = syncService,
            background = syncBackground,
            scheduler = syncScheduler(syncBackground),
            sessionGate = { compositionRoot.addressedSession()?.session },
            pendingCount = { syncService.pendingCount() - syncService.parkedCount() },
        )
    }

    /** One decision, read once: is sync allowed in this build. Release = false, debug = true. */
    private val syncOn: Boolean by lazy { syncAllowed(applicationInfo.flags) }

    /** The store every save caller reaches: the real store wrapped by the sync decorator. */
    private val decoratedHistoryStore: SyncingHistoryStore by lazy {
        SyncingHistoryStore(inner = history, syncService = syncService, worker = syncWorker)
    }

    /** The app's history: the plain store in release, the decorated store in debug. */
    val historyStore: HistoryStore get() = if (syncOn) decoratedHistoryStore else history

    /**
     * Switches the microphone service on and off. Built on first use, after the
     * application context exists.
     */
    val dictationServiceController: DictationServiceController by lazy {
        DictationServiceController(
            AndroidMicPermission(applicationContext),
            AndroidServiceLauncher(applicationContext),
        )
    }

    /** The dictated-text committer, built once from the application context. */
    val textCommitter: TextCommitter by lazy { appTextCommitter(applicationContext) }

    /** The microphone the capture reads, built once from the application context. */
    val realMicSource: MicSource by lazy {
        val audioManager = applicationContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        appMicSource(audioManager)
    }

    val compositionRoot: dev.breaker.dictation.BreakerCompositionRoot by lazy {
        BreakerCompositionRoot(
            filesDir = filesDir,
            history = { historyStore },
            serviceController = dictationServiceController,
            committer = textCommitter,
            micSource = realMicSource,
            background = OneShotBackground(),
        )
    }

    /** The on and off switch over the service; a switch-off is kept in a small file of the app. */
    internal val armedSwitch: ArmedSwitch by lazy {
        ArmedSwitch(dictationServiceController, FileOffStore(File(filesDir, "dictation-off")))
    }

    /** The floating tile and the speech model download, built on first use. */
    internal val tileHost: TileHost by lazy {
        TileHost(applicationContext, compositionRoot, armedSwitch::isOn)
    }

    /** The refusal a refused foreground leaves for the launcher activity to show on its next resume. */
    internal val refusalHold: RefusalHold = RefusalHold()

    /** What a refused foreground leaves: the held sentence, and a notice when the user permits notifications. */
    internal val serviceRefusal: ServiceRefusalNotice by lazy {
        ServiceRefusalNotice(
            hold = { sentence -> refusalHold.set(sentence) },
            notificationPermitted = { DictationNotification.notificationsPermitted(this) },
            postNotice = { sentence -> DictationNotification.postRefusal(this, sentence) },
        )
    }

    /** The app's settings, proxied through the lazily-built composition root. */
    val settingsStore: SettingsStore get() = compositionRoot.settingsStore

    override fun onCreate() {
        super.onCreate()
        scheduleAppStartPurge(
            purgeScope,
            purge = {
                runGuardedStartPurge(
                    now = SystemClockAdapter.nowEpochMillis(),
                    autoTimeOn = automaticTimeOn.isOn(),
                    store = purgeBookkeeping,
                    purge = { history.purgeExpired() },
                )
            },
            onFailure = { e -> Log.w("BreakerApp", "start-up purge failed", e) },
        )
        dictationServiceController.setStateObserver { armed, reason ->
            armedSwitch.onStateChanged(armed, reason)
            tileHost.onArmedChanged(armed)
        }
        // Start the sync worker at app start: a drain runs after every save, at app start and on the backoff timer.
        if (syncOn) syncWorker.onStart()
    }

    override fun onTerminate() {
        super.onTerminate()
        // Stop the worker cleanly: no further triggers start a drain and a pending delayed run is cancelled.
        if (syncOn) syncWorker.stop()
    }
}
