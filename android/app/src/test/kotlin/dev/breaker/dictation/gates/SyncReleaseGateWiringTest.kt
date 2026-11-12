package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one wiring that keeps sync off in a release build, because no JVM test can run
 * BreakerApp. The app reads one decision, `syncOn`, once from the runtime debuggable flag. The
 * sync worker starts and stops only under it, and the history store the app exposes is the plain
 * store unless it is on. The rules read the real BreakerApp.kt with comments and literal text
 * removed by the shared scanner. Each rule holds on the real file, fires on its edited samples
 * and stays quiet on harmless edits.
 */
internal class SyncReleaseGateWiringTest {

    private val app: String = "kotlin/dev/breaker/dictation/BreakerApp.kt"

    private class Rule(val name: String, val pattern: String, val times: Int = 1) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
    }

    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val old: String, val new: String)

    private val decide: String = "syncAllowed(applicationInfo.flags)"
    private val startCall: String = "if (syncOn) syncWorker.onStart()"
    private val stopCall: String = "if (syncOn) syncWorker.stop()"
    private val storeGetter: String = "if (syncOn) decoratedHistoryStore else history"
    private val pullTransportCall: String = "if (syncOn) pullTransport(settingsStore, compositionRoot.addressedSession)"
    private val pullSinkArg: String = "pullSink = if (syncOn) history else null"
    private val pullTransportArg: String = "pullTransport = pullTransportForSync"

    private val rules: List<Rule> = listOf(
        Rule("SYNC_ON_IS_THE_RUNTIME_DEBUGGABLE_DECISION",
            """\bval\s+syncOn\s*:\s*Boolean\s+by\s+lazy\s*\{\s*syncAllowed\s*\(\s*applicationInfo\s*\.\s*flags\s*\)\s*\}"""),
        Rule("SYNC_WORKER_START_IS_UNDER_SYNC_ON", """\bif\s*\(\s*syncOn\s*\)\s*syncWorker\s*\.\s*onStart\s*\(\s*\)"""),
        Rule("SYNC_WORKER_START_IS_CALLED_ONCE", """\bsyncWorker\s*\.\s*onStart\b"""),
        Rule("SYNC_WORKER_STOP_IS_UNDER_SYNC_ON", """\bif\s*\(\s*syncOn\s*\)\s*syncWorker\s*\.\s*stop\s*\(\s*\)"""),
        Rule("SYNC_WORKER_STOP_IS_CALLED_ONCE", """\bsyncWorker\s*\.\s*stop\b"""),
        Rule("HISTORY_STORE_IS_PLAIN_UNLESS_SYNC_ON",
            """\bval\s+historyStore\s*:\s*HistoryStore\s+get\s*\(\s*\)\s*=\s*if\s*\(\s*syncOn\s*\)\s*decoratedHistoryStore\s+else\s+history\b"""),
        Rule("PULL_TRANSPORT_IS_UNDER_SYNC_ON",
            """\bif\s*\(\s*syncOn\s*\)\s*pullTransport\s*\(\s*settingsStore\s*,\s*compositionRoot\s*\.\s*addressedSession\s*\)"""),
        Rule("PULL_SINK_IS_UNDER_SYNC_ON",
            """\bpullSink\s*=\s*if\s*\(\s*syncOn\s*\)\s*history\s+else\s+null\b"""),
        Rule("PULL_TRANSPORT_IS_PASSED_TO_THE_QUEUE",
            """\bpullTransport\s*=\s*pullTransportForSync\b"""),
        Rule("SYNC_WORKER_HAS_FIVE_REFERENCES", """\bsyncWorker\b""", times = 5),
        Rule("DECORATED_STORE_HAS_TWO_REFERENCES", """\bdecoratedHistoryStore\b""", times = 2),
    )

    private val firing: List<Sample> = listOf(
        Sample("SYNC_ON_IS_THE_RUNTIME_DEBUGGABLE_DECISION", decide, "true"),
        Sample("SYNC_ON_IS_THE_RUNTIME_DEBUGGABLE_DECISION", decide, "syncAllowed(applicationInfo.targetSdkVersion)"),
        Sample("SYNC_ON_IS_THE_RUNTIME_DEBUGGABLE_DECISION", "private val syncOn: Boolean by lazy {", "private val syncOn: Boolean = run {"),
        Sample("SYNC_WORKER_START_IS_UNDER_SYNC_ON", startCall, "syncWorker.onStart()"),
        Sample("SYNC_WORKER_START_IS_UNDER_SYNC_ON", startCall, "if (!syncOn) syncWorker.onStart()"),
        Sample("SYNC_WORKER_START_IS_UNDER_SYNC_ON", startCall, "if (true) syncWorker.onStart()"),
        Sample("SYNC_WORKER_START_IS_CALLED_ONCE", startCall, startCall + "\n        syncWorker.onStart()"),
        Sample("SYNC_WORKER_START_IS_CALLED_ONCE", startCall, "Unit"),
        Sample("SYNC_WORKER_STOP_IS_UNDER_SYNC_ON", stopCall, "syncWorker.stop()"),
        Sample("SYNC_WORKER_STOP_IS_UNDER_SYNC_ON", stopCall, "if (!syncOn) syncWorker.stop()"),
        Sample("SYNC_WORKER_STOP_IS_UNDER_SYNC_ON", stopCall, "if (true) syncWorker.stop()"),
        Sample("SYNC_WORKER_STOP_IS_CALLED_ONCE", stopCall, stopCall + "\n        syncWorker.stop()"),
        Sample("SYNC_WORKER_STOP_IS_CALLED_ONCE", stopCall, "Unit"),
        Sample("HISTORY_STORE_IS_PLAIN_UNLESS_SYNC_ON", storeGetter, "decoratedHistoryStore"),
        Sample("HISTORY_STORE_IS_PLAIN_UNLESS_SYNC_ON", storeGetter, "if (syncOn) history else decoratedHistoryStore"),
        Sample("HISTORY_STORE_IS_PLAIN_UNLESS_SYNC_ON", storeGetter, "if (!syncOn) decoratedHistoryStore else history"),
        Sample("SYNC_WORKER_HAS_FIVE_REFERENCES", startCall, startCall + "\n        syncWorker.onSave()"),
        Sample("SYNC_WORKER_HAS_FIVE_REFERENCES", "worker = syncWorker)", "worker = syncWorker)\n        syncWorker.onSave()"),
        Sample("DECORATED_STORE_HAS_TWO_REFERENCES", storeGetter, "if (syncOn) decoratedHistoryStore else decoratedHistoryStore"),
        Sample("DECORATED_STORE_HAS_TWO_REFERENCES", "private val syncOn: Boolean by lazy {", "private val other = decoratedHistoryStore\n    private val syncOn: Boolean by lazy {"),
        Sample("PULL_TRANSPORT_IS_UNDER_SYNC_ON", pullTransportCall, "pullTransport(settingsStore, compositionRoot.addressedSession)"),
        Sample("PULL_TRANSPORT_IS_UNDER_SYNC_ON", pullTransportCall, "if (!syncOn) pullTransport(settingsStore, compositionRoot.addressedSession)"),
        Sample("PULL_TRANSPORT_IS_UNDER_SYNC_ON", pullTransportCall, "if (true) pullTransport(settingsStore, compositionRoot.addressedSession)"),
        Sample("PULL_SINK_IS_UNDER_SYNC_ON", pullSinkArg, "pullSink = history"),
        Sample("PULL_SINK_IS_UNDER_SYNC_ON", pullSinkArg, "pullSink = if (syncOn) null else history"),
        Sample("PULL_SINK_IS_UNDER_SYNC_ON", pullSinkArg, "pullSink = if (!syncOn) history else null"),
        Sample("PULL_TRANSPORT_IS_PASSED_TO_THE_QUEUE", pullTransportArg, "pullTransport = null"),
        Sample("PULL_TRANSPORT_IS_PASSED_TO_THE_QUEUE", pullTransportArg, "pullSink = history"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet(startCall, "if ( syncOn )   syncWorker . onStart ( )"),
        Quiet(startCall, startCall + " // syncWorker.onStart() syncWorker.stop() decoratedHistoryStore"),
        Quiet(stopCall, "if ( syncOn )\n            syncWorker\n                .stop()"),
        Quiet(storeGetter, "if ( syncOn ) decoratedHistoryStore   else   history"),
        Quiet(decide, "syncAllowed( applicationInfo . flags )"),
        Quiet("private val syncOn: Boolean by lazy {", "/* syncWorker decoratedHistoryStore */ private val syncOn: Boolean by lazy {"),
        Quiet("\"start-up purge failed\"", "\"start-up purge failed syncWorker.onStart() decoratedHistoryStore\""),
        Quiet(pullTransportCall, "if ( syncOn )   pullTransport ( settingsStore , compositionRoot . addressedSession )"),
        Quiet(pullSinkArg, "pullSink = if ( syncOn )   history   else   null"),
        Quiet(pullTransportArg, "pullTransport =   pullTransportForSync"),
        Quiet("private val pullTransportForSync: PullTransport? by lazy {",
            "/* pullTransport(settingsStore, compositionRoot.addressedSession) pullSink = if (syncOn) history else null */ private val pullTransportForSync: PullTransport? by lazy {"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a sync release gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.mainFile(app)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `every sync release gate rule holds on the real file`() {
        assertTrue("app: $app was read as empty", codeOf().isNotBlank())
        for (rule in rules) {
            assertTrue("app: $app breaks rule ${rule.name}", rule.holds(codeOf()))
        }
    }

    @Test
    fun `the firing samples cover each sync release gate rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a sync release gate rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: sync release gate rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every sync release gate rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: sync release gate rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.old, sample.new)))
        }
    }

    @Test
    fun `no sync release gate rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules) {
                assertTrue("app: sync release gate rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val failure = assertThrows(IllegalStateException::class.java) { codeOf("no such text here", "x") }
        assertTrue("app: the failure names the lost text", failure.message!!.contains("no such text here"))
    }
}
