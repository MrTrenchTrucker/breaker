package dev.breaker.dictation.host

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.SettingsLauncherActivity
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.service.ModelNotifications
import dev.breaker.dictation.service.DictationNotification
import dev.breaker.dictation.service.NotificationRoute
import dev.breaker.dictation.wiring.DisplayWatch
import dev.breaker.dictation.wiring.ForwardingDownloadNotice
import dev.breaker.dictation.wiring.LevelFeed
import dev.breaker.dictation.wiring.ModelDownloadNotice
import dev.breaker.dictation.wiring.ModelDownloader
import dev.breaker.dictation.wiring.NoticeClearingTilePort
import dev.breaker.dictation.wiring.Opener
import dev.breaker.dictation.wiring.OwnedTakePort
import dev.breaker.dictation.wiring.StoreModelReady
import dev.breaker.dictation.wiring.TakeOwner
import dev.breaker.dictation.wiring.TakeOwnerKind
import dev.breaker.dictation.wiring.TakePortAdapter
import dev.breaker.dictation.wiring.TileCoordinator
import dev.breaker.dictation.wiring.appGesture
import dev.breaker.dictation.wiring.appTakenClipboard
import dev.breaker.dictation.wiring.modelInstallPortFor

/**
 * Where the floating tile and the speech model download are put together for the running app.
 *
 * The tile follows the microphone service: [onArmedChanged] is called from whichever thread changes
 * the service, and the coordinator behind it is reached only through a post to the main looper, where
 * the tile may be touched. The taps of the tile arrive on the main looper and go the same way. The
 * coordinator is built on first use.
 *
 * The download has a thread of its own, so a long download never holds up a dictation, and it uses
 * the same model store the speech engine reads from. [requestDownload] is the one entry point; the
 * settings button calls it and a later first-run step calls the same method.
 *
 * The shake gesture is started when the service goes on and stopped when it goes off,
 * both on the main looper and in the order the changes happened; its trigger reaches the coordinator
 * through the same post as a tap on the tile. A shake only shows the tile.
 * The display-change listener is started when the service goes on and stopped when it goes off, on the main
 * looper beside the shake gesture, and tells the tile once per real change of the default display.
 * [onLauncherVisible] shows the tile again when the
 * launcher comes to the front while the switch is on, because the permission to draw over other
 * apps may have been given since the tile could not be shown; showing a tile that is shown is safe.
 * The switch is read on the main looper before the coordinator is touched, so a launcher start with
 * the switch off builds nothing.
 *
 * It is also the [Opener] of the tile: a tap on an idle or failed tile opens the launcher, on the
 * model route when no model is installed.
 */
internal class TileHost(
    private val context: Context,
    private val root: BreakerCompositionRoot,
    private val isOn: () -> Boolean,
) : Opener {

    private val main = MainLooperPost()
    private val notifications = ModelNotifications(context)
    private val gesture = appGesture(context)
    private val selectedId: () -> String = { root.settingsStore.load().modelSize }

    private val tile: OverlayTilePort = OverlayTilePort(
        context = context,
        settings = root.settingsStore,
        onTap = { toCoordinator { it.onTap() } },
        onBegin = { toCoordinator { it.onBegin() } },
        onCancel = { toCoordinator { it.onCancel() } },
        onSend = { toCoordinator { it.onSend() } },
        onCloseRequested = { toCoordinator { it.onCloseRequested() } },
        onNoticeDismissed = { main.post { noticePort.onNoticeDismissed() } },
    )

    private val noticePort = NoticeClearingTilePort(tile, main, onDismissed = { setNotificationLine(null) })

    private val displayWatch: DisplayWatch = DisplayWatch(DisplayChangeAndroid(context, main)) { tile.onDisplayChanged() }

    /** The one serial worker of the dictation take: the tile coordinator and the Home take both run on it. */
    internal val takeWorker: SerialBackground by lazy { SerialBackground("breaker-dictation") }

    /** Which path holds the running take: a begin that records sets it, and every settle of the take clears it. */
    internal val takeOwner: TakeOwner = TakeOwner()

    private val coordinator: TileCoordinator by lazy {
        TileCoordinator(
            noticePort,
            main,
            takeWorker,
            OwnedTakePort(TakePortAdapter(root.dictation.runner), takeOwner, TakeOwnerKind.TILE),
            StoreModelReady(root.modelStore, selectedId),
            notifications,
            this,
            appTakenClipboard(context),
            { text -> setNotificationLine(text) },
            root.historyStore,
        )
    }

    init {
        root.onTakeEnded = { val m = takeOwner.mark(); toCoordinator { takeOwner.settledIf(m); it.onTakeEnded() } }
        root.onMicTaken = { val m = takeOwner.mark(); toCoordinator { takeOwner.settledIf(m); it.onTakeMicTaken() } }
        root.onDeviceFailure = { fallback -> val m = takeOwner.mark(); toCoordinator { takeOwner.settledIf(m); if (!it.onTakeFailed()) fallback() } }
        root.levelSink = LevelFeed(noticePort, main) { root.dictation.runner.sessionState == DictationState.RECORDING }
    }

    private val forwardingNotice = ForwardingDownloadNotice(notifications)
    private val downloader = ModelDownloader(
        installer = modelInstallPortFor(root.modelStore),
        selectedId = selectedId,
        background = SerialBackground("breaker-model-download"),
        main = main,
        notice = forwardingNotice,
    )

    /** Sets the notification line to a text of a taken take (or clears it when null). Main thread only. */
    private fun setNotificationLine(text: String?) {
        main.post {
            val manager = context.getSystemService(NotificationManager::class.java)
            try {
                manager.notify(
                    DictationNotification.NOTIFICATION_ID,
                    if (text == null) {
                        DictationNotification.build(context)
                    } else {
                        DictationNotification.withText(context, text)
                    }
                )
            } catch (e: RuntimeException) {
                // The platform refused the notification; there is nothing else to show it with.
            }
        }
    }

    /** Shows the tile when the service goes on and hides it when the service goes off. Any thread. */
    fun onArmedChanged(armed: Boolean) {
        main.post { if (armed) gesture.start { toCoordinator { it.onShake() } } else gesture.stop() }
        main.post { if (armed) displayWatch.start() else displayWatch.stop() }
        toCoordinator { it.onArmedChanged(armed) }
    }

    /** Shows the tile again when the switch is on, for a tile that could not be shown before. Any thread. */
    fun onLauncherVisible() {
        main.post { if (isOn()) coordinator.onArmedChanged(true) }
    }

    /** Starts the download of the speech model the settings select. Call it on the main thread. */
    fun requestDownload() {
        downloader.requestDownload()
    }

    /** Sets the in-app download listener (called by the activity in onResume). */
    fun setDownloadListener(listener: ModelDownloadNotice?) {
        forwardingNotice.setListener(listener)
    }

    /** Probe only: hands the keyboard inset listener to the tile on the main thread; nothing else calls it. */
    fun setImeProbe(listener: ((Int, Boolean) -> Unit)?) {
        main.post {
            tile.setImeProbe(listener)
        }
    }

    override fun openLauncher(route: String?) {
        try {
            val intent = Intent(context, SettingsLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (route != null) intent.putExtra(NotificationRoute.EXTRA_ROUTE, route)
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            // The platform refused to open the launcher; the tile stays as it is.
        }
    }

    private fun toCoordinator(block: (TileCoordinator) -> Unit) {
        main.post { block(coordinator) }
    }
}
