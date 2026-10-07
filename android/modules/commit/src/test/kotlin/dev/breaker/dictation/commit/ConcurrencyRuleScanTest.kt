package dev.breaker.dictation.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module's main code follows the threading rule: coroutines only, no
 * hand-made threads or locks, no logging, and the committed text never reaches a
 * string template.
 *
 * Each rule is a text match on code (comments and literals removed), and each has
 * a sample that it must report, so a scan that quietly stopped seeing its shape
 * would fail here instead of passing on an empty result.
 */
internal class ConcurrencyRuleScanTest {

    private val forbidden: Map<String, Regex> = mapOf(
        "a thread constructor" to Regex("""\bThread\s*[({]"""),
        "a thread block" to Regex("""\bthread\s*[({]"""),
        "a synchronized block" to Regex("""\b[sS]ynchronized\b"""),
        "a countdown latch" to Regex("""\bCountDownLatch\b"""),
        "an atomic reference" to Regex("""\bAtomicReference\b"""),
        "an atomic boolean" to Regex("""\bAtomicBoolean\b"""),
        "an atomic integer" to Regex("""\bAtomicInteger\b"""),
        "a volatile field" to Regex("""@\s*Volatile\b"""),
        "a reentrant lock" to Regex("""\bReentrantLock\b"""),
        "a sleep" to Regex("""\bThread\s*\.\s*sleep\b"""),
        "a println" to Regex("""\bprintln\b"""),
        "a Log call" to Regex("""\bLog\s*\."""),
        "a Logger" to Regex("""\bLogger\b"""),
    )

    private val samples: Map<String, String> = mapOf(
        "a thread constructor" to "object A { val t = Thread(r) }",
        "a thread block" to "object A { val t = thread { run() } }",
        "a synchronized block" to "object A { fun f() = synchronized(this) { 1 } }",
        "a countdown latch" to "object A { val l = CountDownLatch(1) }",
        "an atomic reference" to "object A { val r = AtomicReference<String?>(null) }",
        "an atomic boolean" to "object A { val b = AtomicBoolean(false) }",
        "an atomic integer" to "object A { val i = AtomicInteger(0) }",
        "a volatile field" to "object A { @Volatile var v: Int = 0 }",
        "a reentrant lock" to "object A { val l = ReentrantLock() }",
        "a sleep" to "object A { fun f() { Thread.sleep(1) } }",
        "a println" to "object A { fun f() { println(1) } }",
        "a Log call" to "object A { fun f() { Log.d(t, m) } }",
        "a Logger" to "object A { val l: Logger? = null }",
    )

    private val currentThread: Regex = Regex("""\bThread\s*\.\s*currentThread\b""")
    private val blocking: Regex = Regex("""\brunBlocking\b""")
    private val handlerCtor: Regex = Regex("""\bHandler\s*\(""")
    private val mentionsText: Regex = Regex("""(?i)(text|request)""")

    @Test
    fun `main code holds no hand made thread, lock, sleep or log call`() {
        val main: Map<String, String> = SourceFiles.mainSources()
        assertTrue("commit: found no main source to scan", main.containsKey("CommitService.kt"))
        assertEquals(
            "commit: main code breaks the threading or logging rule",
            emptyList<String>(),
            SourceFiles.offences(main, forbidden),
        )
    }

    @Test
    fun `each forbidden shape is reported on its own sample`() {
        assertEquals("commit: a rule has no sample", forbidden.keys, samples.keys)
        for ((label, sample) in samples) {
            val found: List<String> = SourceFiles.offences(mapOf("s.kt" to sample), forbidden)
            assertTrue(
                "commit: the scan did not report $label, found $found",
                found.any { it.startsWith("s.kt: $label x") },
            )
        }
    }

    @Test
    fun `comments, strings and longer names are not reported as forbidden shapes`() {
        val source: String = "// Thread(r) synchronized AtomicBoolean\n" +
            "/* @Volatile ReentrantLock /* Logger */ CountDownLatch */\n" +
            "object A {\n" +
            "    const val S = \"Log.d println Thread.sleep AtomicInteger\"\n" +
            "    val R = \"\"\"Thread(x) AtomicReference\"\"\"\n" +
            "    val m = MainThread(1)\n" +
            "    val h = HandlerMainThread(2)\n" +
            "    val d = dialog.show()\n" +
            "    val c = Thread.currentThread()\n" +
            "}\n"
        assertEquals(
            "commit: a forbidden-shape rule flagged prose, a literal, a longer name or the permitted current thread call",
            emptyList<String>(),
            SourceFiles.offences(mapOf("s.kt" to source), forbidden),
        )
    }

    @Test
    fun `the current thread is read exactly three times, twice in the service and once in the posted hop class`() {
        val counts: Map<String, Int> = SourceFiles.mainSources()
            .mapValues { entry -> SourceFiles.countIn(entry.value, currentThread) }
            .filterValues { it > 0 }
        assertEquals(
            "commit: the places that re-arm an interrupt changed; each one has to be argued for",
            mapOf("CommitService.kt" to 2, "PostedMainThread.kt" to 1),
            counts,
        )
    }

    @Test
    fun `the current thread count sees a split call and ignores prose and strings`() {
        assertEquals(
            "commit: a call split over two lines was not counted",
            1,
            SourceFiles.countIn("object A { fun f() { Thread\n    .currentThread().interrupt() } }", currentThread),
        )
        assertEquals(
            "commit: two calls were not counted as two",
            2,
            SourceFiles.countIn("object A { fun f() { Thread.currentThread(); Thread.currentThread() } }", currentThread),
        )
        assertEquals(
            "commit: a comment or a string was counted",
            0,
            SourceFiles.countIn("// Thread.currentThread()\nobject A { const val S = \"Thread.currentThread()\" }", currentThread),
        )
    }

    @Test
    fun `runBlocking appears only in the posted hop class`() {
        assertEquals(
            "commit: runBlocking moved; only the posted hop class that waits for the main thread may block",
            listOf("PostedMainThread.kt"),
            SourceFiles.filesMatching(SourceFiles.mainSources(), blocking),
        )
    }

    @Test
    fun `Handler is built only inside the adapter folder`() {
        val files: List<String> = SourceFiles.filesMatching(SourceFiles.mainSources(), handlerCtor)
        assertTrue("commit: the scan no longer finds the Handler built in the adapter", files.isNotEmpty())
        assertEquals(
            "commit: a Handler is built outside the adapter folder",
            emptyList<String>(),
            files.filter { !it.startsWith("adapter/") },
        )
    }

    @Test
    fun `the file matching scan reports code and ignores prose, strings and longer names`() {
        val sources: Map<String, String> = mapOf(
            "a.kt" to "object A { fun f() = runBlocking { 1 } }",
            "b.kt" to "// runBlocking\nobject B",
            "c.kt" to "object C { const val S = \"runBlocking\" }",
            "d.kt" to "object D { val h = android.os.Handler(looper) }",
            "e.kt" to "object E { val h = MainHandler(1); val g = HandlerMainThread(2) }",
        )
        assertEquals("commit: runBlocking was not found only in code", listOf("a.kt"), SourceFiles.filesMatching(sources, blocking))
        assertEquals("commit: Handler was not found only in code", listOf("d.kt"), SourceFiles.filesMatching(sources, handlerCtor))
    }

    @Test
    fun `no string template in the service mentions the text or the request`() {
        val service: String = SourceFiles.mainSources()["CommitService.kt"] ?: error("commit: CommitService.kt is missing")
        val mentioning: List<String> = SourceFiles.strip(service).templates.filter { mentionsText.containsMatchIn(it) }
        assertEquals("commit: a string template in the service reads the text or the request", emptyList<String>(), mentioning)
    }

    @Test
    fun `the template scan reports a hole or a simple name that mentions the text and ignores the rest`() {
        val reported: List<String> = listOf(
            "val s = \"saved \${request.text}\"",
            "val s = \"saved \$text\"",
            "val s = \"saved \${request}\"",
            "val s = \"\"\"saved \${text.length}\"\"\"",
            "val s = \"a \${f(\"b \${text}\")}\"",
        )
        for (source in reported) {
            val found: List<String> = SourceFiles.strip(source).templates.filter { mentionsText.containsMatchIn(it) }
            assertTrue("commit: the template scan missed a mention of the text in $source", found.isNotEmpty())
        }
        val quiet: List<String> = listOf(
            "val s = \"the text and the request\"",
            "val s = \"count \${done.interrupted}\"",
            "val s = \"count \$done\"",
            "// \"saved \${text}\"\nval s = 1",
        )
        for (source in quiet) {
            val found: List<String> = SourceFiles.strip(source).templates.filter { mentionsText.containsMatchIn(it) }
            assertEquals("commit: the template scan flagged $source", emptyList<String>(), found)
        }
    }

    @Test
    fun `the reader keeps quotes in character literals and braces in nested strings from hiding code`() {
        assertTrue(
            "commit: a quote character literal hid the code after it",
            SourceFiles.offences(mapOf("s.kt" to "val q = '\"'; val t = Thread(r)"), forbidden).isNotEmpty(),
        )
        assertTrue(
            "commit: a hole was not read as code",
            SourceFiles.offences(mapOf("s.kt" to "val s = \"a \${Thread(r)}\""), forbidden).isNotEmpty(),
        )
        assertTrue(
            "commit: a brace inside a string inside a hole ended the hole early",
            SourceFiles.offences(mapOf("s.kt" to "val s = \"\${f(\"}\")}\"\nval t = Thread(r)"), forbidden).isNotEmpty(),
        )
    }
}
