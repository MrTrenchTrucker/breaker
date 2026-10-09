package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service and wiring code logs nothing, starts no thread, timer or lock, reads no
 * clock and draws no random number; no main string says an http:// address; no main text
 * uses the one word the project bans for putting text into a field.
 *
 * Dictated text, audio and transcripts must never reach a log, a print or a toast, and
 * the new code does its waiting through signals, not through clocks and threads. The scan
 * reads code only: comments, string literals and names in backticks are blanked first, a
 * template hole is code and is kept. It is text, not proof: a path to the outside that
 * uses none of these names is not seen here, and a URL built from pieces is not a literal.
 * The files under `service/` and `wiring/` are scanned when they exist; a folder that is
 * not there is not a failure.
 */
internal class AppPrivacyScanTest {

    private class Rule(val label: String, val pattern: Regex, val sample: String)

    private val rules: List<Rule> = listOf(
        Rule("Log", Regex("""\bLog\b"""), "object A { fun f() { Log.d(t, m) } }"),
        Rule("println", Regex("""\bprintln\b"""), "object A { fun f() { println(1) } }"),
        Rule("print call", Regex("""\bprint\s*\("""), "object A { fun f() { print(1) } }"),
        Rule("printStackTrace", Regex("""printStackTrace"""), "object A { fun f(e: Exception) { e.printStackTrace() } }"),
        Rule("Timber", Regex("""Timber"""), "object A { fun f() { Timber.d(m) } }"),
        Rule("Logger", Regex("""\bLogger\b"""), "object A { val l: Logger? = null }"),
        Rule("System out or err", Regex("""System\s*\.\s*(out|err)\b"""), "object A { fun f() { System.err.write(1) } }"),
        Rule("Toast", Regex("""Toast"""), "object A { fun f() { Toast.makeText(c, m, 0) } }"),
        Rule("Thread", Regex("""\bThread\b"""), "object A { val t = Thread { } }"),
        Rule("thread function", Regex("""\bthread\s*[({]"""), "object A { fun f() { thread { } } }"),
        Rule("sleep call", Regex("""\bsleep\s*\("""), "object A { fun f() { sleep(5) } }"),
        Rule("delay call", Regex("""\bdelay\s*\("""), "object A { suspend fun f() { delay(5) } }"),
        Rule("Timer", Regex("""\bTimer(Task)?\b"""), "object A { val t = Timer() }"),
        Rule("Handler", Regex("""\b(Handler|HandlerThread|Looper)\b"""), "object A { val h = Handler(l) }"),
        Rule("Executor", Regex("""\bExecutors?\b|\bExecutorService\b"""), "object A { val e = Executors.newFixedThreadPool(1) }"),
        Rule("synchronized", Regex("""\bsynchronized\b"""), "object A { fun f() = synchronized(this) { 1 } }"),
        Rule("Volatile", Regex("""\bVolatile\b"""), "object A { @Volatile var v = 1 }"),
        Rule("Synchronized", Regex("""\bSynchronized\b"""), "object A { @Synchronized fun f() {} }"),
        Rule("lock or latch", Regex("""\b(ReentrantLock|ReentrantReadWriteLock|ReadWriteLock|CountDownLatch|CyclicBarrier|Semaphore|Phaser|LockSupport)\b"""), "object A { val l = CountDownLatch(1) }"),
        Rule("Random", Regex("""\b(Random|SecureRandom|ThreadLocalRandom)\b"""), "object A { val r = Random(1) }"),
        Rule("clock read", Regex("""\b(currentTimeMillis|nanoTime|elapsedRealtime|elapsedRealtimeNanos|uptimeMillis)\b|\bSystemClock\b"""), "object A { val t = System.currentTimeMillis() }"),
    )

    private val ruleMap: Map<String, Regex> = rules.associate { it.label to it.pattern }

    /** Files that predate the service and wiring folders. They are scanned by name with the rules above. */
    private val predating: Set<String> = setOf("BreakerApp.kt", "AppStartPurge.kt")

    /** The one exception, by name: the application class may keep a single fixed-shape warning log (see [logProblems]). */
    private val logException: String = "BreakerApp.kt"

    private val quiet: Map<String, String> = mapOf(
        "a line comment" to "// Log.d println(x) Thread.sleep(1) synchronized Random currentTimeMillis\nobject A",
        "a KDoc" to "/**\n * Never calls Toast, Handler, delay(5), Semaphore or e.printStackTrace().\n */\nobject A",
        "a block comment" to "/* Executors Timer @Volatile CountDownLatch nanoTime */ object A",
        "a string" to "object A { const val S = \"Log println Toast Thread sleep( delay( synchronized Random nanoTime\" }",
        "a raw string" to "object A { const val S = \"\"\"Timer Handler Executors SystemClock Looper\"\"\" }",
        "a name in backticks" to "class T { @Test fun `a test that mentions Log and Thread and delay(`() {} }",
        "longer words" to "object A { val Logistics = 1; val blog = 2; val printer = 3; val Threads = 4; val threadCount = 5; " +
            "val Timers = 6; val delayed = 7; fun delayMs() = 8; val randomUUID = 9; val Randomize = 10; " +
            "val synchronizedList = 11; val Handlers = 12; val Executed = 13; val Volatiles = 14; val Semaphores = 15; " +
            "fun sleepy() = 16; val SystemClockAdapter = 17; val currentTimeMillisAgo = 18; val Loggers = 19 }",
        "the lazy mode and the atomics that are allowed" to "object A { val a = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { 1 }; " +
            "val b = AtomicBoolean(false); val c = AtomicReference<String?>(null) }",
        "a clock port and a uuid" to "class C(private val clock: Clock) { fun id() = UUID.randomUUID().toString() }",
        "a coroutine scope" to "object A { val s = CoroutineScope(Dispatchers.IO + SupervisorJob()); val h: DisposableHandle? = null }",
        "System members that are not out or err" to "object A { val a = System.lineSeparator(); val b = System.outline }",
    )

    private val httpFiring: Map<String, String> = mapOf(
        "a plain literal" to "object A { const val U = \"http://example.com\" }",
        "a raw literal" to "object A { val u = \"\"\"http://example.com\"\"\" }",
        "a literal with a leading space" to "object A { val u = \" http://x\" }",
        "a literal in capitals" to "object A { val u = \"HTTP://x\" }",
        "a literal after other words" to "object A { val u = \"see http://x/y\" }",
        "a literal as an argument" to "object A { fun f() = open(\"http://x/y\") }",
    )

    private val httpQuiet: Map<String, String> = mapOf(
        "an https literal" to "object A { const val U = \"https://example.com\" }",
        "an address in a comment" to "// http://example.com\n/** http://example.com */\nobject A",
        "the word alone" to "object A { val a = \"http\"; val b = \"http:\"; val c = \"http/1.1\" }",
        "a name" to "object A { val httpClientName = 1 }",
    )

    private val logFiring: Map<String, String> = mapOf(
        "a debug log" to "object A { fun f(e: Exception) { Log.d(\"a\", \"b\", e) } }",
        "two warnings" to "object A { fun f(e: Exception) { Log.w(\"a\", \"b\", e); Log.w(\"a\", \"c\", e) } }",
        "a warning with a variable message" to "object A { fun f(e: Exception) { Log.w(\"a\", m, e) } }",
        "a warning with a variable tag" to "object A { fun f(e: Exception) { Log.w(t, \"b\", e) } }",
        "a warning with a template message" to "object A { fun f(e: Exception) { Log.w(\"a\", \"x \$name\", e) } }",
        "a warning with the exception message" to "object A { fun f(e: Exception) { Log.w(\"a\", \"b\", e.message) } }",
        "a warning without the exception" to "object A { fun f() { Log.w(\"a\", \"b\") } }",
        "an error log" to "object A { fun f(e: Exception) { Log.e(\"a\", \"b\", e) } }",
    )

    private val logQuiet: Map<String, String> = mapOf(
        "the fixed warning" to "import android.util.Log\nobject A { fun f(e: Exception) { Log.w(\"BreakerApp\", \"start-up purge failed\", e) } }",
        "the fixed warning over lines" to "object A { fun f(e: Exception) { Log\n.w(\"a\",\n\"b\",\ne) } }",
        "no log at all" to "import android.util.Log\nobject A",
        "a log in a comment" to "// Log.d(m)\nobject A",
    )

    /** The banned word is written in two pieces so that this file does not hold it; samples carry [token] where it goes. */
    private val word: String = "inj" + "ection"
    private val token: String = "#W#"

    private val bannedFiring: Map<String, String> = mapOf(
        "in a comment" to "// text #W#\nobject A",
        "in a KDoc" to "/** Uses #W#. */ object A",
        "in a name" to "object A { val #W#Point = 1 }",
        "in a string" to "object A { const val S = \"#W#\" }",
        "in capitals" to "object A { val x = 1 } // #W#",
    )

    /** The files the code rules read: everything under service/ and wiring/, and the two files that predate them. */
    private fun scanned(sources: Map<String, String>): Map<String, String> =
        sources.filterKeys { it.startsWith("service/") || it.startsWith("wiring/") || it in predating }

    /** One line per file and rule hit in the scanned files; the application class is not read for the Log rule (see [logProblems]). */
    private fun codeOffences(sources: Map<String, String>): List<String> =
        AppSourceFiles.offences(scanned(sources), ruleMap).filterNot { it.startsWith("$logException: Log x") }

    private fun httpOffences(sources: Map<String, String>): List<String> {
        val found: MutableList<String> = ArrayList()
        for ((name, text) in sources.toSortedMap()) {
            val hits: Int = AppSourceFiles.strip(text).literals.count { it.contains("http://", ignoreCase = true) }
            if (hits > 0) found.add("$name: $hits string literal(s) with http://")
        }
        return found
    }

    private fun bannedWordOffences(texts: Map<String, String>): List<String> {
        val found: MutableList<String> = ArrayList()
        for ((name, text) in texts.toSortedMap()) {
            val hits: Int = Regex(word, RegexOption.IGNORE_CASE).findAll(text).count()
            if (hits > 0) found.add("$name: banned word x$hits")
        }
        return found
    }

    private val logUse: Regex = Regex("""\bLog\s*\.\s*\w+""")
    private val fixedWarning: Regex = Regex("""\bLog\s*\.\s*w\s*\(\s*""\s*,\s*""\s*,\s*[A-Za-z_]\w*\s*\)""")

    /** Empty when [source] uses Log at most once, as `Log.w(<word>, <word>, <exception variable>)`. */
    private fun logProblems(source: String): List<String> {
        val code: String = AppSourceFiles.strip(source).code
        val uses: List<String> = logUse.findAll(code).map { it.value.replace(Regex("""\s+"""), "") }.toList()
        val problems: MutableList<String> = ArrayList()
        if (uses.any { it != "Log.w" }) problems.add("a Log call other than Log.w")
        if (uses.size > 1) problems.add("${uses.size} Log calls, at most one is allowed")
        if (fixedWarning.findAll(code).count() != uses.size) problems.add("a Log.w that is not a fixed word, a fixed word and the exception")
        return problems
    }

    private fun mainKotlin(): Map<String, String> {
        val main: Map<String, String> = AppSourceFiles.mainKotlinSources()
        assertTrue("app: the privacy scan found no main file to read", main.isNotEmpty())
        assertTrue("app: the privacy scan did not reach the files that predate the folders, found ${main.keys}", main.keys.containsAll(predating))
        return main
    }

    @Test
    fun `no service or wiring file logs, prints, starts a thread, timer or lock, reads a clock or draws a random number`() {
        assertEquals(
            "app: a service or wiring file could send text out, wait on a clock or start a thread",
            emptyList<String>(),
            codeOffences(mainKotlin()),
        )
    }

    @Test
    fun `no main file holds a string with an http address`() {
        assertEquals("app: a main file holds a clear text address", emptyList<String>(), httpOffences(mainKotlin()))
    }

    @Test
    fun `no main text file uses the banned word`() {
        assertEquals("app: a main file uses the banned word", emptyList<String>(), bannedWordOffences(AppSourceFiles.mainTextFiles()))
    }

    @Test
    fun `the application class logs at most its one fixed warning and the purge file logs nothing`() {
        val main: Map<String, String> = mainKotlin()
        assertEquals("app: BreakerApp.kt logs more than its one fixed warning", emptyList<String>(), logProblems(main.getValue("BreakerApp.kt")))
        assertEquals(
            "app: AppStartPurge.kt uses Log",
            emptyList<String>(),
            AppSourceFiles.offences(mapOf("AppStartPurge.kt" to main.getValue("AppStartPurge.kt")), mapOf("Log" to ruleMap.getValue("Log"))),
        )
    }

    @Test
    fun `each forbidden name is reported on its own sample`() {
        assertEquals("app: two privacy rules share a name", rules.size, ruleMap.size)
        for (rule in rules) {
            val found: List<String> = AppSourceFiles.offences(mapOf("Sample.kt" to rule.sample), ruleMap)
            assertTrue("app: the privacy scan did not report ${rule.label}, found $found", found.any { it.startsWith("Sample.kt: ${rule.label} x") })
        }
    }

    @Test
    fun `the same names in comments, strings, backticks or longer words are not reported`() {
        for ((label, source) in quiet) {
            assertEquals("app: the privacy scan flagged $label", emptyList<String>(), AppSourceFiles.offences(mapOf("Sample.kt" to source), ruleMap))
        }
    }

    @Test
    fun `a forbidden name inside a template hole is code and is reported`() {
        val source: String = "object A { fun f() = \"x \${Log.d(t, m)}\" }"
        assertEquals("app: a Log call inside a template hole was not reported", listOf("Sample.kt: Log x1"), AppSourceFiles.offences(mapOf("Sample.kt" to source), ruleMap))
    }

    @Test
    fun `only service, wiring and the two older files are scanned, and a folder that is not there is not a failure`() {
        val bad = "object A { fun f() { Log.d(t, m) } }"
        val none: Map<String, String> = mapOf("BreakerApp.kt" to "object A", "AppStartPurge.kt" to "object B", "Other.kt" to bad)
        assertEquals("app: a tree without service and wiring folders failed the scan", emptyList<String>(), codeOffences(none))
        assertEquals("app: a hit in service/ was not reported", listOf("service/A.kt: Log x1"), codeOffences(mapOf("service/A.kt" to bad)))
        assertEquals("app: a hit in wiring/ was not reported", listOf("wiring/A.kt: Log x1"), codeOffences(mapOf("wiring/A.kt" to bad)))
        assertEquals("app: a hit in a nested wiring folder was not reported", listOf("wiring/deep/A.kt: Log x1"), codeOffences(mapOf("wiring/deep/A.kt" to bad)))
        assertEquals("app: a Thread in BreakerApp.kt was not reported", listOf("BreakerApp.kt: Thread x1"), codeOffences(mapOf("BreakerApp.kt" to "object A { val t = Thread { } }")))
        assertEquals("app: a Log in AppStartPurge.kt was not reported", listOf("AppStartPurge.kt: Log x1"), codeOffences(mapOf("AppStartPurge.kt" to bad)))
        assertEquals("app: a Log in BreakerApp.kt was left to the log check", emptyList<String>(), codeOffences(mapOf("BreakerApp.kt" to bad)))
    }

    @Test
    fun `an http address in a string is reported in every spelling and an https or a comment is not`() {
        for ((label, source) in httpFiring) {
            assertEquals("app: the scan missed $label", listOf("Sample.kt: 1 string literal(s) with http://"), httpOffences(mapOf("Sample.kt" to source)))
        }
        for ((label, source) in httpQuiet) {
            assertEquals("app: the scan flagged $label", emptyList<String>(), httpOffences(mapOf("Sample.kt" to source)))
        }
    }

    @Test
    fun `the fixed warning is accepted and any other use of Log in the application class is reported`() {
        for ((label, source) in logFiring) {
            assertTrue("app: the log check accepted $label", logProblems(source).isNotEmpty())
        }
        for ((label, source) in logQuiet) {
            assertEquals("app: the log check flagged $label", emptyList<String>(), logProblems(source))
        }
    }

    @Test
    fun `the banned word is reported in a comment, a name, a string and in capitals, in the Kotlin and in the XML text`() {
        for ((label, source) in bannedFiring) {
            val text: String = source.replace(token, word)
            assertEquals("app: the scan missed the banned word $label", listOf("Sample.kt: banned word x1"), bannedWordOffences(mapOf("Sample.kt" to text)))
        }
        assertEquals("app: the scan missed the banned word in an XML text", listOf("a.xml: banned word x1"), bannedWordOffences(mapOf("a.xml" to "<!-- $word -->")))
        assertEquals("app: the scan flagged text without the word", emptyList<String>(), bannedWordOffences(mapOf("Sample.kt" to "// text insert\nobject A")))
    }
}
