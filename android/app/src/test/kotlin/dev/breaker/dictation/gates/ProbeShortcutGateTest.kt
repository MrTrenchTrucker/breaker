package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher filter rules of the app: the release manifest has one launcher filter and no shortcut
 * hook, and the debug manifest has no launcher filter and no MAIN action. Each rule holds on the real
 * files and refuses the plant samples written beside it.
 */
internal class ProbeShortcutGateTest {

    private val launcher: String = "android.intent.category.LAUNCHER"
    private val mainAction: String = "android.intent.action.MAIN"
    private val hookName: String = "android.app.shortcuts"

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

    /** One problem per launcher category or MAIN action in the debug manifest. */
    private fun debugProblems(debug: String): List<String> {
        val root: XmlNode = XmlTree.parse(debug)
        val found: MutableList<String> = ArrayList()
        if (named(root, "category").any { it.attributes["android:name"] == launcher }) {
            found.add("app: the debug manifest holds a launcher category")
        }
        if (named(root, "action").any { it.attributes["android:name"] == mainAction }) {
            found.add("app: the debug manifest holds a MAIN action")
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
    fun `the debug manifest has no launcher filter`() {
        val debug: String = moduleText("src/debug/AndroidManifest.xml")
        assertEquals("app: the debug manifest has a launcher category or a MAIN action", emptyList<String>(), debugProblems(debug))
        val filter = XmlSamples.apply(
            "manifest",
            debug,
            XmlSample(
                "the launcher filter on the mic receiver",
                "<action android:name=\"dev.breaker.debug.MIC\" />",
                "<action android:name=\"dev.breaker.debug.MIC\" />\n                <action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />",
            ),
        )
        assertTrue("app: the debug manifest accepted a launcher filter on the mic receiver", debugProblems(filter).isNotEmpty())
    }
}
