package dev.breaker.server.whisper.http

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

// A size-limited body subscriber: it cancels the subscription once the response would
// exceed `limit` and completes with null, so the caller reads a bounded answer or none.
internal class BoundedBodySubscriber(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray?> {
    private val future = CompletableFuture<ByteArray?>()
    private val stream = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null
    private var over = false

    override fun getBody(): CompletionStage<ByteArray?> = future

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(buffers: List<ByteBuffer>) {
        if (over) return
        for (buffer in buffers) {
            val size = buffer.remaining()
            if (stream.size() + size > limit) {
                over = true
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
        if (!over) future.completeExceptionally(throwable)
    }

    override fun onComplete() {
        if (!over) future.complete(stream.toByteArray())
    }
}

internal fun boundedBody(limit: Int): HttpResponse.BodyHandler<ByteArray?> =
    HttpResponse.BodyHandler { _ -> BoundedBodySubscriber(limit) }
