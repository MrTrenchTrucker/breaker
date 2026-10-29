package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.port.PhraseTrigger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Routes the spoken send phrase to the path that holds the take. The phrase detector reports on its own
 * thread, so its callback only checks the gate and posts the route to the main thread; a send never
 * finishes a take on the detector's thread. Each start opens one gate for one take: the first send passes,
 * and later sends of that take are dropped. A callback of a stopped run, or of a run that a newer start
 * replaced, is dropped. A refused start is not an error for the take: the take runs without spoken send,
 * and the owner is told once through the unavailable callback.
 */
internal class PhraseSendHandler(
    private val trigger: PhraseTrigger,
    private val owner: TakeOwner,
    private val main: MainPost,
    private val homeSend: (Long?) -> Unit,
    private val tileSend: (Long?) -> Unit,
    private val onUnavailable: () -> Unit,
) {
    /** One listening run. Its [sent] flag is the gate for the take the run belongs to. */
    private class Listening {
        val sent: AtomicBoolean = AtomicBoolean(false)
    }

    /** The run that may still deliver. Every other run, stopped or replaced, is stale. */
    private val current: AtomicReference<Listening?> = AtomicReference(null)

    /**
     * Starts listening for a new take. The detector keeps the callback it was given first while it is
     * still running, so the detector is stopped before it is started again.
     */
    fun start() {
        val listening = Listening()
        current.set(listening)
        guarded { trigger.stop() }   // the detector keeps the first callback if it is still running
        try {
            trigger.start { event -> onPhrase(listening, event) }
        } catch (e: Exception) {
            refuse(listening)
        } catch (e: LinkageError) {
            refuse(listening)
        }
    }

    /** Stops listening. A second stop, or a stop with no run, does nothing. */
    fun stop() {
        if (current.getAndSet(null) == null) return   // a second stop finds nothing to end
        guarded { trigger.stop() }
    }

    /** A refused start ends its own run and tells the owner once; the take itself is never touched. */
    private fun refuse(listening: Listening) {
        current.compareAndSet(listening, null)
        guarded { trigger.stop() }   // the detector marks itself listening before its stream starts
        main.post { onUnavailable() }
    }

    /** Runs on the detector's thread: checks the run and the gate, then posts the route. */
    private fun onPhrase(listening: Listening, event: PhraseEvent) {
        if (current.get() !== listening) return
        if (event !is PhraseEvent.Send) return
        if (!listening.sent.compareAndSet(false, true)) return
        main.post { route(listening, event.trimBeforeMs) }
    }

    /** Runs on the main thread: hands the trim, unchanged, to the path that holds the take, or drops it. */
    private fun route(listening: Listening, trimBeforeMs: Long?) {
        if (listening !== current.get()) return
        when (owner.current()) {
            TakeOwnerKind.HOME -> homeSend(trimBeforeMs)
            TakeOwnerKind.TILE -> tileSend(trimBeforeMs)
            null -> Unit  // no take held: the send is dropped
        }
    }

    /** Runs the call and ignores an exception or a link error from the detector, so a stop never fails the take. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // The detector refused the call; nothing more can be done, and the take must not fail.
        } catch (e: LinkageError) {
            // The same for a link error from the detector's code.
        }
    }
}

/**
 * Hands a spoken Send for a take the tile holds to the coordinator's send while the tile is shown.
 * With the tile hidden nothing is finished or pushed: [onHidden] runs once and the take is left as it is.
 */
internal fun TileCoordinator.onSpokenSend(trimBeforeMs: Long?, onHidden: () -> Unit) {
    if (tileShown) onSend(trimBeforeMs) else onHidden()
}
