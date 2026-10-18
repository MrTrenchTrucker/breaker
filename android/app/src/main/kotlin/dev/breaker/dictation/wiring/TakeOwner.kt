package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.usecase.SendResult
import java.util.concurrent.atomic.AtomicReference

/** The path that began the running take: the Home screen or the tile. */
internal enum class TakeOwnerKind { HOME, TILE }

/** The identity of one take, as the take owner hands it out. */
internal class TakeMark

/** The take the owner holds: the path that began it and the mark that names it. */
private class Held(val kind: TakeOwnerKind, val mark: TakeMark = TakeMark())

/**
 * Which path holds the running take. A begin that records sets it, and every settle of the take clears
 * it, whichever path settles it. It is kept by the tile host rather than by the screen, so a screen that
 * is rebuilt (on rotation, for one) still finishes the take it began.
 */
internal class TakeOwner {
    private val held: AtomicReference<Held?> = AtomicReference(null)

    /** The path that holds the take now, or null when no take is held. */
    fun current(): TakeOwnerKind? = held.get()?.kind

    /** True only while the Home screen holds the take. */
    fun heldByHome(): Boolean = held.get()?.kind == TakeOwnerKind.HOME

    /** A begin recorded: [kind] holds the take until it is settled, and the take gets a mark of its own. */
    fun begun(kind: TakeOwnerKind) {
        held.set(Held(kind))
    }

    /** The take settled: nothing holds it now. */
    fun settled() {
        held.set(null)
    }

    /**
     * The mark of the take held now, or null when no take is held. A late hook takes it when the hook fires.
     * The take-ended, mic-taken and device-failure hooks run after a post, so a begin may land between the
     * hook firing and the posted block running. The hook takes the mark here and clears with it in the block,
     * so the clear can only reach the take the hook saw, never a newer take.
     */
    fun mark(): TakeMark? = held.get()?.mark

    /**
     * Clears the take only while it is still the take that [mark] names. A null mark, or a take that has
     * changed since the mark was taken, leaves the holder alone. The late hooks call it inside their posted
     * block, because a begin may land after the hook fired and before the block runs; that newer take must
     * keep its holder. The compare-and-set makes the check and the clear one step.
     */
    fun settledIf(mark: TakeMark?) {
        val now: Held = held.get() ?: return
        if (now.mark !== mark) return
        held.compareAndSet(now, null)
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
