package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading stamp: the service stamps each reading from the monotonic clock and reads no wall clock.
 */
internal class ProbeClockGateTest : ProbeGateTestBase() {

    /** The reading stamp is read from the raw service text: the view above strips the publish call, stamp included. */
    private val stampCall: Regex = Regex("""\btime\s*=\s*([^,\n]*),""")
    private val wallClock: Regex = Regex("""\bcurrentTimeMillis\b""")
    private val monotonicStamp: String = "System.nanoTime() / 1_000_000L"

    private fun stampValues(source: String): List<String> =
        stampCall.findAll(code(source)).map { it.groupValues[1].trim() }.toList()

    private fun wallClockProblems(source: String): List<String> {
        val problems: MutableList<String> = ArrayList()
        if (stampValues(source) != listOf(monotonicStamp)) {
            problems.add("the reading stamp is not the monotonic millisecond count")
        }
        if (count(wallClock, code(source)) > 0) {
            problems.add("the service reads the wall clock")
        }
        return problems
    }

    @Test
    fun `the service stamps each reading from the monotonic clock and never reads the wall clock`() {
        assertEquals(
            "commit/accessibility: the service clock check failed",
            emptyList<String>(),
            wallClockProblems(real(serviceFile)),
        )
    }

    @Test
    fun `a stamp from the wall clock, a stamp without the division or a second wall clock read is refused`() {
        val good: String = "class S {\n    fun publish() = publishLatest(\n        time = System.nanoTime() / 1_000_000L,\n    )\n}\n"
        assertEquals("commit/accessibility: the clock sample is not quiet", emptyList<String>(), wallClockProblems(good))
        val fires: Map<String, String> = mapOf(
            "the stamp on the wall clock" to edit(good, "System.nanoTime() / 1_000_000L", "System.currentTimeMillis()"),
            "a stamp without the division" to edit(good, " / 1_000_000L", ""),
            "a second wall clock read" to edit(good, "    )\n}", "    )\n    val t = System.currentTimeMillis()\n}"),
        )
        for ((label, source) in fires) {
            assertTrue("commit/accessibility: the clock check missed " + label, wallClockProblems(source).isNotEmpty())
        }
    }

    @Test
    fun `a comment or a string that names the wall clock is not refused`() {
        val good: String = "class S {\n    fun publish() = publishLatest(\n        time = System.nanoTime() / 1_000_000L,\n    )\n}\n"
        val quiet: Map<String, String> = mapOf(
            "a comment naming it" to edit(good, "        time = System.nanoTime()", "        // time = System.currentTimeMillis(),\n        time = System.nanoTime()"),
            "a string naming it" to edit(good, "    fun publish()", "    val note = \"time = System.currentTimeMillis(), wall\"\n    fun publish()"),
        )
        for ((label, source) in quiet) {
            assertEquals("commit/accessibility: the clock check flagged " + label, emptyList<String>(), wallClockProblems(source))
        }
    }
}
