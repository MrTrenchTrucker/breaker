package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one construction change the take-start capture seam made, re-pointed to the as built
 * code: there is NO change to the take port's signature. The recorder is a new last, defaulted
 * parameter on `DictationRunner`; the composition root passes it by name; `TakePort.begin()` and
 * `TakePortAdapter.begin()` are unchanged and argument free; and the test fake `Rig` passes the
 * recorder as the last positional argument.
 *
 * Every rule says which text it reads in its comment. Each rule holds on the real files, is
 * broken by at least two edited samples, and stays quiet on a harmless edit; an edit whose
 * target text is missing or occurs twice fails by name.
 */
internal class TakePortSignatureGateTest {

    /** The runner whose constructor gained the recorder parameter. */
    private val runner: String = "kotlin/dev/breaker/dictation/wiring/DictationRunner.kt"

    /** The composition root that passes the recorder into the runner. */
    private val component: String = "kotlin/dev/breaker/dictation/wiring/DictationComponent.kt"

    /** The tile port whose begin must stay argument free. */
    private val ports: String = "kotlin/dev/breaker/dictation/wiring/TilePorts.kt"

    /** The adapter whose begin must stay argument free. */
    private val portAdapter: String = "kotlin/dev/breaker/dictation/wiring/TakePortAdapter.kt"

    /** The test fake that builds the runner. */
    private val rig: String = "src/test/kotlin/dev/breaker/dictation/wiring/WiringFakes.kt"

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
     * R1 reads [runner]: the whole `DictationRunner` constructor, with `takeStart` last and
     * defaulted. R2 reads [component]: the named `takeStart =` argument. R3 reads [ports] and
     * [portAdapter] together: two argument free `fun begin(): BeginResult`. R4 reads [rig]: the
     * runner built with the recorder last.
     */
    private val rules: List<Rule> = listOf(
        Rule(
            name = "RUNNER_RECORDER_IS_LAST_AND_DEFAULTED",
            pattern = """class\s+DictationRunner\(\s*private val dictate: DictateUseCase,\s*private val send: SendUseCase,\s*""" +
                """private val audio: AudioSource,\s*private val controller: DictationServiceController,\s*""" +
                """private val capture: ReportingMicSource\?,\s*private val takeStart: TakeStartRecorder\? = null,\s*\)""",
            file = "RUNNER",
        ),
        Rule(
            name = "COMPOSITION_ROOT_PASSES_THE_RECORDER",
            pattern = """\btakeStart\s*=\s*object\s*:\s*TakeStartRecorder\s*\{\s*override fun capture\(\)\s*\{\s*try\s*\{\s*CommitServices\.takeStartCapture\(\)\.capture\(\)\s*\}\s*catch\s*\(t:\s*Throwable\)\s*\{\s*TakeStartAnchor\.capture\(null\)\s*if\s*\(t is Error\)\s*throw t\s*\}\s*\}\s*override fun clear\(\)\s*\{\s*TakeStartAnchor\.clear\(\)\s*\}\s*\}""",
            file = "COMPONENT",
        ),
        Rule(
            name = "PORT_BEGIN_IS_ARGUMENT_FREE",
            pattern = """fun\s+begin\s*\(\s*\)\s*:\s*BeginResult""",
            times = 2,
            file = "PORTS_PLUS_ADAPTER",
        ),
        Rule(
            name = "RIG_PASSES_THE_RECORDER_LAST",
            pattern = """DictationRunner\(dictate, SendUseCase\(committer, history\), audio, controller, capture, takeStart\)""",
            file = "RIG",
        ),
    )

    /** Harmless edits: comments only, each of which keeps every rule of its file true. */
    private val quiet: List<Quiet> = listOf(
        Quiet("RUNNER", "    private val takeStart: TakeStartRecorder? = null,", "    // the recorder is optional\n    private val takeStart: TakeStartRecorder? = null,"),
        Quiet(
            "COMPONENT",
            "            takeStart = object : TakeStartRecorder {",
            "            // the recorder is supplied here\n            takeStart = object : TakeStartRecorder {",
        ),
        Quiet("PORTS_PLUS_ADAPTER", "    override fun begin(): BeginResult = runner.begin()", "    // begin passes straight through\n    override fun begin(): BeginResult = runner.begin()"),
        Quiet("RIG", "    takeStart: TakeStartRecorder? = null,", "    // the fake recorder\n    takeStart: TakeStartRecorder? = null,"),
    )

    /** Each sample edits one file so that the rule it names stops holding. */
    private val firing: List<Sample> = listOf(
        Sample("RUNNER_RECORDER_IS_LAST_AND_DEFAULTED", "    private val takeStart: TakeStartRecorder? = null,", "    private val takeStart: TakeStartRecorder?,"),
        Sample(
            "RUNNER_RECORDER_IS_LAST_AND_DEFAULTED",
            "    private val takeStart: TakeStartRecorder? = null,",
            "    private val takeStart: TakeStartRecorder? = null,\n    private val extra: Int = 0,",
        ),
        Sample(
            "COMPOSITION_ROOT_PASSES_THE_RECORDER",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Throwable) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        TakeStartAnchor.capture(null)\n                        if (t is Error) throw t\n                    }\n                }\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
            "",
        ),
        // The ruling's firing sample: the OLD capture-only SAM lambda must FAIL the re-pointed rule,
        // so the rule still forbids a root that records the window but never drops it.
        Sample(
            "COMPOSITION_ROOT_PASSES_THE_RECORDER",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Throwable) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        TakeStartAnchor.capture(null)\n                        if (t is Error) throw t\n                    }\n                }\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
            "            takeStart = TakeStartRecorder { CommitServices.takeStartCapture().capture() },",
        ),
        Sample(
            "COMPOSITION_ROOT_PASSES_THE_RECORDER",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Throwable) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        TakeStartAnchor.capture(null)\n                        if (t is Error) throw t\n                    }\n                }\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() { CommitServices.takeStartCapture().capture() }\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
        ),
        Sample(
            "COMPOSITION_ROOT_PASSES_THE_RECORDER",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Throwable) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        TakeStartAnchor.capture(null)\n                        if (t is Error) throw t\n                    }\n                }\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Throwable) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        if (t is Error) throw t\n                    }\n                }\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
        ),
        Sample(
            "COMPOSITION_ROOT_PASSES_THE_RECORDER",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Throwable) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        TakeStartAnchor.capture(null)\n                        if (t is Error) throw t\n                    }\n                }\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Exception) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        TakeStartAnchor.capture(null)\n                        if (t is Error) throw t\n                    }\n                }\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
        ),
        Sample(
            "COMPOSITION_ROOT_PASSES_THE_RECORDER",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Throwable) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        TakeStartAnchor.capture(null)\n                        if (t is Error) throw t\n                    }\n                }\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
            "            takeStart = object : TakeStartRecorder {\n                override fun capture() {\n                    try {\n                        CommitServices.takeStartCapture().capture()\n                    } catch (t: Throwable) {\n                        // The build can throw before the capture's own guard exists: the hopper\n                        // asks for the main looper, which a plain JVM test has not got. Fail\n                        // closed the same way the capture does, so the take still records and\n                        // its commit takes the no-field clipboard path.\n                        TakeStartAnchor.capture(null)\n                    }\n                }\n                override fun clear() { TakeStartAnchor.clear() }\n            },",
        ),
        Sample("PORT_BEGIN_IS_ARGUMENT_FREE", "    fun begin(): BeginResult", "    fun begin(windowId: Int): BeginResult"),
        Sample(
            "PORT_BEGIN_IS_ARGUMENT_FREE",
            "    override fun begin(): BeginResult = runner.begin()",
            "    override fun begin(windowId: Int): BeginResult = runner.begin()",
        ),
        Sample(
            "RIG_PASSES_THE_RECORDER_LAST",
            "DictationRunner(dictate, SendUseCase(committer, history), audio, controller, capture, takeStart)",
            "DictationRunner(dictate, SendUseCase(committer, history), audio, controller, capture)",
        ),
        Sample(
            "RIG_PASSES_THE_RECORDER_LAST",
            "DictationRunner(dictate, SendUseCase(committer, history), audio, controller, capture, takeStart)",
            "DictationRunner(dictate, SendUseCase(committer, history), audio, takeStart, controller, capture)",
        ),
    )

    /** [old] replaced by [new] once. Fails by name when the text is missing or occurs twice. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a sample lost its text '$old'" }
        check(text.indexOf(old, at + 1) < 0) { "app: a sample text occurs twice '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** The text a rule reads, by key. */
    private fun textOf(key: String): String = when (key) {
        "RUNNER" -> AppSourceFiles.mainFile(runner)
        "COMPONENT" -> AppSourceFiles.mainFile(component)
        "PORTS_PLUS_ADAPTER" -> AppSourceFiles.mainFile(ports) + "\n" + AppSourceFiles.mainFile(portAdapter)
        "PORT_ADAPTER" -> AppSourceFiles.mainFile(portAdapter)
        "RIG" -> File(AppSourceFiles.moduleRoot, rig).readText(Charsets.UTF_8)
        else -> error("app: no source named $key")
    }

    /** The code a rule reads, with [old] replaced by [new] once; raw rules keep the literal text. */
    private fun codeOf(rule: Rule, old: String? = null, new: String = ""): String {
        val text = textOf(rule.file())
        val edited = if (old == null) text else edit(text, old, new)
        return if (rule.raw) edited else AppSourceFiles.strip(edited).code
    }

    @Test
    fun `every take port signature rule holds on the real port`() {
        val broken = rules.filter { rule -> !rule.holds(codeOf(rule)) }.map { it.name }
        assertTrue("app: the port breaks rules $broken", broken.isEmpty())
    }

    @Test
    fun `the take port signature rules fire on their edited samples`() {
        val byName = rules.associateBy { it.name }
        val unfired = firing.filter { sample ->
            val rule = byName.getValue(sample.rule)
            rule.holds(codeOf(rule, sample.old, sample.new))
        }.map { "${it.rule}: '${it.old}' => '${it.new}'" }
        assertTrue("app: samples that leave their rule true: $unfired", unfired.isEmpty())
    }

    @Test
    fun `the take port signature samples cover each rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a rule name is used twice", names.toSet().size, names.size)
        val unknown = firing.map { it.rule }.filter { it !in names }
        assertTrue("app: samples of unknown rules: $unknown", unknown.isEmpty())
        val thin = names.filter { name -> firing.count { it.rule == name } < 2 }
        assertTrue("app: rules with fewer than two firing samples: $thin", thin.isEmpty())
    }

    @Test
    fun `no take port signature rule fires on a harmless edit`() {
        val broken = quiet.flatMap { q ->
            val edited = edit(textOf(q.file), q.old, q.new)
            rules.filter { it.file() == q.file }.filter { rule ->
                !rule.holds(if (rule.raw) edited else AppSourceFiles.strip(edited).code)
            }.map { "${it.name} on '${q.old}'" }
        }
        assertTrue("app: a harmless edit broke a rule: $broken", broken.isEmpty())
    }
}
