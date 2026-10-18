package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the display-change listener that tells the floating tile when a turn or a fold moves it. The
 * listener, the tile host and the port are Android code that no JVM test can run, so the rules read the
 * real files with comments and literal text removed by the shared scanner. Each rule holds on the real
 * file, is broken by at least two edited samples and has a quiet sample; an edit whose target text is
 * missing fails by name, so a sample cannot go quiet by a typo. Two tests read every main file: the
 * listener is registered in one place only, and no second display-change source exists.
 */
internal class DisplayWiringGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "threads" to dir + "host/AppThreads.kt",
        "adapter" to dir + "host/DisplayChangeAndroid.kt",
        "host" to dir + "host/TileHost.kt",
        "tile" to dir + "host/OverlayTilePort.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)

    private fun count(code: String, pattern: String): Int = Regex(pattern).findAll(code).count()

    /** The index of the first match of [pattern] in [code], or -1 when there is none. */
    private fun at(code: String, pattern: String): Int = Regex(pattern).find(code)?.range?.first ?: -1

    private val rules: List<Rule> = listOf(
        Rule("THE_WATCH_STARTS_AND_STOPS_BESIDE_THE_GESTURE", "host") { code ->
            count(code, """\bmain\s*\.\s*post\s*\{\s*if\s*\(\s*armed\s*\)\s*displayWatch\s*\.\s*start\s*\(\s*\)\s*else\s*displayWatch\s*\.\s*stop\s*\(\s*\)\s*\}""") == 1 &&
                count(code, """\bgesture\s*\.\s*stop\s*\(\s*\)\s*\}\s*main\s*\.\s*post\s*\{\s*if\s*\(\s*armed\s*\)\s*displayWatch\s*\.\s*start\s*\(\s*\)\s*else\s*displayWatch\s*\.\s*stop\s*\(\s*\)\s*\}\s*toCoordinator\s*\{\s*it\s*\.\s*onArmedChanged\s*\(\s*armed\s*\)\s*\}""") == 1
        },
        Rule("THE_WATCH_IS_BUILT_ONCE_WITH_AN_EXPLICIT_TYPE_AND_REACHES_THE_TILE", "host") { code ->
            count(code, """\bprivate\s+val\s+displayWatch\s*:\s*DisplayWatch\s*=\s*DisplayWatch\s*\(\s*DisplayChangeAndroid\s*\(\s*context\s*,\s*main\s*\)\s*\)\s*\{\s*tile\s*\.\s*onDisplayChanged\s*\(\s*\)\s*\}""") == 1 &&
                count(code, """\bDisplayWatch\s*\(""") == 1 &&
                count(code, """\bDisplayChangeAndroid\s*\(""") == 1
        },
        Rule("THE_PORT_FORWARDS_TO_THE_TILE", "tile") { code ->
            count(code, """\bfun\s+onDisplayChanged\s*\(\s*\)\s*\{\s*tile\s*\?\.\s*onDisplayChanged\s*\(\s*\)\s*\}""") == 1
        },
        Rule("THE_LISTENER_IS_REGISTERED_ON_THE_MAIN_LOOPER", "threads") { code ->
            count(code, """\bfun\s+registerOnMain\s*\(\s*manager\s*:\s*DisplayManager\s*,\s*listener\s*:\s*DisplayManager\.DisplayListener\s*\)\s*\{\s*manager\s*\.\s*registerDisplayListener\s*\(\s*listener\s*,\s*handler\s*\)\s*\}""") == 1 &&
                count(code, """\bregisterDisplayListener\s*\(""") == 1
        },
        Rule("THE_ADAPTER_ASKS_THE_MAIN_LOOPER_TO_REGISTER", "adapter") { code ->
            count(code, """\bmain\s*\.\s*registerOnMain\s*\(\s*manager\s*,\s*listener\s*\)""") == 1 &&
                count(code, """\bregisterDisplayListener\s*\(""") == 0
        },
        Rule("THE_SAME_LISTENER_IS_UNREGISTERED", "adapter") { code ->
            count(code, """\bmanager\s*\?\.\s*unregisterDisplayListener\s*\(\s*listener\s*\)""") == 1 &&
                count(code, """\bprivate\s+val\s+listener\s*:\s*DisplayManager\.DisplayListener\s*=\s*object\s*:\s*DisplayManager\.DisplayListener\s*\{""") == 1 &&
                count(code, """\bval\s+listener\b""") == 1 &&
                count(code, """DisplayListener\s*\{""") == 1
        },
        Rule("THE_SNAPSHOT_IS_ROTATION_AND_MODE_SIZE", "adapter") { code ->
            count(code, """\bDisplay\s*\.\s*DEFAULT_DISPLAY\b""") == 1 &&
                count(code, """\.\s*rotation\b""") == 1 &&
                count(code, """\.\s*mode\b""") == 1 &&
                count(code, """\.\s*physicalWidth\b""") == 1 &&
                count(code, """\.\s*physicalHeight\b""") == 1 &&
                count(code, """\b(WindowManager|currentWindowMetrics|maximumWindowMetrics|getMetrics|displayMetrics|refreshRate|modeId|supportedModes|getRealSize|getSize|Handler|Looper)\b""") == 0
        },
        Rule("THE_ADAPTER_CATCHES_PLATFORM_FAILURES", "adapter") { code ->
            count(code, """\bcatch\s*\(\s*\w+\s*:\s*RuntimeException\s*\)""") >= 3
        },
        Rule("THE_ADAPTER_START_KEEPS_ONE_REGISTRATION", "adapter") { code ->
            val begin: Int = at(code, """\boverride\s+fun\s+start\s*\(.*\)\s*:\s*Boolean\s*\{""")
            val end: Int = at(code, """\boverride\s+fun\s+stop\s*\(""")
            begin >= 0 && end > begin && run {
                val body: String = code.substring(begin, end)
                val guard: Int = at(body, """\bif\s*\(\s*registered\s*\)\s*return\s+true\b""")
                val register: Int = at(body, """\bmain\s*\.\s*registerOnMain\s*\(\s*manager\s*,\s*listener\s*\)""")
                val flag: Int = at(body, """\bregistered\s*=\s*true\b""")
                count(body, """\bif\s*\(\s*registered\s*\)\s*return\s+true\b""") == 1 &&
                    guard >= 0 && guard < register && register < flag && flag >= 0 &&
                    count(body, """\bcatch\s*\(\s*\w+\s*:\s*RuntimeException\s*\)\s*\{\s*callback\s*=\s*null\s+false\s*\}""") == 1
            }
        },
        Rule("THE_ADAPTER_STOP_DROPS_THE_CALLBACK_AND_THE_FLAG", "adapter") { code ->
            count(code, """\boverride\s+fun\s+stop\s*\(\s*\)\s*\{\s*callback\s*=\s*null\s+if\s*\(\s*!\s*registered\s*\)\s*return\s+registered\s*=\s*false\s+try\s*\{\s*manager\s*\?\.\s*unregisterDisplayListener\s*\(\s*listener\s*\)""") == 1
        },
        Rule("THE_ADAPTER_PASSES_THE_ID_THAT_CHANGED", "adapter") { code ->
            count(code, """\boverride\s+fun\s+onDisplayChanged\s*\(\s*displayId\s*:\s*Int\s*\)\s*\{\s*callback\s*\?\.\s*invoke\s*\(\s*displayId\s*\)\s*\}""") == 1 &&
                count(code, """\binvoke\s*\(\s*-?\d""") == 0
        },
    )

    private val byName: Map<String, Rule> = rules.associateBy { it.name }

    private val firing: List<Sample> = listOf(
        Sample("THE_WATCH_STARTS_AND_STOPS_BESIDE_THE_GESTURE", "main.post { if (armed) displayWatch.start() else displayWatch.stop() }", "main.post { if (armed) displayWatch.start() }"),
        Sample("THE_WATCH_STARTS_AND_STOPS_BESIDE_THE_GESTURE", "        main.post { if (armed) displayWatch.start() else displayWatch.stop() }\n", ""),
        Sample("THE_WATCH_STARTS_AND_STOPS_BESIDE_THE_GESTURE", "        main.post { if (armed) displayWatch.start() else displayWatch.stop() }\n        toCoordinator { it.onArmedChanged(armed) }", "        toCoordinator { it.onArmedChanged(armed) }\n        main.post { if (armed) displayWatch.start() else displayWatch.stop() }"),
        Sample("THE_WATCH_IS_BUILT_ONCE_WITH_AN_EXPLICIT_TYPE_AND_REACHES_THE_TILE", "DisplayChangeAndroid(context, main)", "DisplayChangeAndroid(context, null)"),
        Sample("THE_WATCH_IS_BUILT_ONCE_WITH_AN_EXPLICIT_TYPE_AND_REACHES_THE_TILE", "private val displayWatch: DisplayWatch = DisplayWatch(", "private val displayWatch = DisplayWatch("),
        Sample("THE_WATCH_IS_BUILT_ONCE_WITH_AN_EXPLICIT_TYPE_AND_REACHES_THE_TILE", "{ tile.onDisplayChanged() }", "{ }"),
        Sample("THE_WATCH_IS_BUILT_ONCE_WITH_AN_EXPLICIT_TYPE_AND_REACHES_THE_TILE", "{ tile.onDisplayChanged() }", "{ tile.onDisplayChanged() }\n    private val spare = DisplayWatch(DisplayChangeAndroid(context, main)) { }"),
        Sample("THE_PORT_FORWARDS_TO_THE_TILE", "tile?.onDisplayChanged()", "tile!!.onDisplayChanged()"),
        Sample("THE_PORT_FORWARDS_TO_THE_TILE", "fun onDisplayChanged() {", "fun onDisplayChanged() {\n        tile?.onDisplayChanged()"),
        Sample("THE_LISTENER_IS_REGISTERED_ON_THE_MAIN_LOOPER", "manager.registerDisplayListener(listener, handler)", "manager.registerDisplayListener(listener, null)"),
        Sample("THE_LISTENER_IS_REGISTERED_ON_THE_MAIN_LOOPER", "fun registerOnMain(", "fun registerOnMainLater("),
        Sample("THE_LISTENER_IS_REGISTERED_ON_THE_MAIN_LOOPER", "manager.registerDisplayListener(listener, handler)", "manager.registerDisplayListener(listener, handler)\n        manager.registerDisplayListener(listener, handler)"),
        Sample("THE_ADAPTER_ASKS_THE_MAIN_LOOPER_TO_REGISTER", "main.registerOnMain(manager, listener)", "registerOnMain(manager, listener)"),
        Sample("THE_ADAPTER_ASKS_THE_MAIN_LOOPER_TO_REGISTER", "main.registerOnMain(manager, listener)", "manager.registerDisplayListener(listener, null)"),
        Sample("THE_SAME_LISTENER_IS_UNREGISTERED", "manager?.unregisterDisplayListener(listener)", "manager?.unregisterDisplayListener(null)"),
        Sample("THE_SAME_LISTENER_IS_UNREGISTERED", "private val listener: DisplayManager.DisplayListener = object", "private val other: DisplayManager.DisplayListener = object"),
        Sample("THE_SAME_LISTENER_IS_UNREGISTERED", "manager?.unregisterDisplayListener(listener)", "manager?.unregisterDisplayListener(listener)\n            manager?.unregisterDisplayListener(listener)"),
        Sample("THE_SNAPSHOT_IS_ROTATION_AND_MODE_SIZE", "Display.DEFAULT_DISPLAY", "0"),
        Sample("THE_SNAPSHOT_IS_ROTATION_AND_MODE_SIZE", "mode.physicalHeight", "mode.physicalWidth"),
        Sample("THE_SNAPSHOT_IS_ROTATION_AND_MODE_SIZE", "val mode = display.mode", "val mode = display.mode\n            val size = display.getMetrics()"),
        Sample("THE_ADAPTER_CATCHES_PLATFORM_FAILURES", "} catch (e: RuntimeException) {", "} catch (e: IllegalStateException) {"),
        Sample("THE_ADAPTER_CATCHES_PLATFORM_FAILURES", "catch (e: RuntimeException) {", "catch (e: Exception) {"),
        Sample("THE_ADAPTER_START_KEEPS_ONE_REGISTRATION", "if (registered) return true", ""),
        Sample("THE_ADAPTER_START_KEEPS_ONE_REGISTRATION", "callback = null\n            false", "callback = null\n            true"),
        Sample("THE_ADAPTER_START_KEEPS_ONE_REGISTRATION", "if (registered) return true\n        return try {\n            main.registerOnMain(manager, listener)\n            registered = true", "return try {\n            main.registerOnMain(manager, listener)\n            registered = true\n            if (registered) return true"),
        Sample("THE_ADAPTER_STOP_DROPS_THE_CALLBACK_AND_THE_FLAG", "override fun stop() {\n        callback = null\n", "override fun stop() {\n"),
        Sample("THE_ADAPTER_STOP_DROPS_THE_CALLBACK_AND_THE_FLAG", "if (!registered) return", "if (registered) return"),
        Sample("THE_ADAPTER_STOP_DROPS_THE_CALLBACK_AND_THE_FLAG", "        registered = false\n", ""),
        Sample("THE_ADAPTER_PASSES_THE_ID_THAT_CHANGED", "callback?.invoke(displayId)", "callback?.invoke(0)"),
        Sample("THE_ADAPTER_PASSES_THE_ID_THAT_CHANGED", "callback?.invoke(displayId)", "callback?.invoke(displayId + 1)"),
    )

    private val quiet: List<Sample> = listOf(
        Sample("THE_WATCH_STARTS_AND_STOPS_BESIDE_THE_GESTURE", "main.post { if (armed) displayWatch.start() else displayWatch.stop() }", "main.post { if (armed) displayWatch.start() else displayWatch.stop() } // starts and stops the listener"),
        Sample("THE_WATCH_STARTS_AND_STOPS_BESIDE_THE_GESTURE", "        toCoordinator { it.onArmedChanged(armed) }", "        // toCoordinator { it.onArmedChanged(armed) }\n        toCoordinator { it.onArmedChanged(armed) }"),
        Sample("THE_WATCH_IS_BUILT_ONCE_WITH_AN_EXPLICIT_TYPE_AND_REACHES_THE_TILE", "private val displayWatch: DisplayWatch", "/* DisplayWatch(DisplayChangeAndroid(context, main)) */ private val displayWatch: DisplayWatch"),
        Sample("THE_PORT_FORWARDS_TO_THE_TILE", "fun onDisplayChanged() {", "// fun onDisplayChanged() { tile?.onDisplayChanged() }\n    fun onDisplayChanged() {"),
        Sample("THE_LISTENER_IS_REGISTERED_ON_THE_MAIN_LOOPER", "Registers [listener] with [manager] so that its callbacks run on the main looper.", "Registers [listener] with [manager]; its callbacks run on the main looper."),
        Sample("THE_ADAPTER_ASKS_THE_MAIN_LOOPER_TO_REGISTER", "main.registerOnMain(manager, listener)", "main.registerOnMain(manager, listener) // not manager.registerDisplayListener"),
        Sample("THE_SAME_LISTENER_IS_UNREGISTERED", "private val listener: DisplayManager.DisplayListener", "// private val listener: DisplayManager.DisplayListener\n    private val listener: DisplayManager.DisplayListener"),
        Sample("THE_SNAPSHOT_IS_ROTATION_AND_MODE_SIZE", "val mode = display.mode", "val mode = display.mode // no refresh rate and no mode id is read"),
        Sample("THE_ADAPTER_CATCHES_PLATFORM_FAILURES", "private var registered: Boolean = false", "// catch (e: RuntimeException)\n    private var registered: Boolean = false"),
        Sample("THE_ADAPTER_START_KEEPS_ONE_REGISTRATION", "if (registered) return true", "if (registered) return true // a second start keeps the one registration"),
        Sample("THE_ADAPTER_STOP_DROPS_THE_CALLBACK_AND_THE_FLAG", "if (!registered) return", "if (!registered) return // nothing was registered"),
        Sample("THE_ADAPTER_PASSES_THE_ID_THAT_CHANGED", "callback?.invoke(displayId)", "callback?.invoke(displayId) // the display that changed"),
    )

    private val listenerCall: String = """\bregisterDisplayListener\s*\("""
    private val otherSource: String = """\b(onConfigurationChanged|ComponentCallbacks\d*|registerComponentCallbacks\d*)\b"""

    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))

    /** The comment-stripped code of [key], with [old] replaced by [new] when given; fails by name when the text is absent. */
    private fun codeOf(key: String, old: String? = null, new: String = ""): String {
        val text = raw(key)
        if (old == null) return AppSourceFiles.strip(text).code
        val at = text.indexOf(old)
        check(at >= 0) { "app: a display rule sample lost its text '$old' in ${files.getValue(key)}" }
        return AppSourceFiles.strip(text.substring(0, at) + new + text.substring(at + old.length)).code
    }

    private fun edited(sample: Sample): String {
        val rule = byName.getValue(sample.rule)
        return codeOf(rule.file, sample.old, sample.new)
    }

    /** The main files, other than host/AppThreads.kt, that call the platform's display listener registration. */
    private fun listenerCallFiles(sources: Map<String, String>): List<String> =
        sources.toSortedMap().filter { (name, text) -> name != "host/AppThreads.kt" && count(AppSourceFiles.strip(text).code, listenerCall) > 0 }.keys.toList()

    /** The main files that name a configuration callback or a component callback in their code. */
    private fun secondSourceFiles(sources: Map<String, String>): List<String> =
        sources.toSortedMap().filter { (_, text) -> count(AppSourceFiles.strip(text).code, otherSource) > 0 }.keys.toList()

    @Test
    fun `every display rule holds on the real files`() {
        for (key in files.keys) assertTrue("app: ${files.getValue(key)} was read as empty", codeOf(key).isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `the firing samples cover each rule at least twice and each rule has a quiet sample`() {
        val names = rules.map { it.name }
        assertEquals("app: a display rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val hits = firing.count { it.rule == rule.name }
            assertTrue("app: display rule ${rule.name} needs at least two firing samples, has $hits", hits >= 2)
            assertTrue("app: display rule ${rule.name} has no quiet sample", quiet.any { it.rule == rule.name })
        }
    }

    @Test
    fun `every display rule fires on each of its edited samples`() {
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: display rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(edited(sample)))
        }
    }

    @Test
    fun `no display rule fires on a harmless edit`() {
        for (sample in quiet) {
            val rule = byName.getValue(sample.rule)
            val code = edited(sample)
            for (other in rules.filter { it.file == rule.file }) {
                assertTrue("app: display rule ${other.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", other.holds(code))
            }
        }
    }

    @Test
    fun `the display listener is registered in the app thread file only`() {
        val real = AppSourceFiles.mainKotlinSources()
        assertTrue("app: the display rules did not find host/AppThreads.kt", "host/AppThreads.kt" in real)
        assertEquals("app: host/AppThreads.kt must register the display listener once", 1, count(AppSourceFiles.strip(real.getValue("host/AppThreads.kt")).code, listenerCall))
        assertEquals("app: a main file other than host/AppThreads.kt registers a display listener", emptyList<String>(), listenerCallFiles(real))
        assertEquals("app: a registration in another file was not reported", listOf("wiring/Other.kt"), listenerCallFiles(real + ("wiring/Other.kt" to "class A { fun f() { m.registerDisplayListener(l, h) } }")))
    }

    @Test
    fun `no main file names a second display-change source`() {
        val real = AppSourceFiles.mainKotlinSources()
        assertEquals("app: a main file names a second display-change source", emptyList<String>(), secondSourceFiles(real))
        for (text in listOf("override fun onConfigurationChanged(c: Configuration) {}", "class A : ComponentCallbacks", "fun f(c: Context) { c.registerComponentCallbacks(l) }", "val kind = android.content.ComponentCallbacks2::class")) {
            assertEquals("app: the second source was not reported for $text", listOf("Other.kt"), secondSourceFiles(mapOf("Other.kt" to "class A { $text }")))
        }
        assertEquals("app: a comment that names a second source is not a use", emptyList<String>(), secondSourceFiles(mapOf("Other.kt" to "class A { // android.content.ComponentCallbacks2 is named in a comment only\n}")))
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            codeOf("host", "no such text in the host", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text in the host"))
    }
}
