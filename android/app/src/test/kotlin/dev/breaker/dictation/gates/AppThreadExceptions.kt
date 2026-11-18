package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three app files that may name a thread type, a handler or a clock, each with the rule names it may
 * use. Every other rule of [AppPrivacyScanTest] still holds in all three files. The first test below reads
 * each real file and checks that it names exactly the rules listed for it, so an entry cannot go stale.
 */
internal object AppThreadExceptions {
    /** Each exception file and the rule names of the privacy scan that it may use, and no others. */
    val lifted: Map<String, Set<String>> = mapOf(
        "host/AppThreads.kt" to setOf("Handler", "Executor", "Thread"),
        "wiring/UpdateTimerAndroid.kt" to setOf("Handler", "synchronized"),
        "wiring/AppUpdater.kt" to setOf("Thread", "synchronized", "Volatile", "clock read"),
    )

    /** True when [line], one offence line of the scan ("file: rule xN"), is a name lifted for its file. */
    fun lifts(line: String): Boolean = lifted.any { (file, labels) -> labels.any { line.startsWith("$file: $it x") } }
}

/** The exception table and its checks. The scan's rules and code helpers are read from [AppPrivacyScanTest]. */
internal class AppThreadExceptionsTest {

    private val scan: AppPrivacyScanTest = AppPrivacyScanTest()

    @Test
    fun `each exception file names exactly the rules it lifts`() {
        val main: Map<String, String> = scan.mainKotlin()
        for ((file, labels) in AppThreadExceptions.lifted) {
            assertTrue("app: the exception file $file is not among the main sources", main.containsKey(file))
            val named: Set<String> = AppSourceFiles.offences(mapOf(file to main.getValue(file)), scan.ruleMap)
                .map { it.substringAfter(": ").substringBefore(" x") }
                .toSet()
            assertEquals("app: $file must name exactly the rules it lifts", labels, named)
        }
    }

    @Test
    fun `a planted log sleep timer random print or toast is still reported in each exception file`() {
        val planted = "\nobject Planted { fun f() { Log.d(t, m); sleep(1); val x = Timer(); val y = Random(1); println(1); Toast.makeText(c, m, 0) } }"
        val expected: Set<String> = setOf("Log", "sleep call", "Timer", "Random", "println", "Toast")
        for (file in AppThreadExceptions.lifted.keys) {
            val text: String = scan.mainKotlin().getValue(file) + planted
            val found: Set<String> = scan.codeOffences(mapOf(file to text)).toSet()
            assertEquals("app: $file let off a planted rule", expected.map { "$file: $it x1" }.toSet(), found)
        }
    }

    @Test
    fun `a same named file in another folder gets no exception`() {
        val thread = "object A { val t = Thread { } }"
        for (other in listOf("service/AppUpdater.kt", "host/AppUpdater.kt", "wiring/deep/AppUpdater.kt")) {
            assertEquals("app: $other was let off a thread name", listOf("$other: Thread x1"), scan.codeOffences(mapOf(other to thread)))
        }
    }

    @Test
    fun `a name an exception file does not lift is still reported in that file`() {
        val thread = "object A { val t = Thread { } }"
        val handler = "object A { val h = Handler(l) }"
        assertEquals("app: a Thread in the timer file was let off", listOf("wiring/UpdateTimerAndroid.kt: Thread x1"), scan.codeOffences(mapOf("wiring/UpdateTimerAndroid.kt" to thread)))
        assertEquals("app: a Handler in the updater file was let off", listOf("wiring/AppUpdater.kt: Handler x1"), scan.codeOffences(mapOf("wiring/AppUpdater.kt" to handler)))
    }
}
