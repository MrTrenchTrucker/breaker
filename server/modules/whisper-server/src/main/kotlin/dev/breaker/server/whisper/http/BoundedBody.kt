package dev.breaker.server.whisper.http

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.Flow
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// A size-limited body subscriber: it cancels the subscription once the response would
// exceed `limit` and completes with null, so the caller reads a bounded answer or none.
// When a deadlineNanos is given, the subscriber cancels itself at the deadline so the
// exchange is closed instead of left open.
internal class BoundedBodySubscriber(
    private val limit: Int,
    private val deadlineNanos: Long?,
) : HttpResponse.BodySubscriber<ByteArray?> {
    private val future = CompletableFuture<ByteArray?>()
    private val stream = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null
    private var over = false
    private var scheduled: ScheduledFuture<*>? = null

    override fun getBody(): CompletionStage<ByteArray?> = future

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
        val deadline = deadlineNanos ?: return
        val left = deadline - System.nanoTime()
        if (left <= 0) {
            cancelAtDeadline()
            return
        }
        scheduled = EXECUTOR.schedule(Runnable { cancelAtDeadline() }, left, TimeUnit.NANOSECONDS)
    }

    private fun cancelAtDeadline() {
        over = true
        subscription?.cancel()
        future.completeExceptionally(TimeoutException("body deadline exceeded"))
    }

    override fun onNext(buffers: List<ByteBuffer>) {
        if (over) return
        for (buffer in buffers) {
            val size = buffer.remaining()
            if (stream.size() + size > limit) {
                over = true
                scheduled?.cancel(false)
                subscription?.cancel()
                future.complete(null)
                return
            }
            val bytes = ByteArray(size)
            buffer.get(bytes)
            stream.write(bytes, 0, size)
        }
    }

    override fun onError(throwable: Throwable) {
        scheduled?.cancel(false)
        if (!over) future.completeExceptionally(throwable)
    }

    override fun onComplete() {
        if (!over) {
            scheduled?.cancel(false)
            future.complete(stream.toByteArray())
        }
    }
}

private val EXECUTOR: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
    Thread(it, "bounded-body-executor").apply { isDaemon = true }
}

internal fun boundedBody(limit: Int): HttpResponse.BodyHandler<ByteArray?> =
    HttpResponse.BodyHandler { _ -> BoundedBodySubscriber(limit, null) }

internal fun boundedBody(limit: Int, deadlineNanos: Long): HttpResponse.BodyHandler<ByteArray?> =
    HttpResponse.BodyHandler { _ -> BoundedBodySubscriber(limit, deadlineNanos) }
