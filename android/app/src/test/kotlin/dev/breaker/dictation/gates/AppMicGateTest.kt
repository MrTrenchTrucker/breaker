package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's microphone swap point returns the real microphone, not the unavailable placeholder.
 * Scans the source text of Swaps.kt and BreakerApp.kt.
 */
internal class AppMicGateTest {

    private val swapsPath = "kotlin/dev/breaker/dictation/wiring/AndroidSwaps.kt"
    private val appPath = "kotlin/dev/breaker/dictation/BreakerApp.kt"

    private fun has(code: String, pattern: String): Boolean = Regex(pattern).containsMatchIn(code)
    private fun count(code: String, pattern: String): Int = Regex(pattern).findAll(code).count()

    private val rules: List<Pair<String, (String) -> Boolean>> = listOf(
        "APP_MIC_SOURCE_RETURNS_THE_REAL_MICROPHONE" to { code ->
            has(code, """\bappMicSource\s*\(\s*audioManager\s*:\s*AudioManager\s*\)\s*:\s*MicSource\s*=\s*AndroidMicSource\.create\s*\(\s*audioManager\s*\)""")
        },
        "APP_MIC_SOURCE_IS_NOT_THE_UNAVAILABLE_PLACEHOLDER" to { code ->
            !has(code, """\bappMicSource\s*\([^)]*\)\s*:\s*MicSource\s*=\s*UnavailableMicSource\s*\(\s*\)""")
        },
        "REAL_MIC_SOURCE_IS_BUILT_ONCE_PER_PROCESS" to { code ->
            count(code, """\bAndroidMicSource\.create\s*\(""") == 1
        },
        "REAL_MIC_SOURCE_IS_BUILT_LAZILY" to { code ->
            has(code, """\bval\s+realMicSource\s*:\s*MicSource\s+by\s+lazy\s*\{""")
        },
    )

    private class Sample(val rule: String, val file: String, val old: String, val new: String)
    private class Quiet(val rule: String, val file: String, val old: String, val new: String)

    private val firing: List<Sample> = listOf(
        Sample("REAL_MIC_SOURCE_IS_BUILT_LAZILY", appPath, "val realMicSource: MicSource by lazy {", "val realMicSource: MicSource = run {"),
        Sample("REAL_MIC_SOURCE_IS_BUILT_LAZILY", appPath, "val realMicSource: MicSource by lazy {", "val realMicSource: MicSource = appMicSource(audioManager),"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("REAL_MIC_SOURCE_IS_BUILT_LAZILY", appPath, "val realMicSource: MicSource by lazy {", "val realMicSource: MicSource by lazy {  "),
    )

    private fun realCode(path: String): String = AppSourceFiles.strip(AppSourceFiles.mainFile(path)).code

    @Test
    fun `every rule holds on the real source`() {
        val swapsCode = realCode(swapsPath)
        val appCode = realCode(appPath)
        val combined = swapsCode + "\n" + appCode
        for ((name, holds) in rules) {
            assertTrue("app: rule $name does not hold", holds(combined))
        }
    }

    @Test
    fun `the real microphone rule fires on the unavailable placeholder`() {
        val swapsCode = realCode(swapsPath)
        val edited = swapsCode.replace(
            "fun appMicSource(audioManager: AudioManager): MicSource = AndroidMicSource.create(audioManager)",
            "fun appMicSource(audioManager: AudioManager): MicSource = UnavailableMicSource()"
        )
        assertFalse(
            "app: the real microphone rule must fire on the unavailable placeholder",
            rules[0].second(edited + "\n" + realCode(appPath))
        )
    }

    @Test
    fun `the once-per-process rule fires on a second create call`() {
        val swapsCode = realCode(swapsPath)
        val edited = swapsCode + "\nfun anotherMicSource(audioManager: AudioManager): MicSource = AndroidMicSource.create(audioManager)"
        assertFalse(
            "app: the once-per-process rule must fire on a second create call",
            rules[2].second(edited + "\n" + realCode(appPath))
        )
    }

    @Test
    fun `the not-unavailable rule fires on the old body`() {
        val swapsCode = realCode(swapsPath)
        val edited = swapsCode.replace(
            "fun appMicSource(audioManager: AudioManager): MicSource = AndroidMicSource.create(audioManager)",
            "fun appMicSource(): MicSource = UnavailableMicSource()"
        )
        assertFalse(
            "app: the not-unavailable rule must fire on the old body",
            rules[1].second(edited + "\n" + realCode(appPath))
        )
    }

    @Test
    fun `the lazy rule fires on each of its edited samples`() {
        for (sample in firing) {
            val code = realCode(sample.file)
            val edited = code.replace(sample.old, sample.new)
            val combined = edited + "\n" + realCode(if (sample.file == appPath) swapsPath else appPath)
            assertFalse(
                "app: rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'",
                rules.find { it.first == sample.rule }!!.second(combined)
            )
        }
    }

    @Test
    fun `the lazy rule stays quiet on a harmless edit`() {
        for (sample in quiet) {
            val code = realCode(sample.file)
            val edited = code.replace(sample.old, sample.new)
            val combined = edited + "\n" + realCode(if (sample.file == appPath) swapsPath else appPath)
            assertTrue(
                "app: rule ${sample.rule} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'",
                rules.find { it.first == sample.rule }!!.second(combined)
            )
        }
    }
}
