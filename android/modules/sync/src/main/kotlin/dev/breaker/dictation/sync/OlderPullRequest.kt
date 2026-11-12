package dev.breaker.dictation.sync

import dev.breaker.dictation.core.port.OlderBound

/** One older-rows request: the fixed path and the `older_than` query. Only [path] and [query] are public. */
class OlderPullRequest private constructor(
    /** The `older_than` value: `<ms>` or `<ms>-<change>`. */
    private val olderThan: String,
) {

    companion object {
        private const val PATH = "/v1/transcriptions/older"

        /** Builds the request for [bound]. */
        internal fun forBound(bound: OlderBound): OlderPullRequest = OlderPullRequest(bound.render())
    }

    /** The fixed older-rows path. */
    fun path(): String = PATH

    /** The query text: `older_than=` and the bound. The bound is digits and one dash, so nothing needs encoding. */
    fun query(): String = "older_than=$olderThan"
}
