package dev.breaker.dictation.wiring

import dev.breaker.shared.api.ServiceStatusReply

/**
 * One answer to a service-status ask.
 *
 * [Answered] carries the body of `GET /v1/status` exactly as the shared contract reads it, so the
 * caller can feed it to the sentence decision as it is. [Failed] is every other outcome: no
 * addressed session, a refusal of the http module, a connection problem, a status that is not 200,
 * a reply over its cap, a body that is not UTF-8, or a body that does not parse. A failure is a
 * state the caller shows; it is never a throw and never a sentence.
 */
sealed interface ServiceStatusAnswer {
    /** The server answered 200 and the reply parsed. */
    data class Answered(val reply: ServiceStatusReply) : ServiceStatusAnswer

    /** The ask produced no usable reply. */
    data object Failed : ServiceStatusAnswer
}

/**
 * The service-status ask as a port: the caller supplies the implementation.
 *
 * This app supplies [ServiceStatusProbe] over the existing HttpTransport, and a test supplies a
 * fake. The port returns [ServiceStatusAnswer], never a sentence: the sentence belongs to the home
 * screen (the S-1 decision), and the asker must not be the one that writes it.
 */
fun interface ServiceStatusAsker {
    /** Asks once and returns the answer. It never throws: a failed ask is [ServiceStatusAnswer.Failed]. */
    fun ask(): ServiceStatusAnswer
}
