package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher shortcut for the probe page: the release manifest has one launcher filter and no
 * shortcut hook, the debug manifest has no launcher filter and one hook on the launcher activity,
 * and the shortcut file opens the probe activity with string labels. Each rule holds on the real
 * files and refuses the plant samples written beside it.
 */
internal class ProbeShortcutGateTest {

    private val launcher: String = "android.intent.category.LAUNCHER"
    private val mainAction: String = "android.intent.action.MAIN"
    private val hookName: String = "android.app.shortcuts"
    private val hookResource: String = "@xml/probe_shortcuts"
    private val probeClass: String = "dev.breaker.dictation.probe.KeyboardProbeActivity"

    private fun moduleText(path: String): String {
        val file = File(AppSourceFiles.moduleRoot, path)
        check(file.isFile) { "app: the file $path is missing" }
        return file.readText(Charsets.UTF_8)
    }

    /** Every element of [node] and of its children with the given [name], in document order. */
    private fun named(node: XmlNode, name: String): List<XmlNode> =
        listOf(node).filter { it.name == name } + node.children.flatMap { named(it, name) }

    /** One problem per way the release manifest has other than one launcher category and no shortcuts hook. */
    private fun releaseProblems(manifest: String): List<String> {
        val root: XmlNode = XmlTree.parse(manifest)
        val found: MutableList<String> = ArrayList()
        val launchers: Int = named(root, "category").count { it.attributes["android:name"] == launcher }
        if (launchers != 1) {
            found.add("app: the release manifest has $launchers launcher categories, expected one")
        }
        val hooks: Int = named(root, "meta-data").count { it.attributes["android:name"] == hookName }
        if (hooks != 0) {
            found.add("app: the release manifest has $hooks shortcuts hooks, expected none")
        }
        return found
    }

    /** The names of the files of [resources] under res/xml that hold a shortcuts element. */
    private fun shortcutFileHits(resources: Map<String, String>): List<String> =
        resources.filterKeys { it.startsWith("res/xml/") }.filterValues { it.contains("<shortcuts") }.keys.sorted()

    /** One problem per way the debug manifest has other than no launcher filter and one hook on the launcher activity. */
    private fun debugProblems(debug: String): List<String> {
        val root: XmlNode = XmlTree.parse(debug)
        val found: MutableList<String> = ArrayList()
        if (named(root, "category").any { it.attributes["android:name"] == launcher }) {
            found.add("app: the debug manifest holds a launcher category")
        }
        if (named(root, "action").any { it.attributes["android:name"] == mainAction }) {
            found.add("app: the debug manifest holds a MAIN action")
        }
        val hooks: List<XmlNode> = named(root, "meta-data").filter { it.attributes["android:name"] == hookName }
        val onLauncher: List<XmlNode> = named(root, "activity")
            .filter { it.attributes["android:name"] == ".SettingsLauncherActivity" }
            .flatMap { it.children }
            .filter { it.name == "meta-data" && it.attributes["android:name"] == hookName && it.attributes["android:resource"] == hookResource }
        if (hooks.size != 1 || onLauncher.size != 1) {
            found.add("app: the debug manifest does not hold one shortcuts hook on the launcher activity")
        }
        return found
    }

    /** The applicationId of the app module, read from its assignment line in the build script. */
    private fun applicationIdOf(script: String): String =
        Regex("applicationId = \"(.*)\"").find(script)?.groupValues?.get(1) ?: error("app: the build script has no applicationId line")

    /** One problem per way the shortcut file has other than one entry that opens the probe activity with string labels. */
    private fun shortcutProblems(shortcutXml: String, valuesXml: String, applicationId: String): List<String> {
        val root: XmlNode = XmlTree.parse(shortcutXml)
        val strings: Set<String> = named(XmlTree.parse(valuesXml), "string").mapNotNull { it.attributes["name"] }.toSet()
        val found: MutableList<String> = ArrayList()
        val shortcuts: List<XmlNode> = named(root, "shortcut")
        val intents: List<XmlNode> = shortcuts.flatMap { it.children }.filter { it.name == "intent" }
        if (shortcuts.size != 1 || intents.size != 1) {
            found.add("app: the probe shortcut file is not one shortcut with one intent")
            return found
        }
        for (label in listOf("android:shortcutShortLabel", "android:shortcutLongLabel")) {
            val value: String = shortcuts.single().attributes[label] ?: ""
            if (!value.startsWith("@string/") || value.removePrefix("@string/") !in strings) {
                found.add("app: the probe shortcut $label is not a string resource of the debug values folder")
            }
        }
        val intent: XmlNode = intents.single()
        val wanted: Map<String, String> = mapOf(
            "android:action" to mainAction,
            "android:targetClass" to probeClass,
            "android:targetPackage" to applicationId,
        )
        for ((key, want) in wanted) {
            if (intent.attributes[key] != want) {
                found.add("app: the probe shortcut intent $key is \"${intent.attributes[key]}\", expected \"$want\"")
            }
        }
        return found
    }

    @Test
    fun `the release manifest has one launcher and no shortcuts hook and the release resources hold no shortcuts file`() {
        val manifest: String = AppSourceFiles.mainFile("AndroidManifest.xml")
        assertEquals("app: the release manifest is not one launcher without a shortcuts hook", emptyList<String>(), releaseProblems(manifest))
        assertEquals("app: a release resource file holds a shortcuts element", emptyList<String>(), shortcutFileHits(AppSourceFiles.mainTextFiles()))
        val second = XmlSamples.apply("manifest", manifest, XmlSample("a second launcher category", "<category android:name=\"android.intent.category.LAUNCHER\" />", "<category android:name=\"android.intent.category.LAUNCHER\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />"))
        val hook = XmlSamples.apply("manifest", manifest, XmlSample("the shortcuts hook", "android:label=\"@string/app_name\">", "android:label=\"@string/app_name\">\n            <meta-data android:name=\"android.app.shortcuts\" android:resource=\"@xml/probe_shortcuts\" />"))
        val file = mapOf("res/xml/probe_shortcuts.xml" to "<shortcuts xmlns:android=\"http://schemas.android.com/apk/res/android\" />")
        assertTrue("app: a second launcher category in the release manifest was accepted", releaseProblems(second).isNotEmpty())
        assertTrue("app: a shortcuts hook in the release manifest was accepted", releaseProblems(hook).isNotEmpty())
        assertTrue("app: a shortcuts file under src/main was accepted", shortcutFileHits(file).isNotEmpty())
    }

    @Test
    fun `the debug manifest has no launcher filter and one shortcuts hook on the launcher activity`() {
        val debug: String = moduleText("src/debug/AndroidManifest.xml")
        assertEquals("app: the debug manifest has a launcher filter or not one shortcuts hook on the launcher activity", emptyList<String>(), debugProblems(debug))
        val filter = XmlSamples.apply("manifest", debug, XmlSample("the launcher filter on the probe activity", "android:theme=\"@android:style/Theme.DeviceDefault.NoActionBar\" />", "android:theme=\"@android:style/Theme.DeviceDefault.NoActionBar\"><intent-filter><action android:name=\"android.intent.action.MAIN\" /><category android:name=\"android.intent.category.LAUNCHER\" /></intent-filter></activity>"))
        val removed = XmlSamples.apply("manifest", debug, XmlSample("the shortcuts hook removed", "<meta-data\n                android:name=\"android.app.shortcuts\"\n                android:resource=\"@xml/probe_shortcuts\" />", ""))
        val second = XmlSamples.apply("manifest", debug, XmlSample("a second shortcuts hook", "android:resource=\"@xml/probe_shortcuts\" />", "android:resource=\"@xml/probe_shortcuts\" />\n            <meta-data android:name=\"android.app.shortcuts\" android:resource=\"@xml/probe_shortcuts\" />"))
        assertTrue("app: the debug manifest accepted a launcher filter on the probe activity", debugProblems(filter).isNotEmpty())
        assertTrue("app: the debug manifest accepted a removed shortcuts hook", debugProblems(removed).isNotEmpty())
        assertTrue("app: the debug manifest accepted a second shortcuts hook", debugProblems(second).isNotEmpty())
    }

    @Test
    fun `the probe shortcut has one entry that opens the probe activity with string labels`() {
        val shortcut: String = moduleText("src/debug/res/xml/probe_shortcuts.xml")
        val values: String = moduleText("src/debug/res/values/probe_strings.xml")
        val appId: String = applicationIdOf(moduleText("build.gradle.kts"))
        assertEquals("app: the probe shortcut is not one entry that opens the probe activity", emptyList<String>(), shortcutProblems(shortcut, values, appId))
        val literal = XmlSamples.apply("shortcut", shortcut, XmlSample("a literal short label", "android:shortcutShortLabel=\"@string/probe_shortcut_short_label\"", "android:shortcutShortLabel=\"Breaker probe\""))
        val wrongClass = XmlSamples.apply("shortcut", shortcut, XmlSample("a wrong class", "android:targetClass=\"dev.breaker.dictation.probe.KeyboardProbeActivity\"", "android:targetClass=\"dev.breaker.dictation.probe.Other\""))
        val wrongPackage = XmlSamples.apply("shortcut", shortcut, XmlSample("a wrong package", "android:targetPackage=\"dev.breaker.dictation\"", "android:targetPackage=\"dev.breaker.dictation.debug\""))
        assertTrue("app: a literal shortcut label was accepted", shortcutProblems(literal, values, appId).isNotEmpty())
        assertTrue("app: a wrong target class was accepted", shortcutProblems(wrongClass, values, appId).isNotEmpty())
        assertTrue("app: a wrong target package was accepted", shortcutProblems(wrongPackage, values, appId).isNotEmpty())
    }
}
