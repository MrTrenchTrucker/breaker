package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release build holds none of the keyboard signal probe.
 *
 * The probe lives in the debug source set only: its activity, its start provider, its
 * selector and its service config overlay. The main sources and the main manifest are read
 * as text, and the debug config is read as XML with the same parser and the same exact allow-list.
 * The debug manifest rules live in ProbeDebugManifestGateTest and the shortcut rules in
 * ProbeShortcutGateTest. No main source of the app or of any module
 * calls setEnabled on the probe holder; that rule reads text, so a call through reflection or a
 * second alias of an alias is not seen. Each rule holds on the real files and
 * refuses the plant samples written beside it; a sample whose target text is missing fails
 * by name.
 */
internal class ProbeReleaseGateTest {

    private val probeNames: List<String> = listOf(
        "KeyboardProbeActivity",
        "ProbeStartProvider",
        "KeyboardSignalSetting",
        "ProbeSettingStore",
        "breaker_probe",
        "dev.breaker.dictation.probe",
    )

    private val mainManifest: String = AppSourceFiles.mainFile("AndroidManifest.xml")

    private val debugConfigPath: String = "src/debug/res/xml/commit_accessibility_service_config.xml"

    private val debugConfig: XmlExpect = XmlExpect(
        name = "accessibility-service",
        attributes = mapOf(
            "xmlns:android" to XmlTree.ANDROID_NAMESPACE,
            "android:accessibilityEventTypes" to "typeViewFocused|typeWindowsChanged|typeWindowStateChanged",
            "android:accessibilityFeedbackType" to "feedbackGeneric",
            "android:accessibilityFlags" to "flagRetrieveInteractiveWindows",
            "android:canRetrieveWindowContent" to "true",
            "android:description" to "@string/commit_accessibility_service_description",
        ),
    )

    /** One line per element of [xml] whose name or authorities names the probe. */
    private fun probeElements(xml: String): List<String> {
        val found: MutableList<String> = ArrayList()
        collectProbeElements(XmlTree.parse(xml), found)
        return found
    }

    private fun collectProbeElements(node: XmlNode, found: MutableList<String>) {
        for ((key, value) in node.attributes) {
            val named: Boolean = key == "android:name" || key == "android:authorities"
            if (named && value.contains("probe", ignoreCase = true)) {
                found.add("<${node.name} $key=\"$value\">")
            }
        }
        for (child in node.children) {
            collectProbeElements(child, found)
        }
    }

    /** One line per file of [files] that names a probe package, element, selector or preference name. */
    private fun probeNameHits(files: Map<String, String>): List<String> {
        val found: MutableList<String> = ArrayList()
        for ((name, text) in files.toSortedMap()) {
            for (token in probeNames) {
                if (text.contains(token)) {
                    found.add("$name names $token")
                }
            }
        }
        return found
    }

    /** The names of the files of [files] that hold an accessibility service config. */
    private fun serviceConfigHits(files: Map<String, String>): List<String> {
        val found: MutableList<String> = ArrayList()
        for ((name, text) in files.toSortedMap()) {
            if (name.endsWith("accessibility_service_config.xml") || text.contains("<accessibility-service")) {
                found.add(name)
            }
        }
        return found
    }

    /** The text of a file of the app module that is not in src/main; fails by name when it is missing. */
    private fun debugText(path: String): String {
        val file = File(AppSourceFiles.moduleRoot, path)
        check(file.isFile) { "app: the debug file $path is missing" }
        return file.readText(Charsets.UTF_8)
    }

    private val setEnabledCall: Regex = Regex("\\bsetEnabled\\s*\\(")
    private val funBefore: Regex = Regex("\\bfun\\s+\$")

    /** The repository root: the nearest folder at or above the app module that holds the root settings script. */
    private fun repoRoot(): File =
        generateSequence(AppSourceFiles.moduleRoot) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("app: no folder with settings.gradle.kts at or above ${AppSourceFiles.moduleRoot}")

    /** The text of every Kotlin file in the main source set of the app and of each module, by path below the repository root. */
    private fun releaseSources(): Map<String, String> {
        val repo: File = repoRoot()
        val found: MutableMap<String, String> = LinkedHashMap()
        for (root in listOf("android/app/src/main", "android/modules")) {
            val start = File(repo, root)
            check(start.isDirectory) { "app: no source folder at $start" }
            val files = start.walkTopDown().onEnter { it.name != "build" }.filter { it.isFile && it.extension == "kt" }
            for (file in files.sortedBy { it.path }) {
                val path: String = file.relativeTo(repo).path.replace(File.separatorChar, '/')
                if (path.contains("/src/main/")) {
                    found[path] = file.readText(Charsets.UTF_8)
                }
            }
        }
        return found
    }

    /** The paths of [files] whose code names the probe holder and has a setEnabled call that is not a declaration; a file whose raw text lacks the name is not lexed. */
    private fun setEnabledHits(files: Map<String, String>): List<String> {
        val found: MutableList<String> = ArrayList()
        for ((path, text) in files.toSortedMap().filterValues { it.contains("KeyboardProbeReadings") }) {
            val code: String = AppSourceFiles.strip(text).code
            val calls = setEnabledCall.findAll(code).filter { !funBefore.containsMatchIn(code.substring(0, it.range.first).takeLast(40)) }
            if (code.contains("KeyboardProbeReadings") && calls.any()) {
                found.add(path)
            }
        }
        return found
    }

    @Test
    fun `the release manifest names no probe activity and no probe provider`() {
        assertEquals("app: the main manifest names a probe element", emptyList<String>(), probeElements(mainManifest))
        val activity = XmlSamples.apply(
            "manifest",
            mainManifest,
            XmlSample("a probe activity", "</application>", "<activity android:name=\".probe.KeyboardProbeActivity\" android:exported=\"true\" />\n    </application>"),
        )
        val provider = XmlSamples.apply(
            "manifest",
            mainManifest,
            XmlSample("a probe provider", "</application>", "<provider android:name=\".probe.ProbeStartProvider\" android:authorities=\"x.probe\" android:exported=\"false\" />\n    </application>"),
        )
        assertTrue("app: the release rule accepted a probe activity", probeElements(activity).isNotEmpty())
        assertTrue("app: the release rule accepted a probe provider", probeElements(provider).isNotEmpty())
    }

    @Test
    fun `the release sources name no probe package and no selector`() {
        val main: Map<String, String> = AppSourceFiles.mainTextFiles()
        assertTrue("app: no main source was read", main.isNotEmpty())
        assertEquals("app: a main file names a probe package or selector", emptyList<String>(), probeNameHits(main))
        val packageLine = mapOf("host/PlantFile.kt" to "import dev.breaker.dictation.probe.KeyboardSignalSetting")
        val selector = mapOf("wiring/PlantFile.kt" to "class A { val s = KeyboardSignalSetting.OFF }")
        assertTrue("app: a main source with the probe package line was accepted", probeNameHits(packageLine).isNotEmpty())
        assertTrue("app: a main source naming the selector was accepted", probeNameHits(selector).isNotEmpty())
    }

    @Test
    fun `the release resources hold no service config`() {
        val main: Map<String, String> = AppSourceFiles.mainTextFiles()
        assertEquals("app: a main file holds an accessibility service config", emptyList<String>(), serviceConfigHits(main))
        val plant = mapOf("res/xml/commit_accessibility_service_config.xml" to "<accessibility-service android:description=\"x\" />")
        assertTrue("app: a service config under src/main was accepted", serviceConfigHits(plant).isNotEmpty())
    }

    @Test
    fun `the debug service config holds the baseline attributes plus the flag and the two window events and nothing more`() {
        val text: String = debugText(debugConfigPath)
        assertEquals("app: the debug service config is not the allowed one", emptyList<String>(), XmlAllowList.problems(XmlTree.parse(text), debugConfig))
        val plants = listOf(
            XmlSample("an extra capability attribute", "android:canRetrieveWindowContent=\"true\"", "android:canRetrieveWindowContent=\"true\"\n    android:canPerformGestures=\"true\""),
            XmlSample("a dropped window event", "|typeWindowsChanged", ""),
            XmlSample("a dropped window state event", "|typeWindowStateChanged", ""),
            XmlSample("a dropped flag", "\n    android:accessibilityFlags=\"flagRetrieveInteractiveWindows\"", ""),
        )
        for (sample in plants) {
            val changed: String = XmlSamples.apply("config", text, sample)
            assertTrue("app: the debug service config accepted ${sample.label}", XmlAllowList.problems(XmlTree.parse(changed), debugConfig).isNotEmpty())
        }
    }

    @Test
    fun `the debug service config keeps the description string of the baseline`() {
        val debug: String = XmlTree.parse(debugText(debugConfigPath)).attributes["android:description"] ?: ""
        val baselineFile = File(AppSourceFiles.moduleRoot, "../modules/commit/accessibility/src/main/res/xml/commit_accessibility_service_config.xml")
        check(baselineFile.isFile) { "app: the baseline service config is missing" }
        val baseline: String = XmlTree.parse(baselineFile.readText(Charsets.UTF_8)).attributes["android:description"] ?: ""
        assertEquals("app: the debug description differs from the baseline", baseline, debug)
        assertEquals("app: the description is not the baseline string", "@string/commit_accessibility_service_description", debug)
        val changed: String = XmlSamples.apply(
            "config",
            debugText(debugConfigPath),
            XmlSample("another description", "@string/commit_accessibility_service_description", "@string/other_description"),
        )
        assertTrue("app: a changed description was accepted", XmlTree.parse(changed).attributes["android:description"] != baseline)
    }

    @Test
    fun `the release sources never call setEnabled outside the debug source set`() {
        val sources: Map<String, String> = releaseSources()
        val required = listOf(
            "android/modules/commit/src/main/kotlin/dev/breaker/dictation/commit/KeyboardProbeReadings.kt",
            "android/modules/commit/accessibility/src/main/kotlin/dev/breaker/dictation/commit/accessibility/adapter/BreakerAccessibilityService.kt",
        )
        for (path in required) {
            assertTrue("app: the scan did not read $path", sources.containsKey(path))
        }
        assertTrue("app: the scan read no overlay main file", sources.keys.any { it.startsWith("android/modules/overlay/src/main/") })
        assertTrue("app: the scan read no app main file", sources.keys.any { it.startsWith("android/app/src/main/") })
        val hits: List<String> = setEnabledHits(sources)
        assertTrue("app: these release sources call setEnabled on the probe holder: $hits", hits.isEmpty())
    }

    @Test
    fun `the setEnabled rule refuses a call from any module and stays quiet on the other uses`() {
        val path = "android/modules/overlay/src/main/kotlin/dev/breaker/dictation/overlay/Plant.kt"
        val alias = "import dev.breaker.dictation.commit.KeyboardProbeReadings as Probe"
        val refused = mapOf(
            "a qualified call" to "class A { fun f() { KeyboardProbeReadings.setEnabled(true) } }",
            "a call with a space before the paren" to "class A { fun f() { KeyboardProbeReadings.setEnabled (true) } }",
            "a bare call in a with block" to "class A { fun f() {\n    with(KeyboardProbeReadings) {\n        setEnabled(true)\n    }\n} }",
            "a call through an import alias" to "$alias\nclass A { fun f() { Probe.setEnabled(true) } }",
        )
        val quiet = mapOf(
            "the holder declaration" to "object KeyboardProbeReadings {\n    fun setEnabled(on: Boolean) {}\n    fun isEnabled(): Boolean = true\n}",
            "a view call in a file that does not name the holder" to "class B { fun f(view: View) { view.setEnabled(false) } }",
            "a comment and a string" to "// KeyboardProbeReadings.setEnabled(true)\nclass C { val s = \"KeyboardProbeReadings.setEnabled(true)\" }",
            "a read of isEnabled only" to "class D { fun f(): Boolean = KeyboardProbeReadings.isEnabled() }",
        )
        for ((label, text) in refused) {
            assertEquals("app: the setEnabled rule missed $label", listOf(path), setEnabledHits(mapOf(path to text)))
        }
        for ((label, text) in quiet) {
            assertEquals("app: the setEnabled rule refused $label", emptyList<String>(), setEnabledHits(mapOf(path to text)))
        }
    }
}
