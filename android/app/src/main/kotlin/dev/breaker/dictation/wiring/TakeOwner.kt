package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.usecase.SendResult
import java.util.concurrent.atomic.AtomicReference

/** The path that began the running take: the Home screen or the tile. */
internal enum class TakeOwnerKind { HOME, TILE }

/**
 * Which path holds the running take. A begin that records sets it, and every settle of the take clears
 * it, whichever path settles it. It is kept by the tile host rather than by the screen, so a screen that
 * is rebuilt (on rotation, for one) still finishes the take it began.
 */
internal class TakeOwner {
    private val held: AtomicReference<TakeOwnerKind?> = AtomicReference(null)

    /** The path that holds the take now, or null when no take is held. */
    fun current(): TakeOwnerKind? = held.get()

    /** True only while the Home screen holds the take. */
    fun heldByHome(): Boolean = held.get() == TakeOwnerKind.HOME

    /** A begin recorded: [kind] holds the take until it is settled. */
    fun begun(kind: TakeOwnerKind) {
        held.set(kind)
    }

    /** The take settled: nothing holds it now. */
    fun settled() {
        held.set(null)
    }
}

/**
 * A [TakePort] that keeps [owner] in step with the take it drives. A begin that records makes [kind] the
 * holder. A refused or failed begin clears the holder only when no take runs, so a refusal during another
 * path's take leaves that path as the holder. Every settle (finish, send, cancel and the reset) clears the
 * holder before the port call, so the holder is gone before the runner can go idle.
 */
internal class OwnedTakePort(
    private val inner: TakePort,
    private val owner: TakeOwner,
    private val kind: TakeOwnerKind,
) : TakePort {
    override val sessionState: DictationState
        get() = inner.sessionState

    override fun begin(): BeginResult {
        val answer: BeginResult = inner.begin()
        if (answer == BeginResult.Recording) {
            owner.begun(kind)
        } else if (inner.sessionState != DictationState.RECORDING) {
            owner.settled()
        }
        return answer
    }

    override fun finish(): FinishResult {
        owner.settled()
        return inner.finish()
    }

    override fun send(): SendResult? {
        owner.settled()
        return inner.send()
    }

    override fun cancel() {
        owner.settled()
        inner.cancel()
    }

    override fun resetAfterTaken() {
        owner.settled()
        inner.resetAfterTaken()
    }
}
