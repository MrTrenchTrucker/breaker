package dev.breaker.dictation.sync

import dev.breaker.dictation.core.port.ConnectivityProbe

/**
 * A [ConnectivityProbe] for tests: it answers with [reachable], which a test can change, and
 * counts and records every call.
 */
internal class ScriptedProbe(var reachable: Boolean = true, private val log: CallLog = CallLog()) :
    ConnectivityProbe {

    /** How many times [isServerReachable] was called. */
    var calls: Int = 0
        private set

    override fun isServerReachable(): Boolean {
        calls++
        log.add("probe")
        return reachable
    }
}
