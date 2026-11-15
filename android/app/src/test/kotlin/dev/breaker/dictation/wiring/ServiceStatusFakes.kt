package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.StreamReply

/** A fake [ServiceStatusAsker]: a script of answers, and the count of the asks it served. */
internal class FakeAsker : ServiceStatusAsker {
    val answers = ArrayDeque<ServiceStatusAnswer>()
    var asks = 0
        private set

    override fun ask(): ServiceStatusAnswer {
        asks++
        return answers.removeFirstOrNull() ?: ServiceStatusAnswer.Failed
    }
}

/** A fake [HttpTransport]: it records every request and answers with a canned reply or a throw. No network. */
internal class FakeStatusTransport : HttpTransport {
    val requests = mutableListOf<CallRequest>()
    var reply: BytesReply = BytesReply(200, ByteArray(0))
    var failure: Exception? = null

    override fun send(request: CallRequest): BytesReply {
        requests.add(request)
        val thrown = failure
        if (thrown != null) throw thrown
        return reply
    }

    override fun open(request: CallRequest): StreamReply =
        throw UnsupportedOperationException("open is not used by the status probe")
}

/**
 * The core clock over the fake post's own clock, so a test advances the two-hour rule by hand: the
 * schedule reads the time and the queue from the same source, as it does from one looper in the app.
 */
internal fun statusClock(post: FakeDelayedPost): Clock = Clock { post.now }
