package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one-shot background worker, because a JVM test cannot rely on a real thread failing to start.
 * Each rule reads host/AppThreads.kt with comments and string text removed by the shared scanner. The
 * class exists once; its start call is not inside a try block, so a thread that cannot start makes
 * submit throw; the block is called only inside the Runnable and never on the caller; the file still
 * holds the serial worker class. Each rule holds on the real file, is broken by at least two planted
 * edits, and stays quiet on harmless edits. The count of serial worker constructions is not repeated
 * here: SharedTakeWorkerGateTest owns it.
 */
internal class OneShotBackgroundScanTest {

    private val file: String = "kotlin/dev/breaker/dictation/host/AppThreads.kt"

    private class ScanRule(val name: String, val holds: (String) -> Boolean)

    private class Planted(val rule: String, val old: String, val new: String)

    private val startCall: Regex = Regex("""\.\s*start\s*\(\s*\)""")
    private val blockCall: Regex = Regex("""\bblock\s*\(\s*\)""")
    private val runnableOpen: Regex = Regex("""\bRunnable\s*\{""")
    private val tryOpen: Regex = Regex("""\btry\s*\{""")

    /** The index of the brace that closes the one opened at [open]. */
    private fun closeOf(code: String, open: Int): Int {
        var depth = 0
        for (at in open until code.length) {
            if (code[at] == '{') depth += 1
            if (code[at] == '}') depth -= 1
            if (depth == 0) return at
        }
        error("app: a brace is never closed")
    }

    /** The range from the opening brace to the closing brace of the class [name], or null. */
    private fun classBody(code: String, name: String): IntRange? {
        val found: MatchResult = Regex("""\bclass\s+$name\b""").find(code) ?: return null
        val open: Int = code.indexOf('{', found.range.last)
        return if (open < 0) null else open..closeOf(code, open)
    }

    private fun regionsOf(code: String, opener: Regex, within: IntRange): List<IntRange> =
        opener.findAll(code)
            .filter { it.range.first in within }
            .map { it.range.last..closeOf(code, it.range.last) }
            .toList()

    private fun inAny(regions: List<IntRange>, at: Int): Boolean = regions.any { at in it }

    private fun classCount(code: String, name: String): Int =
        Regex("""\bclass\s+$name\b""").findAll(code).count()

    private fun startsOnceOutsideTry(code: String): Boolean {
        val body: IntRange = classBody(code, "OneShotBackground") ?: return false
        val starts: List<MatchResult> = startCall.findAll(code).filter { it.range.first in body }.toList()
        val tries: List<IntRange> = regionsOf(code, tryOpen, body)
        return starts.size == 1 && !inAny(tries, starts[0].range.first)
    }

    private fun blockOnlyInRunnable(code: String): Boolean {
        val body: IntRange = classBody(code, "OneShotBackground") ?: return false
        val calls: List<MatchResult> = blockCall.findAll(code).filter { it.range.first in body }.toList()
        val runnables: List<IntRange> = regionsOf(code, runnableOpen, body)
        return calls.isNotEmpty() && calls.all { inAny(runnables, it.range.first) }
    }

    private val rules: List<ScanRule> = listOf(
        ScanRule("ONE_SHOT_CLASS_EXISTS_ONCE") { classCount(it, "OneShotBackground") == 1 },
        ScanRule("SERIAL_CLASS_IS_STILL_THERE") { classCount(it, "SerialBackground") == 1 },
        ScanRule("START_IS_CALLED_ONCE_OUTSIDE_ANY_TRY") { code -> startsOnceOutsideTry(code) },
        ScanRule("BLOCK_IS_CALLED_ONLY_INSIDE_THE_RUNNABLE") { code -> blockOnlyInRunnable(code) },
    )

    private val firing: List<Planted> = listOf(
        Planted(
            "ONE_SHOT_CLASS_EXISTS_ONCE",
            "internal class OneShotBackground(",
            "internal class OneShotGone(",
        ),
        Planted(
            "ONE_SHOT_CLASS_EXISTS_ONCE",
            "internal class OneShotBackground(",
            "internal class OneShotBackground(private val spare: Int) : Background {\n" +
                "    override fun submit(block: () -> Unit) {}\n}\n\n" +
                "internal class OneShotBackground(",
        ),
        Planted(
            "SERIAL_CLASS_IS_STILL_THERE",
            "internal class SerialBackground(",
            "internal class SerialGone(",
        ),
        Planted(
            "SERIAL_CLASS_IS_STILL_THERE",
            "internal class OneShotBackground(",
            "internal class SerialBackground(private val spare: Int) : Background {\n" +
                "    override fun submit(block: () -> Unit) {}\n}\n\n" +
                "internal class OneShotBackground(",
        ),
        Planted(
            "START_IS_CALLED_ONCE_OUTSIDE_ANY_TRY",
            "        worker.start()",
            "        try {\n            worker.start()\n        } catch (e: Exception) {\n        }",
        ),
        Planted(
            "START_IS_CALLED_ONCE_OUTSIDE_ANY_TRY",
            "        worker.start()",
            "        worker.start()\n        worker.start()",
        ),
        Planted("START_IS_CALLED_ONCE_OUTSIDE_ANY_TRY", "        worker.start()", "        work.run()"),
        Planted(
            "BLOCK_IS_CALLED_ONLY_INSIDE_THE_RUNNABLE",
            "        worker.start()",
            "        block()\n        worker.start()",
        ),
        Planted("BLOCK_IS_CALLED_ONLY_INSIDE_THE_RUNNABLE", "val work = Runnable {", "val work = Thread {"),
        Planted(
            "BLOCK_IS_CALLED_ONLY_INSIDE_THE_RUNNABLE",
            "        val work = Runnable {",
            "        block()\n        val work = Runnable {",
        ),
    )

    private val quiet: List<Planted> = listOf(
        Planted(
            "",
            "        worker.start()",
            "        // try { worker.start() } would hide a failure to start\n        worker.start()",
        ),
        Planted(
            "",
            "        val work = Runnable {",
            "        // block() is called only in the runnable\n        val work = Runnable {",
        ),
        Planted(
            "",
            "        worker.isDaemon = true",
            "        worker.isDaemon = true\n        val note = \"try { start() } class SerialBackground\"",
        ),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a one shot scan sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val raw: String = AppSourceFiles.mainFile(file)
        return AppSourceFiles.strip(if (old == null) raw else edit(raw, old, new)).code
    }

    @Test
    fun `every one shot rule holds on the real file`() {
        assertTrue("app: the thread file was read as empty", codeOf().isNotBlank())
        val broken: List<String> = rules.filter { !it.holds(codeOf()) }.map { it.name }
        assertTrue("app: the one shot worker breaks rules $broken", broken.isEmpty())
    }

    @Test
    fun `each one shot rule has at least two planted edits and each one fires`() {
        val names: List<String> = rules.map { it.name }
        assertEquals(
            "app: a firing sample belongs to an unknown rule",
            names.toSet(),
            firing.map { it.rule }.toSet(),
        )
        for (rule in rules) {
            val count: Int = firing.count { it.rule == rule.name }
            assertTrue(
                "app: one shot rule ${rule.name} needs at least two planted edits, has $count",
                count >= 2,
            )
        }
        for (sample in firing) {
            val rule: ScanRule = rules.single { it.name == sample.rule }
            assertFalse(
                "app: one shot rule ${sample.rule} must fire on '${sample.old}' => '${sample.new}'",
                rule.holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `a harmless edit leaves every one shot rule true`() {
        for (sample in quiet) {
            for (rule in rules) {
                assertTrue(
                    "app: one shot rule ${rule.name} must stay quiet on the harmless edit '${sample.old}'",
                    rule.holds(codeOf(sample.old, sample.new)),
                )
            }
        }
    }

    @Test
    fun `a one shot scan edit whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue(
            "app: the failure must name the lost text, got ${thrown.message}",
            thrown.message!!.contains("no such text"),
        )
    }
}
