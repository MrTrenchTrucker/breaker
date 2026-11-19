package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins where the take-start window is captured, after the seam was ruled into
 * `DictationRunner.begin` through the `TakeStartRecorder` port: the call rides the runner's
 * begin, inside the existing try, after the session compare-and-set and before the recording
 * starts; the composition root supplies the recorder; the take port's `begin()` still takes no
 * arguments; and no main source of the overlay module reads a clock or a window.
 *
 * Every rule says which text it reads in its comment. Each rule holds on the real files, is
 * broken by at least two edited samples, and stays quiet on a harmless edit; an edit whose
 * target text is missing or occurs twice fails by name.
 */
internal class TakeStartCaptureGateTest {

    /** The runner whose begin captures the take-start window. */
    private val runner: String = "kotlin/dev/breaker/dictation/wiring/DictationRunner.kt"

    /** The composition root that adapts the commit module's capture into the recorder port. */
    private val component: String = "kotlin/dev/breaker/dictation/wiring/DictationComponent.kt"

    /** The tile port whose begin must stay argument free. */
    private val ports: String = "kotlin/dev/breaker/dictation/wiring/TilePorts.kt"

    /** The adapter that passes the port's begin straight to the runner. */
    private val portAdapter: String = "kotlin/dev/breaker/dictation/wiring/TakePortAdapter.kt"

    /** The Home adapter, which settles a take without ever sending it. */
    private val homeAdapter: String = "kotlin/dev/breaker/dictation/wiring/HomeTakeAdapter.kt"
    private val homeWiring: String = "kotlin/dev/breaker/dictation/wiring/HomeTakeWiring.kt"

    /** The overlay module's main sources, which must read no clock and no window. */
    private val overlay: String = "android/modules/overlay/src/main/kotlin"

    private class Rule(
        val name: String,
        val pattern: String,
        val times: Int = 1,
        val file: String? = null,
        /** True when the rule reads the literal text too, not only the comment free code. */
        val raw: Boolean = false,
    ) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
        fun file(): String = file ?: "RUNNER"
    }

    private class Sample(val rule: String, val old: String, val new: String)

    private class Quiet(val file: String, val old: String, val new: String)

    /**
     * R1 reads [runner]: the successful `session.compareAndSet(idle, recording)`, then the `try {`,
     * then `takeStart?.capture()`, then `dictate.startCapture(recording, audio)`. The capture must
     * sit inside the existing try, after the compare-and-set won the session and before the
     * recording begins.
     */
    private val rules: List<Rule> = listOf(
        Rule(
            name = "CAPTURE_RIDES_THE_RUNNERS_BEGIN",
            pattern = """session\.compareAndSet\(idle, recording\)[^}]*try\s*\{[^}]*takeStart\?\.capture\(\)[^}]*dictate\.startCapture\(recording, audio\)""",
            file = "RUNNER",
        ),
        Rule(
            name = "COMPOSITION_ROOT_SUPPLIES_THE_RECORDER",
            pattern = """\btakeStart\s*=\s*object\s*:\s*TakeStartRecorder\s*\{\s*override fun capture\(\)\s*\{\s*try\s*\{\s*CommitServices\.takeStartCapture\(\)\.capture\(\)\s*\}\s*catch\s*\(t:\s*Throwable\)\s*\{\s*TakeStartAnchor\.capture\(null\)\s*if\s*\(t is Error\)\s*throw t\s*\}\s*\}\s*override fun generation\(\)\s*=\s*TakeStartAnchor\.generation\(\)\s*override fun clearIf\(generation:\s*Long\)\s*=\s*TakeStartAnchor\.clearIfGeneration\(generation\)\s*override fun clear\(\)\s*\{\s*TakeStartAnchor\.clear\(\)\s*\}\s*\}""",
            file = "COMPONENT",
        ),
        Rule(
            name = "TAKE_PORT_BEGIN_TAKES_NO_ARGUMENTS",
            pattern = """fun\s+begin\s*\(\s*\)\s*:\s*BeginResult""",
            file = "PORTS",
        ),
        Rule(
            // F5 pin: the Home settle saves and copies AFTER finish and never sends, which is
            // why LazyTakePort.finish must not clear the anchor. If Home ever grows a send,
            // this rule fires and the clear has to be re-thought, not silently skipped.
            name = "HOME_NEVER_SENDS",
            pattern = """\btake\.send\(""",
            times = 0,
            file = "HOME_ADAPTER",
        ),
        Rule(
            name = "SEND_GUARD_GOES_UP_FIRST",
            pattern = """fun send\(\): SendResult\? \{\s*sendInFlight\.set\(true\)""",
            times = 1,
            file = "RUNNER",
        ),
        Rule(
            name = "LAZY_TAKE_PORT_STAYS_FILE_PRIVATE",
            pattern = """\bprivate\s+class\s+LazyTakePort""",
            times = 1,
            file = "HOME_WIRING",
        ),
        Rule(
            name = "HOME_WIRING_SENDS_ONLY_THROUGH_THE_PORT",
            pattern = """\.\s*send\s*\(""",
            times = 1,
            file = "HOME_WIRING",
        ),
        Rule(
            name = "HOME_ADAPTER_NEVER_SENDS_AT_ALL",
            pattern = """\.\s*send\s*\(""",
            times = 0,
            file = "HOME_ADAPTER",
        ),
        Rule(
            name = "OVERLAY_READS_NO_CLOCK_OR_WINDOW",
            pattern = """\bSystem\s*\.\s*currentTimeMillis\b|\bSystem\s*\.\s*nanoTime\b|\bSystemClock\b|""" +
                """\bInstant\s*\.\s*now\b|\bLocalDateTime\s*\.\s*now\b|\bClock\s*\.\s*system\b|""" +
                """\bwindowId\b|\bgetWindowId\b|\bAccessibilityWindowInfo\b""",
            times = 0,
            file = "OVERLAY_ALL",
        ),
    )

    /** Harmless edits: comments and whitespace only, each of which keeps every rule of its file true. */
    private val quiet: List<Quiet> = listOf(
        Quiet("RUNNER", "    fun begin(): BeginResult {", "    // the take starts here\n    fun begin(): BeginResult {"),
        Quiet("RUNNER", "    fun send(): SendResult? {", "    // the commit\n    fun send(): SendResult? {"),
        Quiet("COMPONENT", "        val built = DictationRunner(", "        // the runner is built here\n        val built = DictationRunner("),
        Quiet("PORTS", "    /** Starts listening. */", "    /** Starts listening. */ "),
        Quiet("HOME_ADAPTER", "    private fun finishAndSettle() {", "    // the Home settle\n    private fun finishAndSettle() {"),
        Quiet("HOME_WIRING", "private class LazyTakePort(supplier: () -> TakePort) : TakePort {", "// the Home port\nprivate class LazyTakePort(supplier: () -> TakePort) : TakePort {"),
        Quiet("OVERLAY_ALL", "    /** The most characters a notice keeps. */", "    // the notice rules\n    /** The most characters a notice keeps. */"),
    )

    /** Each sample edits one file so that the rule it names stops holding. */
    private val firing: List<Sample> = listOf(
        Sample(
            "CAPTURE_RIDES_THE_RUNNERS_BEGIN",
            "            takeStart?.capture()  // inside the try: a recorder that throws takes the same rollback\n            dictate.startCapture(recording, audio)",
            "            dictate.startCapture(recording, audio)\n            takeStart?.capture()  // inside the try: a recorder that throws takes the same rollback",
        ),
        Sample("CAPTURE_RIDES_THE_RUNNERS_BEGIN", "            takeStart?.capture()  // inside the try: a recorder that throws takes the same rollback\n", ""),
        Sample(
            "COMPOSITION_ROOT_SUPPLIES_THE_RECORDER",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Throwable) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        TakeStartAnchor.capture(null)\n                        if (t is Error) throw t\n                    }\n                }\n                override fun generation() = TakeStartAnchor.generation()\n                override fun clearIf(generation: Long) = TakeStartAnchor.clearIfGeneration(generation)\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
            "",
        ),
        Sample(
            "COMPOSITION_ROOT_SUPPLIES_THE_RECORDER",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Throwable) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        TakeStartAnchor.capture(null)\n                        if (t is Error) throw t\n                    }\n                }\n                override fun generation() = TakeStartAnchor.generation()\n                override fun clearIf(generation: Long) = TakeStartAnchor.clearIfGeneration(generation)\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
            "            takeStart = TakeStartRecorder { CommitServices.takeStartCapture().capture() },",
        ),
        Sample("TAKE_PORT_BEGIN_TAKES_NO_ARGUMENTS", "    fun begin(): BeginResult", "    fun begin(windowId: Int): BeginResult"),
        Sample("TAKE_PORT_BEGIN_TAKES_NO_ARGUMENTS", "    fun begin(): BeginResult", "    fun begin(windowId: Int? = null): BeginResult"),
        Sample(
            "HOME_NEVER_SENDS",
            "                    try { take.finish() } catch (e: Exception) { null }",
            "                    try { take.finish() } catch (e: Exception) { null }\n                    take.send()",
        ),
        Sample(
            "HOME_NEVER_SENDS",
            "                try {\n                    take.resetAfterTaken()",
            "                take.send()\n                try {\n                    take.resetAfterTaken()",
        ),
        Sample(
            "SEND_GUARD_GOES_UP_FIRST",
            "    fun send(): SendResult? {\n        sendInFlight.set(true)\n",
            "    fun send(): SendResult? {\n        val current = session.get()\n        sendInFlight.set(true)\n",
        ),
        Sample(
            "SEND_GUARD_GOES_UP_FIRST",
            "    fun send(): SendResult? {\n        sendInFlight.set(true)\n",
            "    fun send(): SendResult? {\n",
        ),
        Sample(
            "LAZY_TAKE_PORT_STAYS_FILE_PRIVATE",
            "private class LazyTakePort(supplier: () -> TakePort) : TakePort {",
            "internal class LazyTakePort(supplier: () -> TakePort) : TakePort {",
        ),
        Sample(
            "LAZY_TAKE_PORT_STAYS_FILE_PRIVATE",
            "private class LazyTakePort(supplier: () -> TakePort) : TakePort {",
            "public class LazyTakePort(supplier: () -> TakePort) : TakePort {",
        ),
        Sample(
            "LAZY_TAKE_PORT_STAYS_FILE_PRIVATE",
            "private class LazyTakePort(supplier: () -> TakePort) : TakePort {",
            "class LazyTakePort(supplier: () -> TakePort) : TakePort {",
        ),
        Sample(
            "HOME_WIRING_SENDS_ONLY_THROUGH_THE_PORT",
            "        val result = port.send()",
            "        val result = port.send()\n        take.send()",
        ),
        Sample(
            "HOME_WIRING_SENDS_ONLY_THROUGH_THE_PORT",
            "    override fun resetAfterTaken() {\n        port.resetAfterTaken()",
            "    override fun resetAfterTaken() {\n        port.send()\n        port.resetAfterTaken()",
        ),
        Sample(
            "HOME_ADAPTER_NEVER_SENDS_AT_ALL",
            "    private fun finishAndSettle() {",
            "    private fun finishAndSettle() {\n        port.send()",
        ),
        Sample(
            "HOME_ADAPTER_NEVER_SENDS_AT_ALL",
            "    private fun finishAndSettle() {",
            "    private fun finishAndSettle() {\n        home.send()",
        ),
        Sample(
            "OVERLAY_READS_NO_CLOCK_OR_WINDOW",
            "    const val MAX_CHARS: Int = 80",
            "    const val MAX_CHARS: Int = 80\n    private val plantedClock = System.currentTimeMillis()",
        ),
        Sample(
            "OVERLAY_READS_NO_CLOCK_OR_WINDOW",
            "    private val breakCodes = setOf(0x0A, 0x09, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)",
            "    private val breakCodes = setOf(0x0A, 0x09, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)\n" +
                "    private val windowId: Int = 0",
        ),
    )

    /** [old] replaced by [new] once. Fails by name when the text is missing or occurs twice. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a sample lost its text '$old'" }
        check(text.indexOf(old, at + 1) < 0) { "app: a sample text occurs twice '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** The repository root: the nearest folder at or above the app module holding the root settings script. */
    private fun repoRoot(): File =
        generateSequence(AppSourceFiles.moduleRoot) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("app: no folder with settings.gradle.kts at or above ${AppSourceFiles.moduleRoot}")

    /** Every Kotlin file of the overlay module's main source set, joined in path order. */
    private fun overlayCode(): String {
        val start = File(repoRoot(), overlay)
        check(start.isDirectory) { "app: no overlay source folder at $start" }
        val files = start.walkTopDown().onEnter { it.name != "build" }
            .filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        check(files.isNotEmpty()) { "app: no overlay Kotlin source at $start" }
        return files.joinToString("\n") { it.readText(Charsets.UTF_8) }
    }

    /** The text a rule reads, by key. */
    private fun textOf(key: String): String = when (key) {
        "RUNNER" -> AppSourceFiles.mainFile(runner)
        "COMPONENT" -> AppSourceFiles.mainFile(component)
        "PORTS" -> AppSourceFiles.mainFile(ports)
        "HOME_ADAPTER" -> AppSourceFiles.mainFile(homeAdapter)
        "HOME_WIRING" -> AppSourceFiles.mainFile(homeWiring)
        "OVERLAY_ALL" -> overlayCode()
        else -> error("app: no source named $key")
    }

    /** The code a rule reads, with [old] replaced by [new] once; raw rules keep the literal text. */
    private fun codeOf(rule: Rule, old: String? = null, new: String = ""): String {
        val text = textOf(rule.file())
        val edited = if (old == null) text else edit(text, old, new)
        return if (rule.raw) edited else AppSourceFiles.strip(edited).code
    }

    @Test
    fun `every take start capture rule holds on the real wiring`() {
        val broken = rules.filter { rule -> !rule.holds(codeOf(rule)) }.map { it.name }
        assertTrue("app: the wiring breaks rules $broken", broken.isEmpty())
    }

    @Test
    fun `the take start capture rules fire on their edited samples`() {
        val byName = rules.associateBy { it.name }
        val unfired = firing.filter { sample ->
            val rule = byName.getValue(sample.rule)
            rule.holds(codeOf(rule, sample.old, sample.new))
        }.map { "${it.rule}: '${it.old}' => '${it.new}'" }
        assertTrue("app: samples that leave their rule true: $unfired", unfired.isEmpty())
    }

    @Test
    fun `the take start capture samples cover each rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a rule name is used twice", names.toSet().size, names.size)
        val unknown = firing.map { it.rule }.filter { it !in names }
        assertTrue("app: samples of unknown rules: $unknown", unknown.isEmpty())
        val thin = names.filter { name -> firing.count { it.rule == name } < 2 }
        assertTrue("app: rules with fewer than two firing samples: $thin", thin.isEmpty())
    }

    @Test
    fun `no take start capture rule fires on a harmless edit`() {
        val broken = quiet.flatMap { q ->
            val edited = edit(textOf(q.file), q.old, q.new)
            rules.filter { it.file() == q.file }.filter { rule ->
                !rule.holds(if (rule.raw) edited else AppSourceFiles.strip(edited).code)
            }.map { "${it.name} on '${q.old}'" }
        }
        assertTrue("app: a harmless edit broke a rule: $broken", broken.isEmpty())
    }
}
