package dev.breaker.dictation.sync

import kotlin.collections.ArrayList

/** A [PullTransport] for tests: it answers from a script and remembers every request. */
internal class ScriptedPullTransport private constructor(
    private val answers: List<PullTransportResult>,
    private val generator: ((Int) -> PullTransportResult)?,
) : PullTransport {
    /** Every request received, oldest first. */
    val requests = ArrayList<PullRequest>()
    private var index = 0

    /** Answers from [answers] in order; after the list ends, repeats the last answer. */
    constructor(answers: List<PullTransportResult>) : this(answers, null)

    /** Answers from [generator] called with the request number (0 for the first). */
    constructor(generator: (Int) -> PullTransportResult) : this(emptyList(), generator)

    override fun fetch(request: PullRequest): PullTransportResult {
        requests.add(request)
        return if (generator != null) {
            generator(index).also { index++ }
        } else {
            answers[minOf(index, answers.lastIndex)].also { index++ }
        }
    }
}
