package dev.breaker.dictation.wiring

/**
 * What the return activity does with a return link: it reads the link, hands it to the wiring
 * and records the state for the start screen. It answers true on every outcome, because the
 * return activity finishes every time.
 *
 * The return runs through [background], so the exchange with the server never blocks the main
 * thread. With the default [InlineBackground] the block runs at once and the state is recorded
 * before [handle] returns. A link the intent rule refuses is recorded as ignored at once, with
 * no call to the background. If the background cannot take the block, the attempt the holder
 * still holds is wiped and the return is recorded as not finished, so the start screen is
 * never left waiting with nothing recorded. Any failure to schedule, an Error included (a thread
 * that cannot start fails with one), wipes the attempt and records NOT_FINISHED.
 */
class HandoffReturnFlow(
    private val wiring: SignInStartWiring,
    private val store: SignInReturnStore,
    private val background: Background = InlineBackground,
) {

    /** Handle one incoming intent. Always true: the return activity finishes on every outcome. */
    fun handle(action: String?, dataString: String?): Boolean {
        val link = HandoffIntentRule.read(action, dataString)
        if (link == null) {
            store.record(SignInReturnState.IGNORED)
            return true
        }
        try {
            background.submit { finishReturn(link) }
        } catch (ignored: Throwable) {
            wiring.cancel()
            store.record(SignInReturnState.NOT_FINISHED)
        }
        return true
    }

    /** Runs the whole return and records its state when it ends. A receiver that throws records nothing. */
    private fun finishReturn(link: String) {
        try {
            store.record(wiring.returned(link))
        } catch (ignored: Exception) {
            return
        }
    }
}

/** A [Background] that runs each block at once, on the calling thread: the return flow's default. */
object InlineBackground : Background {
    override fun submit(block: () -> Unit) {
        block()
    }
}
