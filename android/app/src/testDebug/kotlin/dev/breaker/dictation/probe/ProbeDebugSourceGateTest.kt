package dev.breaker.dictation.probe

import dev.breaker.dictation.gates.AppSourceFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reads the app's debug sources and holds them to four rules: the size limits, no network or microphone
 * name, one inset listener call in the activity, and a provider whose data methods do nothing. Each rule
 * runs on the real files and on a planted sample that it must refuse.
 */
internal class ProbeDebugSourceGateTest {

    private val activityPath: String = "kotlin/dev/breaker/dictation/probe/KeyboardProbeActivity.kt"
    private val providerPath: String = "kotlin/dev/breaker/dictation/probe/ProbeStartProvider.kt"
    private val listenerCall: String = "setOnApplyWindowInsetsListener"
    private val networkOrMicrophone: Regex = Regex("""\bjava\.net\b|\bINTERNET\b|\bRECORD_AUDIO\b|\bAudioRecord\b""")
    private val providerBodies: List<Regex> = listOf(
        Regex("""override fun query\([^)]*\)\s*:\s*Cursor\?\s*=\s*null"""),
        Regex("""override fun insert\([^)]*\)\s*:\s*Uri\?\s*=\s*null"""),
        Regex("""override fun update\([^)]*\)\s*:\s*Int\s*=\s*0"""),
        Regex("""override fun delete\([^)]*\)\s*:\s*Int\s*=\s*0"""),
    )

    /** Every file under the app's debug source folder, by path below it, with forward slashes. */
    private fun debugFiles(): Map<String, String> {
        val root = File(AppSourceFiles.moduleRoot, "src/debug")
        check(root.isDirectory) { "probe: no debug source folder at $root" }
        val result: MutableMap<String, String> = LinkedHashMap()
        for (file in root.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
            result[file.relativeTo(root).path.replace(File.separatorChar, '/')] = file.readText(Charsets.UTF_8)
        }
        return result
    }

    private fun lineCount(text: String): Int = text.count { it == '\n' }

    private fun newlines(count: Int): String = "\n".repeat(count)

    /** One problem per Kotlin file at or over its limit: the activity under 300 lines, the others under 200. */
    private fun sizeProblems(files: Map<String, String>): List<String> {
        val found: MutableList<String> = ArrayList()
        for ((name, text) in files) {
            if (name.endsWith(".kt")) {
                val limit: Int = if (name.endsWith("KeyboardProbeActivity.kt")) 300 else 200
                if (lineCount(text) >= limit) {
                    found.add("probe: $name has ${lineCount(text)} lines, over the limit of $limit")
                }
            }
        }
        return found
    }

    /** One problem per file that names the network or the microphone. */
    private fun networkProblems(files: Map<String, String>): List<String> =
        files.filter { networkOrMicrophone.containsMatchIn(it.value) }.keys.map { "probe: $it names the network or the microphone" }

    /** One problem unless the text holds exactly one inset listener call. */
    private fun listenerProblems(text: String): List<String> {
        val calls: Int = Regex(Regex.escape(listenerCall)).findAll(text).count()
        return if (calls == 1) emptyList() else listOf("probe: the activity has $calls inset listener calls, expected one")
    }

    /** One problem per data method whose body does not return null or 0. */
    private fun providerProblems(text: String): List<String> =
        providerBodies.filter { !it.containsMatchIn(text) }.map { "probe: a provider data method does not return null or 0: ${it.pattern}" }

    private val wantedDeclaration: Regex = Regex("""\bval\s+wanted\b""")
    private val wantedHelper: Regex = Regex("""val\s+wanted\s*:\s*Boolean\s*=\s*activityInsetsWanted\(\s*setting\s*\)""")
    private val attachCall: Regex = Regex("""setOnApplyWindowInsetsListener\(""")
    private val wantedArgument: Regex = Regex("""setOnApplyWindowInsetsListener\(\s*if\s*\(\s*wanted\s*\)\s*activityInsets\s*else\s*null\s*\)""")
    private val startIf: Regex = Regex("""if\s*\(\s*startNeedsFullApply\(\s*setting\s*\)\s*\)\s*\{""")
    private val fullApply: Regex = Regex("""appProbeSwitch\(\s*app\s*\)\s*\.\s*apply\(\s*setting\s*\)""")
    private val declaredProblem: String = "probe: the activity must declare wanted once, as activityInsetsWanted(setting)"
    private val attachProblem: String = "probe: the activity attaches its inset listener with an argument other than if (wanted) activityInsets else null"
    private val startProblem: String = "probe: the provider must start the full apply under one if (startNeedsFullApply(setting))"
    private val insideProblem: String = "probe: the provider's full apply must lie inside that if"

    /** Copies [text] with [old] replaced by [new]; fails by name when [old] is not in the text. */
    private fun plantedCopy(text: String, old: String, new: String): String {
        check(text.contains(old)) { "probe: the plant's old text is missing from the file: $old" }
        return text.replace(old, new)
    }

    /** The source text with comments and literal text blanked, so only code is read. */
    private fun codeOf(text: String): String = AppSourceFiles.strip(text).code

    /** The problems with the wanted rule on comment-free code: one declaration that calls the helper, and one attach call that takes the wanted argument. */
    private fun wantedProblems(code: String): List<String> {
        val found: MutableList<String> = ArrayList()
        val declarations: Int = wantedDeclaration.findAll(code).count()
        if (declarations != 1 || !wantedHelper.containsMatchIn(code)) {
            found.add(declaredProblem)
        }
        val calls: Int = attachCall.findAll(code).count()
        val matching: Int = wantedArgument.findAll(code).count()
        if (calls != 1 || matching != calls) {
            found.add(attachProblem)
        }
        return found
    }

    /** The index of the brace that closes the brace at [open], or -1 when it is never closed. */
    private fun closeOf(code: String, open: Int): Int {
        var depth = 0
        var close = -1
        var index = open
        while (close < 0 && index < code.length) {
            if (code[index] == '{') {
                depth += 1
            } else if (code[index] == '}') {
                depth -= 1
                if (depth == 0) {
                    close = index
                }
            }
            index += 1
        }
        return close
    }

    /** The problems with the start rule on comment-free code: one start if, and one full apply inside its braces. */
    private fun startProblems(code: String): List<String> {
        val starts: List<MatchResult> = startIf.findAll(code).toList()
        if (starts.size != 1) {
            return listOf(startProblem)
        }
        val applies: List<MatchResult> = fullApply.findAll(code).toList()
        if (applies.size != 1) {
            return listOf(insideProblem)
        }
        val open: Int = starts[0].range.last
        val close: Int = closeOf(code, open)
        val at: Int = applies[0].range.first
        return if (at > open && close > open && at < close) emptyList() else listOf(insideProblem)
    }

    @Test
    fun `the activity file is under three hundred lines and every other debug file under two hundred`() {
        assertEquals("probe: debug files over their limit", emptyList<String>(), sizeProblems(debugFiles()))
        assertEquals(
            "probe: a planted 300 line activity must be refused",
            1,
            sizeProblems(mapOf(activityPath to newlines(300))).size,
        )
        assertEquals(
            "probe: a planted 299 line activity must be allowed",
            0,
            sizeProblems(mapOf(activityPath to newlines(299))).size,
        )
        assertEquals(
            "probe: a planted 200 line file must be refused",
            1,
            sizeProblems(mapOf("kotlin/dev/breaker/dictation/probe/ProbeLog.kt" to newlines(200))).size,
        )
        assertEquals(
            "probe: a planted 199 line file must be allowed",
            0,
            sizeProblems(mapOf("kotlin/dev/breaker/dictation/probe/ProbeLog.kt" to newlines(199))).size,
        )
    }

    @Test
    fun `no debug file names the network or the microphone`() {
        assertEquals("probe: debug files naming the network or the microphone", emptyList<String>(), networkProblems(debugFiles()))
        for (planted in listOf(
            "import java.net.URL",
            "<uses-permission android:name=\"android.permission.INTERNET\" />",
            "val needed = RECORD_AUDIO",
            "val recorder = AudioRecord(source, rate, channels, format, size)",
        )) {
            assertEquals("probe: a planted line must be refused: $planted", 1, networkProblems(mapOf("planted.kt" to planted)).size)
        }
    }

    @Test
    fun `the activity attaches the activity inset listener in one place only`() {
        val activity: String = debugFiles()[activityPath] ?: error("probe: the activity file is missing")
        assertEquals("probe: the activity must attach its inset listener once", emptyList<String>(), listenerProblems(activity))
        assertEquals(
            "probe: a planted activity with two listener calls must be refused",
            1,
            listenerProblems(activity + "\n" + "window.decorView.$listenerCall(null)\n").size,
        )
    }

    @Test
    fun `the provider does nothing in query insert update delete`() {
        val provider: String = debugFiles()[providerPath] ?: error("probe: the provider file is missing")
        assertEquals("probe: the provider data methods must return null or 0", emptyList<String>(), providerProblems(provider))
        val planted: String = provider.replace("override fun insert(uri: Uri, values: ContentValues?): Uri? = null", "override fun insert(uri: Uri, values: ContentValues?): Uri? = uri")
        assertEquals("probe: a planted insert that returns its uri must be refused", 1, providerProblems(planted).size)
    }

    @Test
    fun `the activity attaches its inset listener only through the wanted helper`() {
        val activity: String = debugFiles()[activityPath] ?: error("probe: the activity file is missing")
        assertEquals("probe: the activity must use the wanted helper", emptyList<String>(), wantedProblems(codeOf(activity)))
        val declaration = "val wanted: Boolean = activityInsetsWanted(setting)"
        val attach = "window.decorView.setOnApplyWindowInsetsListener(if (wanted) activityInsets else null)"
        assertEquals(
            "probe: a planted val wanted = true must be refused",
            listOf(declaredProblem),
            wantedProblems(codeOf(plantedCopy(activity, declaration, "val wanted: Boolean = true"))),
        )
        assertEquals(
            "probe: a planted always-on wanted must be refused",
            listOf(declaredProblem),
            wantedProblems(codeOf(plantedCopy(activity, declaration, "val wanted: Boolean = setting != KeyboardSignalSetting.OFF || true"))),
        )
        assertEquals(
            "probe: a planted wanted for FIELD_FOCUS must be refused",
            listOf(declaredProblem),
            wantedProblems(codeOf(plantedCopy(activity, declaration, "val wanted: Boolean = activityInsetsWanted(KeyboardSignalSetting.FIELD_FOCUS)"))),
        )
        assertEquals(
            "probe: a planted attach with activityInsets alone must be refused",
            listOf(attachProblem),
            wantedProblems(codeOf(plantedCopy(activity, attach, "window.decorView.setOnApplyWindowInsetsListener(activityInsets)"))),
        )
    }

    @Test
    fun `the provider applies in full only under the start helper`() {
        val provider: String = debugFiles()[providerPath] ?: error("probe: the provider file is missing")
        assertEquals("probe: the provider must apply in full under the start helper", emptyList<String>(), startProblems(codeOf(provider)))
        val start = "if (startNeedsFullApply(setting)) {"
        assertEquals(
            "probe: a planted if (true) must be refused",
            listOf(startProblem),
            startProblems(codeOf(plantedCopy(provider, start, "if (true) {"))),
        )
        assertEquals(
            "probe: a planted if that bypasses the helper must be refused",
            listOf(startProblem),
            startProblems(codeOf(plantedCopy(provider, start, "if (setting != KeyboardSignalSetting.OFF) {"))),
        )
        assertEquals(
            "probe: a planted inverted helper must be refused",
            listOf(startProblem),
            startProblems(codeOf(plantedCopy(provider, start, "if (!startNeedsFullApply(setting)) {"))),
        )
        assertEquals(
            "probe: a planted apply after the closing brace must be refused",
            listOf(insideProblem),
            startProblems(codeOf(plantedCopy(
                provider,
                "            MainLooperPost().post { appProbeSwitch(app).apply(setting) }\n        }",
                "        }\n        MainLooperPost().post { appProbeSwitch(app).apply(setting) }",
            ))),
        )
    }

    private val applyInsetsCall: Regex = Regex("""\bapplyInsets\(""")
    private val columnInsetsCall: Regex = Regex("""\bapplyInsets\(\s*column\s*\)""")
    private val decorAttach: String = "window.decorView.setOnApplyWindowInsetsListener(if (wanted) activityInsets else null)"
    private val columnCallProblem: String = "probe: the activity must call applyInsets(column) exactly once"
    private val otherInsetsProblem: String = "probe: the activity calls applyInsets with an argument other than column"
    private val decorAttachProblem: String = "probe: the decorView must keep its one activity inset listener attach"

    /** The problems with the column rule on comment-free code: one applyInsets(column) call, no other applyInsets call, one decorView attach. */
    private fun columnProblems(code: String): List<String> {
        val calls: Int = applyInsetsCall.findAll(code).count()
        val columnCalls: Int = columnInsetsCall.findAll(code).count()
        val attaches: Int = Regex(Regex.escape(decorAttach)).findAll(code).count()
        return listOfNotNull(
            columnCallProblem.takeIf { calls != 1 || columnCalls != 1 },
            otherInsetsProblem.takeIf { calls != columnCalls },
            decorAttachProblem.takeIf { attaches != 1 },
        )
    }

    @Test
    fun `the column takes the system bar and cutout insets through its own listener`() {
        val activity: String = debugFiles()[activityPath] ?: error("probe: the activity file is missing")
        assertEquals("probe: the activity must pad the column and keep the decorView attach", emptyList<String>(), columnProblems(codeOf(activity)))
        val removed: String = plantedCopy(activity, "applyInsets(column)\n", "")
        assertEquals("probe: a planted activity without the column call must be refused", listOf(columnCallProblem), columnProblems(codeOf(removed)))
        val decor: String = plantedCopy(activity, "applyInsets(column)", "applyInsets(window.decorView)")
        assertEquals("probe: a planted applyInsets(window.decorView) must be refused", listOf(columnCallProblem, otherInsetsProblem), columnProblems(codeOf(decor)))
        val twice: String = plantedCopy(activity, "applyInsets(column)\n", "applyInsets(column)\n        applyInsets(column)\n")
        assertEquals("probe: a planted activity with two column calls must be refused", listOf(columnCallProblem), columnProblems(codeOf(twice)))
    }
}
