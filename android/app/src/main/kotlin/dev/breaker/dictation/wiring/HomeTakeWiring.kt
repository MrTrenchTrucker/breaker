package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.usecase.SendResult
import dev.breaker.dictation.overlay.TileState
import dev.breaker.dictation.ui.HomeControl

/**
 * The Home take's wiring: the lazy ports, the one adapter over them, and the way its lines and states
 * reach the Home screen.
 *
 * Nothing is built before the first tap. [take], which builds the dictation component, and [history],
 * which opens the database, are functions: each is called only when the adapter first uses the port or
 * the store, and the store's first use is the save on the background. The adapter's lines and states
 * go through [main] to the screen given to [attach]; a line or a state that comes before [attach] is
 * not shown.
 *
 * The Home's mic is a [HomeScreenMic] — the tile's own drawing, level slots and X, with the tile's draw
 * cancelled while the Home owns the take. The wiring pushes the take's state and the audio level to
 * the mic through [screenMic].
 *
 * The Home derives its mic face from the shared [MicBusyPort] [busy] on every state push — the tile
 * host forwards the tile's MIC_BUSY flag and the three begin answers to it — and the wiring registers
 * the busy change listener and the armed-switch listener, so a flip of either re-pushes the current
 * state and repaints the mic.
 */
internal class HomeTakeWiring(
    take: () -> TakePort,
    history: () -> HistoryStore,
    clipboard: TakenClipboard,
    private val switch: ArmedSwitch,
    private val owner: TakeOwner,
    private val busy: MicBusyPort = NoopMicBusyPort,
    background: Background,
    private val main: MainPost,
    private val modelReady: () -> Boolean,
) {
    private var home: HomeControl? = null

    private val port: LazyTakePort = LazyTakePort { OwnedTakePort(take(), owner, TakeOwnerKind.HOME) }

    /**
     * The listener the armed switch notifies: it re-pushes the derived mic face from the port's own
     * state, so an arm or a disarm with the Home open repaints the mic instead of leaving the face it
     * was given when it was created. Held so [unwatchSwitch] can take it off again.
     */
    internal val switchListener: (Boolean) -> Unit = { main.post { rePushState(port.sessionState) } }

    /** The Home screen's mic, set by [createScreenMic] or the [installScreenMic] seam. Pushed state and level from this wiring. */
    var screenMic: HomeMicPort? = null

    /**
     * The amber SENDING face is sticky across a busy re-push: set when onState(SENDING) arrives (the take
     * has ended and the local model is transcribing) and cleared by any other state. Read in [rePushState]
     * so a busy flip during transcription re-pushes SENDING, not ARMED over it. Main thread only.
     */
    internal var sending = false

    private val adapter = HomeTakeAdapter(
        take = port,
        owner = owner,
        settle = TakeSettle(LazyHistoryStore(history), clipboard),
        switch = switch,
        background = background,
        main = main,
        busy = busy,
        onLine = { line -> main.post { home?.showLine(line) } },
        onState = { state -> main.post { sending = state == DictationState.SENDING; rePushState(state) } },
    )

    /**
     * Registers the two listeners: a flip of the shared busy flag and a flip of the armed switch each
     * re-push the current state, repainting the mic.
     */
    init {
        busy.setBusyListener { rePushState(port.sessionState) }
        switch.addSwitchListener(switchListener)
    }

    /**
     * Takes a switch listener off again. The switch is app-wide and a wiring is built per screen, so a
     * listener left on it would re-push a mic after the screen is gone; the activity calls this in
     * onDestroy, as it takes the level sink off the tile host.
     */
    internal fun unwatchSwitch(listener: (Boolean) -> Unit) {
        switch.removeSwitchListener(listener)
    }

    /**
     * Pushes [state] to the mic: the recording look from the state, and the mic face derived from the
     * switch, the shared busy flag, the sticky sending flag and the state, in that order — armed-off wins, else busy, else
     * sending, else RECORDING, else ARMED. Used by the state callback and the busy-flag listener; a null home or mic
     * is a harmless no-op, as the state callback was before the screen is attached.
     */
    private fun rePushState(state: DictationState) {
        home?.showRecording(state == DictationState.RECORDING)
        screenMic?.setState(homeMicFace(switch.isOn(), busy.micBusy(), sending, state))
    }

    /**
     * Creates the Home screen's mic and wires its callbacks to this wiring. The mic's view is
     * placed by the app's screen. The wiring pushes state and level to the mic.
     */
    fun createScreenMic(
        context: android.content.Context,
        theme: dev.breaker.shared.tokens.ThemeMode,
        label: String,
        cancelLabel: String,
        sendLabel: String,
    ): HomeScreenMic {
        val mic = HomeScreenMic(
            context = context,
            theme = theme,
            label = label,
            cancelLabel = cancelLabel,
            sendLabel = sendLabel,
            onTap = { onTap() },
            onBegin = { onTap() },
            onCancel = { adapter.onCancel() },
            onSend = { adapter.onSend() },
        )
        screenMic = mic
        // the derived face once, so a rebuilt mic never shows a stale one
        rePushState(port.sessionState)
        return mic
    }

    /**
     * The test seam: installs [mic] as the screen's mic and pushes the derived face once — the step
     * [createScreenMic] performs after it has built the real mic. A JVM test cannot build the real mic
     * (it is an Android view), so this is the only way to watch the face the wiring pushes without a
     * phone. The state pushed is the port's own, the one a fresh launch pushes.
     */
    internal fun installScreenMic(mic: HomeMicPort) {
        screenMic = mic
        val state: DictationState = port.sessionState
        rePushState(state)
    }

    /**
     * The mic's tap: one call reaches the adapter once. A tap that would begin a take needs the speech
     * model: with no model it answers the model sentence and the adapter is never called, so no take is
     * begun. A tap that finishes the Home's own take goes on to the adapter whether or not the model is there.
     */
    fun onTap() {
        val finishing: Boolean = owner.heldByHome() && port.sessionState == DictationState.RECORDING
        if (!finishing && !modelReady()) {
            main.post { home?.showLine(ModelSentences.NO_MODEL) }
            return
        }
        adapter.onTap()
    }

    /**
     * Gives the Home screen to the wiring once it is shown. Main thread only. A screen attached while the
     * Home holds a recording take shows the recording look, so a rebuilt screen does not show the take as idle.
     */
    fun attach(screen: HomeControl) {
        home = screen
        if (owner.heldByHome() && port.sessionState == DictationState.RECORDING) screen.showRecording(true)
    }
}

/** A [TakePort] that asks for the real port on its first use and keeps it after that. */
internal class LazyTakePort(supplier: () -> TakePort) : TakePort {
    private val port: TakePort by lazy { supplier() }

    override val sessionState: DictationState
        get() = port.sessionState

    override fun begin(): BeginResult = port.begin()

    override fun finish(): FinishResult = port.finish()

    override fun send(): SendResult? = port.send()

    override fun cancel() {
        port.cancel()
    }

    override fun resetAfterTaken() {
        port.resetAfterTaken()
    }
}

/** The Home mic's face for a push: armed-off wins, else the shared busy flag, else the sticky sending flag, else RECORDING, else ARMED. */
internal fun homeMicFace(switchOn: Boolean, busy: Boolean, sending: Boolean, state: DictationState): TileState = when {
    !switchOn -> TileState.IDLE
    busy -> TileState.MIC_BUSY
    sending -> TileState.SENDING
    state == DictationState.RECORDING -> TileState.RECORDING
    else -> TileState.ARMED
}
