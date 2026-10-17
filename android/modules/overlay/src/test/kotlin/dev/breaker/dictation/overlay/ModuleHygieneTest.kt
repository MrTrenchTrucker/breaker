package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the module's sources as text and checks four rules: no thread primitive, clock
 * read or delayed post, and no network class, in main sources; no main source over 300
 * lines; and no clock or thread in test sources.
 *
 * The module runs on the main looper only, takes no time from a clock and reaches nothing
 * outside the device. Each check refuses, rather than passes, when it finds no source, and
 * each has two controls: the same scan on a sample that breaks the rule must fire, and on a
 * clean sample (rule words only in comments) must stay quiet. Comments and KDoc are not
 * scanned, string literals are.
 */
class ModuleHygieneTest {

    private val threadTokens = listOf(
        "Thread", "synchronized", "CountDownLatch", "java.util.concurrent", "Thread.sleep", "delay(", "runBlocking", "@Synchronized",
        "postDelayed", "currentTimeMillis", "nanoTime", "SystemClock", "Instant.now", "LocalDateTime.now", "Clock.system",
    )
    private val networkTokens = listOf("java.net", "android.net", "okhttp", "HttpURLConnection", "URL(", "Socket")
    private val clockTokens = listOf("Thread", "sleep", "delay(", "runBlocking", "java.util.concurrent", "System.currentTimeMillis", "nanoTime")
    private val lineCap = 300
    private val ownName = "ModuleHygieneTest.kt"

    /** The line count of each file of [files] that is longer than [lineCap], by file name; files within the cap are left out. */
    private fun overCap(files: Map<String, String>): Map<String, Int> =
        files.mapValues { SourceText.lineCount(it.value) }.filterValues { it > lineCap }

    /** The tokens of [tokens] found in the code of each file of [files], by file name; files with none are left out. */
    private fun hitsByFile(files: Map<String, String>, tokens: List<String>): Map<String, List<String>> =
        files.mapValues { SourceText.hits(SourceText.code(it.value), tokens) }.filterValues { it.isNotEmpty() }

    /** The test sources that wait on a clock or use a thread, by file name; this class is not scanned, since it lists the words. */
    private fun clockProblems(files: Map<String, String>): Map<String, List<String>> =
        hitsByFile(files.filterKeys { it != ownName }, clockTokens)

    private fun mainTextsRead(): Map<String, String> {
        val texts = ModuleFiles.mainTexts()
        assertTrue("android_overlay: no main source is named FloatingTile.kt, so the scan read the wrong folder: ${texts.keys}", texts.containsKey("FloatingTile.kt"))
        return texts
    }

    /** A failure here means main code uses a thread primitive, so the module is no longer single-threaded and deterministic. */
    @Test
    fun `no main source uses a thread primitive`() {
        val bad = "import java.util.concurrent.CountDownLatch\nfun f() { Thread.sleep(10); runBlocking { delay(5) }; synchronized(lock) { } }\n@Synchronized fun g() {}\n" +
            "fun h() { handler.postDelayed(r, 5); System.currentTimeMillis(); System.nanoTime(); SystemClock.uptimeMillis() }\n" +
            "fun i() { Instant.now(); LocalDateTime.now(); Clock.systemUTC() }\n"
        assertEquals(
            "android_overlay: control: the scan must fire on every thread token in a bad sample",
            threadTokens.toSet(),
            SourceText.hits(SourceText.code(bad), threadTokens).toSet(),
        )
        val quiet = "// Thread.sleep and synchronized are not used here\n/* runBlocking, delay( */\nval x = 1\n"
        assertEquals("android_overlay: control: tokens inside comments must not count", emptyList<String>(), SourceText.hits(SourceText.code(quiet), threadTokens))
        val quietClock = "// postDelayed, currentTimeMillis, nanoTime and SystemClock are not used here\n/* Instant.now, LocalDateTime.now, Clock.system */\nval x = 1\n"
        assertEquals("android_overlay: control: clock words inside comments must not count", emptyList<String>(), SourceText.hits(SourceText.code(quietClock), threadTokens))
        assertEquals(
            "android_overlay: control: a token inside a string literal must count",
            listOf("Thread"),
            SourceText.hits(SourceText.code("val s = \"Thread\" // x\n"), threadTokens),
        )

        assertEquals("android_overlay: main sources must use no thread primitive and read no clock, found by file", emptyMap<String, List<String>>(), hitsByFile(mainTextsRead(), threadTokens))
    }

    /** A failure here means main code reaches the network, which this module has no business doing. */
    @Test
    fun `no main source reaches the network`() {
        val bad = "import java.net.Socket\nimport android.net.ConnectivityManager\nval c = okhttp3.OkHttpClient()\nval u = URL(\"x\")\nval h: HttpURLConnection? = null\n"
        assertEquals(
            "android_overlay: control: the scan must fire on every network token in a bad sample",
            networkTokens.toSet(),
            SourceText.hits(SourceText.code(bad), networkTokens).toSet(),
        )
        val quiet = "// java.net, android.net and okhttp are not used here\n/* URL( and Socket */\nval x = 1\n"
        assertEquals("android_overlay: control: tokens inside comments must not count", emptyList<String>(), SourceText.hits(SourceText.code(quiet), networkTokens))

        assertEquals("android_overlay: main sources must not reach the network, found by file", emptyMap<String, List<String>>(), hitsByFile(mainTextsRead(), networkTokens))
    }

    /** A failure here means a main source has grown past the line cap and must be split by responsibility. */
    @Test
    fun `no main source is longer than 300 lines`() {
        assertEquals("android_overlay: control: 300 lines must count as 300", 300, SourceText.lineCount("x\n".repeat(300)))
        assertEquals("android_overlay: control: 301 lines must count as 301", 301, SourceText.lineCount("x\n".repeat(301)))
        assertEquals("android_overlay: control: a last line with no line break must count", 3, SourceText.lineCount("x\nx\nx"))
        assertTrue("android_overlay: control: 301 lines must be over the cap", SourceText.lineCount("x\n".repeat(301)) > lineCap)
        assertTrue("android_overlay: control: 300 lines must not be over the cap", SourceText.lineCount("x\n".repeat(300)) <= lineCap)

        val atCap = "x\n".repeat(lineCap)
        val overByOne = "x\n".repeat(lineCap + 1)
        val samples = mapOf("AtCap.kt" to atCap, "AtCapNoBreak.kt" to atCap.trimEnd('\n'), "Over.kt" to overByOne, "OverNoBreak.kt" to overByOne.trimEnd('\n'), "Short.kt" to "x\n")
        assertEquals(
            "android_overlay: control: only the files over the cap must be reported, with their line counts",
            mapOf("Over.kt" to lineCap + 1, "OverNoBreak.kt" to lineCap + 1),
            overCap(samples),
        )
        assertEquals("android_overlay: control: files at or under the cap must stay quiet", emptyMap<String, Int>(), overCap(samples - "Over.kt" - "OverNoBreak.kt"))

        assertEquals("android_overlay: main sources longer than $lineCap lines, by file", emptyMap<String, Int>(), overCap(mainTextsRead()))
    }

    /** A failure here means a test waits on a clock or starts a thread, so it can pass or fail with the machine instead of the code. */
    @Test
    fun `no test source waits on a clock or uses a thread primitive`() {
        val bad = "fun f() { Thread.sleep(1); delay(2); runBlocking { }; val t = System.currentTimeMillis(); val n = System.nanoTime() }\nimport java.util.concurrent.Executors\n"
        assertEquals(
            "android_overlay: control: the scan must fire on every clock and thread token in a bad sample",
            clockTokens.toSet(),
            SourceText.hits(SourceText.code(bad), clockTokens).toSet(),
        )
        assertEquals("android_overlay: control: a bad file other than this one must be reported", setOf("Slow.kt"), clockProblems(mapOf("Slow.kt" to bad, "Fine.kt" to "val x = 1\n")).keys)
        assertEquals("android_overlay: control: this class lists the words and must be skipped", emptyMap<String, List<String>>(), clockProblems(mapOf(ownName to bad)))
        assertEquals("android_overlay: control: tokens inside comments must not count", emptyMap<String, List<String>>(), clockProblems(mapOf("Fine.kt" to "// Thread.sleep and nanoTime\n/* delay( */\nval x = 1\n")))

        val texts = ModuleFiles.testTexts()
        assertTrue("android_overlay: the scan did not read this module's test folder: ${texts.keys}", texts.containsKey("AdapterGateTest.kt") && texts.containsKey(ownName))
        assertEquals("android_overlay: test sources must not wait on a clock or use a thread primitive, found by file", emptyMap<String, List<String>>(), clockProblems(texts))
    }
}
