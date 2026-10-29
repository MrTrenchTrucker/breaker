package dev.breaker.dictation.wiring

/**
 * Joins the take owner to the spoken Send. A begin that records prepares the word stream and queues a start
 * for that take on [worker]; the queued start runs only while the take it was made for is still the one held,
 * so a take that ended before the worker ran it starts nothing. A settle queues a stop on [worker].
 *
 * Both callbacks only queue work and never wait. The owner calls [begun] on the take worker, inside the port
 * call that recorded the begin, and [cleared] on the thread that settled the take.
 */
internal class PhraseTakeLink(
    private val worker: Background,
    private val owner: TakeOwner,
    private val prepare: () -> Unit,
    private val start: () -> Unit,
    private val stop: () -> Unit,
) : TakeOwnerListener {

    override fun begun(kind: TakeOwnerKind, mark: TakeMark) {
        prepare()
        worker.submit { if (owner.mark() === mark) start() }
    }

    override fun cleared() {
        worker.submit { stop() }
    }
}
