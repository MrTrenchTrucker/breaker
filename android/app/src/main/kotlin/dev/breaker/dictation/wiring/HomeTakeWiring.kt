package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.usecase.SendResult
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
 */
internal class HomeTakeWiring(
    take: () -> TakePort,
    history: () -> HistoryStore,
    clipboard: TakenClipboard,
    switch: ArmedSwitch,
    private val owner: TakeOwner,
    background: Background,
    private val main: MainPost,
    private val modelReady: () -> Boolean,
) {
    private var home: HomeControl? = null

    private val port: LazyTakePort = LazyTakePort { OwnedTakePort(take(), owner, TakeOwnerKind.HOME) }

    private val adapter = HomeTakeAdapter(
        take = port,
        owner = owner,
        settle = TakeSettle(LazyHistoryStore(history), clipboard),
        switch = switch,
        background = background,
        main = main,
        onLine = { line -> main.post { home?.showLine(line) } },
        onState = { state -> main.post { home?.showRecording(state == DictationState.RECORDING) } },
    )

    /**
     * The big mic's tap: one call reaches the adapter once. A tap that would begin a take needs the speech
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
