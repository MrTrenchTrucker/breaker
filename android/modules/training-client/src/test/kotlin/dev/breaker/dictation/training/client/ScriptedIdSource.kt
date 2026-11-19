package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.port.IdSource

/** An id source that hands out the scripted ids in order and counts the calls. Asking past the script fails the test. */
class ScriptedIdSource(vararg scripted: String) : IdSource {
    private val queue = ArrayDeque(scripted.toList())

    var calls = 0
        private set

    override fun newId(): String {
        calls += 1
        check(queue.isNotEmpty()) { "the id script is used up after ${calls - 1} calls" }
        return queue.removeFirst()
    }
}
