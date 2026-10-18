package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module's tests pass on one core with little memory and in parallel on many.
 *
 * That only holds when no test waits on a clock, a sleep, a timer, a time limit or a
 * thread of its own, draws a random number, or touches the one process-wide holder a
 * text-insert mechanism publishes into. There is no allowance of any kind in this
 * module: a test that needs to wait is a design problem to raise, never a time limit
 * to add, and no test file may build a thread.
 *
 * Every test source is scanned, the scanner files included. The names the rules look
 * for appear in those files only inside strings, which a scan of code does not read.
 * [ownFiles] hold the scanners and their helper; [insertTestFiles] are the insert core's tests
 * and doubles; [moduleTestFiles] are the focused field, adapter and file set tests. All three
 * sets must be found, so a scan of the wrong folder, or one that lost a file, fails.
 */
internal class TestRulesScanTest {

    private val ownFiles: Set<String> = setOf(
        "SourceFiles.kt",
        "XmlFiles.kt",
        "PureFilesScanTest.kt",
        "PrivacyScanTest.kt",
        "TestRulesScanTest.kt",
        "ManifestGateTest.kt",
        "ConfigGateTest.kt",
    )

    private val insertTestFiles: Set<String> = setOf(
        "InsertPlanMergeTest.kt",
        "InsertPlanSelectionTest.kt",
        "InsertPlanRefusalTest.kt",
        "InsertPlanRedactionTest.kt",
        "FakeFieldNode.kt",
        "FakeFieldNodeTest.kt",
    )

    private val moduleTestFiles: Set<String> = setOf(
        "AdapterGateTest.kt",
        "AdapterGateSamples.kt",
        "AdapterMappingGateTest.kt",
        "FakeFieldNodeSwitchesTest.kt",
        "NodeFocusedFieldInsertTest.kt",
        "NodeFocusedFieldRefusalTest.kt",
        "NodeFocusedFieldSetTextRefusalTest.kt",
        "NodeFocusedFieldReleaseTest.kt",
        "NodeFocusedFieldPrivacyTest.kt",
        "NodeFocusedFieldErrorTest.kt",
        "ModuleFilesGateTest.kt",
    )

    /** One line per pinned name that [found] lacks; none means every pinned file was scanned. */
    private fun missingPinned(found: Set<String>, pinned: Set<String>): List<String> =
        pinned.filter { it !in found }.sorted().map { "pinned test file " + it + " was not scanned" }

    private val timeLimitRule: String = "a test time limit"

    private val rules: Map<String, Regex> = mapOf(
        "a sleep" to Regex("""\bThread\s*\.\s*sleep\b"""),
        "a sleep call" to Regex("""\.\s*sleep\s*\("""),
        "a delay" to Regex("""\bdelay\s*\("""),
        "a timeout" to Regex("""\bwithTimeout(OrNull)?\b"""),
        "runBlocking" to Regex("""\brunBlocking\b"""),
        "an executor" to Regex("""\bExecutor[A-Za-z]*\b"""),
        "a pool or future" to Regex("""\b(ForkJoinPool|ThreadPoolExecutor|CompletableFuture|FutureTask)\b"""),
        "a lock or latch" to Regex("""\b(CountDownLatch|CyclicBarrier|Semaphore|Phaser|ReentrantLock|ReentrantReadWriteLock|StampedLock)\b"""),
        "a millisecond clock" to Regex("""\bSystem\s*\.\s*currentTimeMillis\b"""),
        "a nanosecond clock" to Regex("""\bSystem\s*\.\s*nanoTime\b"""),
        "a wall clock read" to Regex("""\b(Instant|LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime|OffsetTime)\s*\.\s*now\b"""),
        "a clock object" to Regex("""\bClock\b"""),
        "Dispatchers" to Regex("""\bDispatchers\b"""),
        "GlobalScope" to Regex("""\bGlobalScope\b"""),
        "a volatile field" to Regex("""@\s*Volatile\b"""),
        "a synchronized block" to Regex("""\b[sS]ynchronized\b"""),
        "the process-wide holder" to Regex("""\bFocusedFieldHolder\b"""),
        "a thread constructor" to Regex("""\bThread\s*[({]"""),
        "a thread block" to Regex("""\bthread\s*[({]"""),
        "a timer" to Regex("""\bTimer(Task)?\b"""),
        "a random source" to Regex("""\b(Thread[Ll]ocal|Secure)?Random\b|\bkotlin\s*\.\s*random\b|\.\s*random\s*\(|\brandomUUID\b"""),
        timeLimitRule to Regex(
            """\bTimeout\s*(\.\s*[a-z]\w*\s*)?\(""" +
                """|@\s*([\p{L}\p{N}_]+\s*\.\s*)*Timeout\b""" +
                """|@\s*([\p{L}\p{N}_]+\s*\.\s*)*Test\s*\([^)]*\btimeout\s*=""" +
                """|org\s*\.\s*junit\s*\.\s*rules\s*\.\s*Timeout\b""",
        ),
    )

    private val samples: Map<String, String> = mapOf(
        "a sleep" to "class T { fun f() { Thread.sleep(1) } }",
        "a sleep call" to "class T { fun f() { TimeUnit.SECONDS.sleep(1) } }",
        "a delay" to "class T { suspend fun f() { delay(5) } }",
        "a timeout" to "class T { suspend fun f() { withTimeout(5) { 1 } } }",
        "runBlocking" to "class T { fun f() = runBlocking { 1 } }",
        "an executor" to "class T { val e = Executors.newFixedThreadPool(1) }",
        "a pool or future" to "class T { val f = CompletableFuture<Int>() }",
        "a lock or latch" to "class T { val l = CountDownLatch(1) }",
        "a millisecond clock" to "class T { val t = System.currentTimeMillis() }",
        "a nanosecond clock" to "class T { val t = System.nanoTime() }",
        "a wall clock read" to "class T { val t = Instant.now() }",
        "a clock object" to "class T { val c: Clock? = null }",
        "Dispatchers" to "class T { val d = Dispatchers.Default }",
        "GlobalScope" to "class T { val s = GlobalScope }",
        "a volatile field" to "class T { @Volatile var v: Int = 0 }",
        "a synchronized block" to "class T { fun f() = synchronized(this) { 1 } }",
        "the process-wide holder" to "class T { val r = FocusedFieldHolder.registry }",
        "a thread constructor" to "class T { val t = Thread(r) }",
        "a thread block" to "class T { val t = thread { run() } }",
        "a timer" to "class T { val t = Timer() }",
        "a random source" to "class T { val r = Random(1) }",
        timeLimitRule to "class T { val l = Timeout.seconds(10) }",
    )

    @Test
    fun `no test source waits, builds a thread, draws a random number or names the process wide holder`() {
        val tests: Map<String, String> = SourceFiles.testSources()
        assertTrue("commit/accessibility: the test scan found no test source", tests.isNotEmpty())
        assertTrue(
            "commit/accessibility: the test scan did not reach the scanner files, found ${tests.keys}",
            tests.keys.containsAll(ownFiles),
        )
        assertTrue(
            "commit/accessibility: the test scan did not reach the insert core tests, found ${tests.keys}",
            tests.keys.containsAll(insertTestFiles),
        )
        assertEquals(
            "commit/accessibility: the test scan did not reach a pinned test file",
            emptyList<String>(),
            missingPinned(tests.keys, moduleTestFiles),
        )
        assertEquals(
            "commit/accessibility: a test source breaks the one-core, in-parallel rule",
            emptyList<String>(),
            SourceFiles.offences(tests, rules),
        )
    }

    @Test
    fun `a pinned test file that was not scanned is reported by name`() {
        val everything: Set<String> = ownFiles + insertTestFiles + moduleTestFiles
        assertEquals("commit/accessibility: the pinned module test files are not the eleven expected", 11, moduleTestFiles.size)
        assertEquals("commit/accessibility: an empty scan was not reported", 11, missingPinned(emptySet(), moduleTestFiles).size)
        assertEquals("commit/accessibility: a full scan was reported as missing a file", emptyList<String>(), missingPinned(everything, moduleTestFiles))
        for (name in moduleTestFiles.sorted()) {
            assertEquals(
                "commit/accessibility: a scan without " + name + " was not reported",
                listOf("pinned test file " + name + " was not scanned"),
                missingPinned(everything - name, moduleTestFiles),
            )
        }
    }

    @Test
    fun `each forbidden test shape is reported on its own sample`() {
        assertEquals("commit/accessibility: a test rule has no sample, or a sample has no rule", rules.keys, samples.keys)
        for ((label, sample) in samples) {
            val found: List<String> = SourceFiles.offences(mapOf("Sample.kt" to sample), rules)
            assertTrue(
                "commit/accessibility: the test scan did not report $label, found $found",
                found.any { it.startsWith("Sample.kt: $label x") },
            )
        }
    }

    @Test
    fun `the other spellings of a random source and a time limit are reported`() {
        val more: Map<String, Pair<String, String>> = mapOf(
            "the thread local generator" to Pair("a random source", "class T { val r = ThreadLocalRandom.current() }"),
            "a random import" to Pair("a random source", "import kotlin.random.Default\nclass T"),
            "a random pick" to Pair("a random source", "class T { val r = listOf(1, 2).random() }"),
            "a random identifier" to Pair("a random source", "class T { val r = UUID.randomUUID() }"),
            "a limit with a constructor" to Pair(timeLimitRule, "class T { val l = Timeout(10, TimeUnit.SECONDS) }"),
            "a limit with another factory" to Pair(timeLimitRule, "class T { val l = Timeout.millis(5) }"),
            "a limit on the test annotation" to Pair(timeLimitRule, "class T { @Test(timeout = 5) fun f() {} }"),
            "a limit next to another argument" to Pair(timeLimitRule, "class T { @Test(expected = X::class, timeout = 5) fun f() {} }"),
            "a limit on a qualified annotation" to Pair(timeLimitRule, "class T { @org.junit.Test(timeout=5) fun f() {} }"),
            "a limit as an annotation" to Pair(timeLimitRule, "class T { @Timeout(5) fun f() {} }"),
            "a limit imported" to Pair(timeLimitRule, "import org.junit.rules.Timeout\nclass T"),
            "a limit on a rule field" to Pair(timeLimitRule, "class T { @get:Rule val t = Timeout.seconds(60) }"),
        )
        for ((label, pair) in more) {
            val found: List<String> = SourceFiles.offences(mapOf("Sample.kt" to pair.second), rules)
            assertTrue(
                "commit/accessibility: the test scan did not report $label under ${pair.first}, found $found",
                found.any { it.startsWith("Sample.kt: ${pair.first} x") },
            )
        }
    }

    @Test
    fun `the same names in comments, strings, backticks or longer words are not reported`() {
        val source: String = "// Thread.sleep(1) delay(2) FocusedFieldHolder Random\n" +
            "/** Executors, Dispatchers and GlobalScope are not allowed here. */\n" +
            "class T {\n" +
            "    val a = \"System.currentTimeMillis() withTimeout runBlocking @Volatile Timeout.seconds(10)\"\n" +
            "    val b = \"\"\"Thread(x) Timer() synchronized(y) Instant.now() Clock\"\"\"\n" +
            "    @Test fun `a test about Thread(x) and Random and @Test(timeout = 5)`() {}\n" +
            "    val c = MainThread(1)\n" +
            "    val d = InlineMainThread(2)\n" +
            "    val e = FocusedFieldHolderFake(3)\n" +
            "    val f = candelay(4)\n" +
            "    val g = Thread.currentThread()\n" +
            "    val h = DispatchersX\n" +
            "    val i = FakeClock(5)\n" +
            "    val j = randomized(6)\n" +
            "    val k = RandomAccess\n" +
            "    val l = Semaphores\n" +
            "    val m = MyExecutor(7)\n" +
            "    val n = MyCompletableFuture(8)\n" +
            "    val o = MyGlobalScope\n" +
            "    val p = unsynchronized(9)\n" +
            "    val q = mythread(10)\n" +
            "    val r = MyTimer(11)\n" +
            "    val s = Timers\n" +
            "    val t = System.currentTimeMillisX\n" +
            "    val u = System.nanoTimeX\n" +
            "    val v = MyInstant.now\n" +
            "    val w = Instant.nowhere\n" +
            "    @VolatileX var x: Int = 0\n" +
            "    val y = Thread.sleeper()\n" +
            "    val z = awithTimeout(12)\n" +
            "    val aa = withTimeoutOrNullX(13)\n" +
            "    val bb = myrunBlocking(14)\n" +
            "    val cc = runBlockingX(15)\n" +
            "}\n"
        assertEquals(
            "commit/accessibility: a test rule flagged prose, a literal, a backtick name, a longer name or the current thread call",
            emptyList<String>(),
            SourceFiles.offences(mapOf("Sample.kt" to source), rules),
        )
    }

    @Test
    fun `the names of a time limit that are not a limit are not reported`() {
        val source: String = "import java.util.concurrent.TimeoutException\n" +
            "class T {\n" +
            "    val a: TimeoutException? = null\n" +
            "    val timeoutMillis = 5\n" +
            "    val b = MyTimeout(1)\n" +
            "    val c = \"Timeout.seconds(10)\"\n" +
            "    @Test(expected = X::class) fun f() {}\n" +
            "    @Test fun g() { val timeout = 1 }\n" +
            "    // Timeout.seconds(10)\n" +
            "}\n"
        assertEquals(
            "commit/accessibility: a longer name, an annotation without a limit or a variable was reported as a time limit",
            emptyList<String>(),
            SourceFiles.offences(mapOf("Sample.kt" to source), rules),
        )
    }

    @Test
    fun `a name written in a template hole is code and is reported`() {
        val source: String = "class T { val s = \"waited \${System.nanoTime()}\" }"
        assertEquals(
            "commit/accessibility: a clock read inside a template hole was not reported",
            listOf("Sample.kt: a nanosecond clock x1"),
            SourceFiles.offences(mapOf("Sample.kt" to source), rules),
        )
    }

    @Test
    fun `no file name earns an allowance for a thread or a time limit`() {
        val thread: String = "class T { val t = Thread(r) }"
        val limit: String = "class T { val l = Timeout.seconds(10) }"
        val annotated: String = "class T { @Test(timeout = 5) fun f() {} }"
        val sources: Map<String, String> = mapOf(
            "PostedMainThreadClaimTest.kt" to thread,
            "FakeFieldNode.kt" to thread,
            "PostedMainThreadTest.kt" to limit,
            "FakeFieldNodeTest.kt" to annotated,
        )
        assertEquals(
            "commit/accessibility: a file name was allowed a thread constructor or a time limit",
            listOf(
                "PostedMainThreadClaimTest.kt: a thread constructor x1",
                "FakeFieldNode.kt: a thread constructor x1",
                "PostedMainThreadTest.kt: $timeLimitRule x1",
                "FakeFieldNodeTest.kt: $timeLimitRule x1",
            ),
            SourceFiles.offences(sources, rules),
        )
    }

    @Test
    fun `a main thread double that starts a thread is reported by the thread rules`() {
        val source: String = "class T { val m = object : MainThread {\n" +
            "    override fun <T> call(block: () -> T): T { val t = Thread { block() }; t.start(); t.join(); return block() }\n" +
            "} }"
        val found: List<String> = SourceFiles.offences(mapOf("Sample.kt" to source), rules)
        assertTrue(
            "commit/accessibility: a real thread inside a double was not reported, found $found",
            found.any { it.contains("a thread constructor") },
        )
    }
}
