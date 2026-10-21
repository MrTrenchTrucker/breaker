package dev.breaker.dictation.stt.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A scripted [ServerHttp] for the engine tests. Each call to [exchange] records its request
 * and then consumes the next step of the script. Public members:
 * - the constructor: the steps, in order, and an optional [FakeClock] that
 *   [ScriptedHttp.Step.ReplyAfter] advances. Without a clock a ReplyAfter step fails loudly.
 * - [ScriptedHttp.requests]: every [ServerRequest] received, in order (method, url, headers, body, timeouts).
 * - [ScriptedHttp.Step.Reply]: answers with a status and a body text, encoded as UTF-8.
 * - [ScriptedHttp.Step.Throw]: throws the given throwable.
 * - [ScriptedHttp.Step.Stall]: completes [ScriptedHttp.Step.Stall.entered], then blocks until the
 *   abort handle fires, then returns the late reply if one was given, else throws an IOException.
 * - [ScriptedHttp.Step.ReplyAfter]: advances the clock by its delay, then answers.
 * - [ScriptedHttp.exchange]: runs the next step. With no step left it throws an AssertionError
 *   that names this double, so the test fails by name.
 */
internal class ScriptedHttp(
    steps: List<Step>,
    private val clock: FakeClock? = null,
) : ServerHttp {

    /** One scripted answer. */
    sealed class Step {
        /** Answers with [status] and [bodyText]. */
        class Reply(val status: Int, val bodyText: String) : Step()

        /** Throws [error] from the exchange. */
        class Throw(val error: Throwable) : Step()

        /** Blocks until the abort handle fires. [entered] completes just before the block. */
        class Stall(val lateReply: ServerReply? = null) : Step() {
            /** Completed when the exchange has started and is about to block. */
            val entered: CompletableDeferred<Unit> = CompletableDeferred()
        }

        /** Advances the clock by [advanceMs], then answers with [status] and [bodyText]. */
        class ReplyAfter(val advanceMs: Long, val status: Int, val bodyText: String) : Step()
    }

    private val script: List<Step> = steps.toList()
    private val nextIndex = AtomicInteger(0)
    private val seen = AtomicReference<List<ServerRequest>>(emptyList())

    /** Every request this double has received, in the order they arrived. */
    val requests: List<ServerRequest>
        get() = seen.get()

    override fun exchange(request: ServerRequest, abort: AbortHandle): ServerReply {
        seen.updateAndGet { it + request }
        val index = nextIndex.getAndIncrement()
        if (index >= script.size) {
            throw AssertionError("ScriptedHttp has no step left for request number ${index + 1}")
        }
        return when (val step = script[index]) {
            is Step.Reply -> ServerReply(step.status, step.bodyText.toByteArray(Charsets.UTF_8))
            is Step.Throw -> throw step.error
            is Step.Stall -> stall(step, abort)
            is Step.ReplyAfter -> {
                val fake = clock ?: throw IllegalStateException("ScriptedHttp needs a clock for ReplyAfter")
                fake.advanceMs(step.advanceMs)
                ServerReply(step.status, step.bodyText.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun stall(step: Step.Stall, abort: AbortHandle): ServerReply {
        val release = CompletableDeferred<Unit>()
        abort.onAbort { release.complete(Unit) }
        step.entered.complete(Unit)
        runBlocking { release.await() }
        return step.lateReply ?: throw IOException("stalled exchange was aborted")
    }
}
