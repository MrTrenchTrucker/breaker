package dev.breaker.dictation.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module's tests pass on one core with little memory and in parallel on many.
 *
 * That only holds when no test waits on a clock, a sleep, a timer or a thread of
 * its own, and when none of them touches the one process-wide holder a
 * text-insert mechanism uses. A test that needs to wait is a design problem to raise, never a
 * timeout to add, with one exception: a time limit on each of the three posted main
 * thread test classes, a safety net that fails a test whose hop call never returns,
 * never what a test measures. Every test source is scanned, this one and the scanner
 * included: the names the rules look for appear here only inside strings, which a scan
 * of code does not read. The one exception to the thread rule is a single thread
 * constructor in one named test file. Both allowances are described below.
 */
internal class TestRulesScanTest {

    /**
     * The only test file that may build a thread, and how many thread constructors it must
     * hold. The reason: the deadline can only fire while a block is running if a second
     * thread runs the block, and the handshake between the two uses signals only, with no
     * sleeps or clocks. The allowance is exact: a file with none leaves an unused exception,
     * a file with two has a second thread nobody ruled on, and any other file is not covered.
     * Every other rule applies to this file unchanged.
     */
    private val threadAllowedFile: String = "PostedMainThreadClaimTest.kt"
    private val threadAllowedCount: Int = 1
    private val threadRule: String = "a thread constructor"

    /**
     * The three test files that may each hold one time limit, written exactly as
     * `Timeout.seconds(10)`. The allowance is exact: a file with none leaves an unused exception,
     * a second use, another value or another form is not covered, and no other file is covered.
     * Every other rule applies to these files unchanged.
     */
    private val hopFile: String = "PostedMainThreadTest.kt"
    private val hopLimitFiles: Set<String> = setOf(hopFile, "PostedMainThreadClaimTest.kt", "PostedMainThreadServiceTest.kt")
    private val hopLimitExact: Regex = Regex("""\bTimeout\s*\.\s*seconds\s*\(\s*10\s*\)""")
    private val hopLimitRule: String = "a test time limit"

    private val rules: Map<String, Regex> = mapOf(
        "a sleep" to Regex("""\bThread\s*\.\s*sleep\b"""),
        "a sleep call" to Regex("""\.\s*sleep\s*\("""),
        "a delay" to Regex("""\bdelay\s*\("""),
        "a timeout" to Regex("""\bwithTimeout(OrNull)?\b"""),
        "runBlocking" to Regex("""\brunBlocking\b"""),
        "an executor" to Regex("""\bExecutor[A-Za-z]*\b"""),
        "a millisecond clock" to Regex("""\bSystem\s*\.\s*currentTimeMillis\b"""),
        "a nanosecond clock" to Regex("""\bSystem\s*\.\s*nanoTime\b"""),
        "a wall clock read" to Regex("""\b(Instant|LocalDateTime|LocalDate|ZonedDateTime|OffsetDateTime)\s*\.\s*now\b"""),
        "Dispatchers" to Regex("""\bDispatchers\b"""),
        "GlobalScope" to Regex("""\bGlobalScope\b"""),
        "a volatile field" to Regex("""@\s*Volatile\b"""),
        "a synchronized block" to Regex("""\b[sS]ynchronized\b"""),
        "the process-wide holder" to Regex("""\bFocusedFieldHolder\b"""),
        threadRule to Regex("""\bThread\s*[({]"""),
        "a thread block" to Regex("""\bthread\s*[({]"""),
        "a timer" to Regex("""\bTimer(Task)?\b"""),
        hopLimitRule to Regex("""\bTimeout\s*(\.\s*[a-z]\w*\s*)?\(|@\s*([\p{L}\p{N}_]+\s*\.\s*)*Test\s*\([^)]*\btimeout\s*="""),
    )

    private val samples: Map<String, String> = mapOf(
        "a sleep" to "class T { fun f() { Thread.sleep(1) } }",
        "a sleep call" to "class T { fun f() { TimeUnit.SECONDS.sleep(1) } }",
        "a delay" to "class T { suspend fun f() { delay(5) } }",
        "a timeout" to "class T { suspend fun f() { withTimeout(5) { 1 } } }",
        "runBlocking" to "class T { fun f() = runBlocking { 1 } }",
        "an executor" to "class T { val e = Executors.newFixedThreadPool(1) }",
        "a millisecond clock" to "class T { val t = System.currentTimeMillis() }",
        "a nanosecond clock" to "class T { val t = System.nanoTime() }",
        "a wall clock read" to "class T { val t = Instant.now() }",
        "Dispatchers" to "class T { val d = Dispatchers.Default }",
        "GlobalScope" to "class T { val s = GlobalScope }",
        "a volatile field" to "class T { @Volatile var v: Int = 0 }",
        "a synchronized block" to "class T { fun f() = synchronized(this) { 1 } }",
        "the process-wide holder" to "class T { val r = FocusedFieldHolder.registry }",
        threadRule to "class T { val t = Thread(r) }",
        "a thread block" to "class T { val t = thread { run() } }",
        "a timer" to "class T { val t = Timer() }",
        hopLimitRule to "class T { val l = Timeout.seconds(10) }",
    )

    private val ownFiles: Set<String> = setOf(
        "SourceFiles.kt",
        "PureFilesScanTest.kt",
        "AndroidConfinementTest.kt",
        "ConcurrencyRuleScanTest.kt",
        "TestRulesScanTest.kt",
    )

    /**
     * The offences of [sources] under every rule, with one allowance applied: the allowed file
     * holding exactly the allowed number of thread constructors is not reported for them. When
     * the allowed file is present and holds none, the unused allowance is reported.
     */
    private fun offencesAllowingTheHelperThread(sources: Map<String, String>): List<String> {
        val found: List<String> = SourceFiles.offences(sources, rules)
        val covered = "$threadAllowedFile: $threadRule x$threadAllowedCount"
        val result: MutableList<String> = found.filter { it != covered }.toMutableList()
        val holdsThread: Boolean = found.any { it.startsWith("$threadAllowedFile: $threadRule x") }
        if (threadAllowedFile in sources && !holdsThread) {
            result.add("$threadAllowedFile: $threadRule x0, the allowance is unused")
        }
        return result
    }

    /** [found] with the time limit allowance applied; a hop file in [sources] with no use is reported as unused. */
    private fun withoutTheHopTimeLimit(found: List<String>, sources: Map<String, String>): List<String> {
        val result: MutableList<String> = found.toMutableList()
        for (file in hopLimitFiles.filter { it in sources }) {
            val uses: Int = SourceFiles.countIn(sources.getValue(file), rules.getValue(hopLimitRule))
            val allowed: Int = SourceFiles.countIn(sources.getValue(file), hopLimitExact)
            when {
                uses == 1 && allowed == 1 -> result.remove("$file: $hopLimitRule x1")
                uses == 0 -> result.add("$file: $hopLimitRule x0, the allowance is unused")
            }
        }
        return result
    }

    private fun offencesUnderTheAllowances(sources: Map<String, String>): List<String> = withoutTheHopTimeLimit(offencesAllowingTheHelperThread(sources), sources)

    @Test
    fun `no test source waits on a clock, builds a thread or names the process wide holder`() {
        val tests: Map<String, String> = SourceFiles.testSources()
        assertTrue("commit: the test scan did not reach the scan files themselves", tests.keys.containsAll(ownFiles))
        assertTrue("commit: the test scan did not reach the file with the allowed thread", threadAllowedFile in tests.keys)
        assertTrue("commit: the test scan did not reach the three files with the allowed time limit", tests.keys.containsAll(hopLimitFiles))
        assertEquals(
            "commit: a test source breaks the one-core, in-parallel rule",
            emptyList<String>(),
            offencesUnderTheAllowances(tests),
        )
    }

    @Test
    fun `each forbidden test shape is reported on its own sample`() {
        assertEquals("commit: a test rule has no sample", rules.keys, samples.keys)
        for ((label, sample) in samples) {
            val found: List<String> = SourceFiles.offences(mapOf("Sample.kt" to sample), rules)
            assertTrue(
                "commit: the test scan did not report $label, found $found",
                found.any { it.startsWith("Sample.kt: $label x") },
            )
        }
    }

    @Test
    fun `the same names in comments, strings or longer words are not reported`() {
        val source: String = "// Thread.sleep(1) delay(2) FocusedFieldHolder\n" +
            "/** Executors, Dispatchers and GlobalScope are not allowed here. */\n" +
            "class T {\n" +
            "    val a = \"System.currentTimeMillis() withTimeout runBlocking @Volatile\"\n" +
            "    val b = \"\"\"Thread(x) Timer() synchronized(y) Instant.now()\"\"\"\n" +
            "    val c = MainThread(1)\n" +
            "    val d = InlineMainThread(2)\n" +
            "    val e = FocusedFieldHolderFake(3)\n" +
            "    val f = candelay(4)\n" +
            "    val g = Thread.currentThread()\n" +
            "    val h = DispatchersX\n" +
            "}\n"
        assertEquals(
            "commit: a test rule flagged prose, a literal, a longer name or the current thread call",
            emptyList<String>(),
            SourceFiles.offences(mapOf("Sample.kt" to source), rules),
        )
    }

    @Test
    fun `a name written in a template hole is code and is reported`() {
        val source: String = "class T { val s = \"waited \${System.nanoTime()}\" }"
        assertEquals(
            "commit: a clock read inside a template hole was not reported",
            listOf("Sample.kt: a nanosecond clock x1"),
            SourceFiles.offences(mapOf("Sample.kt" to source), rules),
        )
    }

    @Test
    fun `a main thread test double that starts a thread is reported by the thread rules`() {
        val source: String = "class T { val m = object : MainThread {\n" +
            "    override fun <T> call(block: () -> T): T { val t = Thread { block() }; t.start(); t.join(); return block() }\n" +
            "} }"
        val found: List<String> = SourceFiles.offences(mapOf("Sample.kt" to source), rules)
        assertTrue("commit: a real thread inside a main thread double was not reported, found $found", found.any { it.contains("a thread constructor") })
    }

    @Test
    fun `the allowed file with exactly one thread constructor is not reported`() {
        val source: String = "class T { val t = Thread(r) }"
        assertEquals(
            "commit: the one allowed thread constructor was reported",
            emptyList<String>(),
            offencesAllowingTheHelperThread(mapOf(threadAllowedFile to source)),
        )
    }

    @Test
    fun `a second thread constructor in the allowed file is still reported`() {
        val source: String = "class T { val a = Thread(r); val b = Thread(s) }"
        assertEquals(
            "commit: a second thread constructor in the allowed file was not reported",
            listOf("$threadAllowedFile: $threadRule x2"),
            offencesAllowingTheHelperThread(mapOf(threadAllowedFile to source)),
        )
    }

    @Test
    fun `the allowed file with no thread constructor reports the unused allowance`() {
        val source: String = "class T { val t = 1 }"
        assertEquals(
            "commit: an allowance with no thread constructor behind it was not reported",
            listOf("$threadAllowedFile: $threadRule x0, the allowance is unused"),
            offencesAllowingTheHelperThread(mapOf(threadAllowedFile to source)),
        )
    }

    @Test
    fun `one thread constructor in any other test file is still reported`() {
        val source: String = "class T { val t = Thread(r) }"
        val sources: Map<String, String> = mapOf(
            "OtherTest.kt" to source,
            "PostedMainThreadTest.kt" to source,
        )
        assertEquals(
            "commit: a thread constructor outside the allowed file was not reported",
            listOf("OtherTest.kt: $threadRule x1", "PostedMainThreadTest.kt: $threadRule x1"),
            offencesAllowingTheHelperThread(sources),
        )
    }

    @Test
    fun `the allowed file is still reported for every other rule`() {
        val source: String = "class T { val t = Thread(r); fun f() { Thread.sleep(1) } }"
        assertEquals(
            "commit: the allowance spread to the other rules or hid a sleep",
            listOf("$threadAllowedFile: a sleep x1", "$threadAllowedFile: a sleep call x1"),
            offencesAllowingTheHelperThread(mapOf(threadAllowedFile to source)),
        )
    }

    private fun hopOffences(file: String, vararg uses: String): List<String> = offencesUnderTheAllowances(mapOf(file to "class T { " + uses.joinToString("; ") + " }"))

    @Test
    fun `the hop test files with exactly one ten second limit each are not reported`() {
        val use = "val l = Timeout.seconds(10)"
        val sources: Map<String, String> = hopLimitFiles.associateWith { "class T { $use }" } + (threadAllowedFile to "class T { $use; val t = Thread(r) }")
        assertEquals("commit: the one allowed time limit in a hop file was reported", emptyList<String>(), offencesUnderTheAllowances(sources))
    }

    @Test
    fun `a second time limit in a hop test file is still reported`() {
        val found: List<String> = hopOffences(hopFile, "val a = Timeout.seconds(10)", "val b = Timeout.seconds(10)")
        assertEquals("commit: a second time limit in a hop file was not reported", listOf("$hopFile: $hopLimitRule x2"), found)
    }

    @Test
    fun `a hop test file with another limit value or another form is still reported`() {
        for (use in listOf("Timeout.seconds(60)", "Timeout.seconds(10L)", "Timeout.millis(10000)", "Timeout(10, TimeUnit.SECONDS)", "Timeout.create(10)")) {
            assertEquals("commit: a hop file with $use was not reported", listOf("$hopFile: $hopLimitRule x1"), hopOffences(hopFile, "val l = $use"))
        }
    }

    @Test
    fun `a hop test file with no time limit reports the unused allowance`() {
        val file = "PostedMainThreadServiceTest.kt"
        assertEquals("commit: a hop file with no time limit behind its allowance was not reported", listOf("$file: $hopLimitRule x0, the allowance is unused"), hopOffences(file, "val t = 1"))
    }

    @Test
    fun `the time limit allowance does not cover another rule in the same file`() {
        val found: List<String> = hopOffences(hopFile, "val l = Timeout.seconds(10)", "val s = Thread.sleep(1)")
        assertEquals("commit: the allowance spread to the other rules or hid a sleep", listOf("$hopFile: a sleep x1", "$hopFile: a sleep call x1"), found)
    }

    @Test
    fun `the longer names and the type name alone are not reported as a time limit`() {
        val source: String = "import org.junit.rules.Timeout\nimport java.util.concurrent.TimeoutException\n" +
            "class T { val a: Timeout? = null; val timeoutMillis = 5; val b = MyTimeout(1); val c = \"Timeout.seconds(10)\"\n" +
            "    fun f() { try { g() } catch (e: TimeoutException) { throw TestTimedOutException(TimeoutException(\"late\")) } }\n    // Timeout.seconds(10)\n" +
            "    @Test(expected = X::class) fun h() {} }"
        assertEquals("commit: a longer name, an import or a type name was reported as a time limit", emptyList<String>(), SourceFiles.offences(mapOf("Sample.kt" to source), rules))
    }

    @Test
    fun `a time limit in any other test file, or on the test annotation in any file, is still reported`() {
        val call = "class T { val l = Timeout.seconds(10) }"
        val annotated = "class T { @Test(timeout = 5) fun f() {} }"
        val sources: Map<String, String> = mapOf("OtherTest.kt" to call, "PostedMainThreadHelperTest.kt" to annotated, hopFile to annotated)
        assertEquals(
            "commit: a time limit outside the hop files, or on the test annotation, was not reported",
            listOf("OtherTest.kt: $hopLimitRule x1", "PostedMainThreadHelperTest.kt: $hopLimitRule x1", "$hopFile: $hopLimitRule x1"),
            offencesUnderTheAllowances(sources),
        )
    }
}
