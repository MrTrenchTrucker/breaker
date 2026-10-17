package dev.breaker.dictation.commit.accessibility

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest declares the one accessibility service and nothing else.
 *
 * Adding a permission, a second service, a different export or permission setting, or
 * any attribute or element beyond the allow-list is a change to what this module may
 * do on the phone, so it fails here until it is argued for and the list is changed on
 * purpose. The file is parsed as XML, not searched as text: comments are not content,
 * attribute order does not matter, and nothing outside the allow-list passes.
 */
internal class ManifestGateTest {

    private val labelName: String = "commit_accessibility_service_label"
    private val configName: String = "commit_accessibility_service_config"

    private val metaData: String =
        """<meta-data android:name="android.accessibilityservice" android:resource="@xml/commit_accessibility_service_config" />"""

    private val good: String = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <service
            android:name=".adapter.BreakerAccessibilityService"
            android:exported="true"
            android:label="@string/commit_accessibility_service_label"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            $metaData
        </service>
    </application>
</manifest>
"""

    private fun allowed(): XmlExpect = XmlExpect(
        name = "manifest",
        attributes = mapOf("xmlns:android" to XmlTree.ANDROID_NAMESPACE),
        children = listOf(
            XmlExpect(
                name = "application",
                attributes = emptyMap(),
                children = listOf(
                    XmlExpect(
                        name = "service",
                        attributes = mapOf(
                            "android:name" to ".adapter.BreakerAccessibilityService",
                            "android:exported" to "true",
                            "android:label" to "@string/$labelName",
                            "android:permission" to "android.permission.BIND_ACCESSIBILITY_SERVICE",
                        ),
                        children = listOf(
                            XmlExpect(
                                name = "intent-filter",
                                attributes = emptyMap(),
                                children = listOf(
                                    XmlExpect("action", mapOf("android:name" to "android.accessibilityservice.AccessibilityService")),
                                ),
                            ),
                            XmlExpect("meta-data", mapOf("android:name" to "android.accessibilityservice", "android:resource" to "@xml/$configName")),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** One line per difference between [xml] and the allow-list; none means it matches exactly. Throws on text that is not XML. */
    private fun manifestProblems(xml: String): List<String> = XmlAllowList.problems(XmlTree.parse(xml), allowed())

    /** [source] with every [from] replaced; fails when [from] is not there, so a sample cannot go quiet by a typo. */
    private fun edit(source: String, from: String, to: String): String {
        assertTrue("commit/accessibility: the manifest sample has no text \"$from\" to change", source.contains(from))
        return source.replace(from, to)
    }

    private fun assertRejected(label: String, xml: String, fragment: String) {
        val problems: List<String> = manifestProblems(xml)
        assertTrue("commit/accessibility: the manifest gate accepted $label", problems.isNotEmpty())
        assertTrue(
            "commit/accessibility: the manifest gate did not say \"$fragment\" for $label, it said $problems",
            problems.any { it.contains(fragment) },
        )
    }

    @Test
    fun `the real manifest declares exactly the one accessibility service`() {
        val text: String? = SourceFiles.mainFileOrNull("AndroidManifest.xml")
        assertTrue("commit/accessibility: src/main/AndroidManifest.xml is missing", text != null)
        assertTrue("commit/accessibility: src/main/AndroidManifest.xml is empty", !text.isNullOrBlank())
        val problems: List<String> = manifestProblems(text ?: "")
        assertEquals("commit/accessibility: the manifest differs from its allow-list: $problems", emptyList<String>(), problems)
    }

    @Test
    fun `the manifest points at a service label and a service config this module holds`() {
        val strings: String? = SourceFiles.mainFileOrNull("res/values/strings.xml")
        assertTrue("commit/accessibility: src/main/res/values/strings.xml is missing", strings != null)
        assertTrue(
            "commit/accessibility: strings.xml defines no string named $labelName",
            labelName in XmlTree.stringNames(strings ?: "<resources/>"),
        )
        assertTrue(
            "commit/accessibility: res/xml/$configName.xml is missing",
            SourceFiles.mainFileOrNull("res/xml/$configName.xml") != null,
        )
    }

    @Test
    fun `the exact good manifest passes, with comments that name forbidden things, and in another attribute order`() {
        assertEquals("commit/accessibility: the exact good manifest was rejected", emptyList<String>(), manifestProblems(good))
        val commented: String = edit(
            good,
            "<application>",
            "<!-- uses-permission android.permission.INTERNET android:exported=\"false\" <service android:name=\".X\" /> -->\n    <application>",
        )
        assertEquals("commit/accessibility: a comment that names forbidden things was read as content", emptyList<String>(), manifestProblems(commented))
        val reordered: String = edit(
            good,
            "android:name=\".adapter.BreakerAccessibilityService\"\n            android:exported=\"true\"",
            "android:exported=\"true\"\n            android:name=\".adapter.BreakerAccessibilityService\"",
        )
        assertEquals("commit/accessibility: attribute order changed the verdict", emptyList<String>(), manifestProblems(reordered))
    }

    @Test
    fun `a manifest with more than the allow-list is rejected with what is wrong`() {
        val internet = "<uses-permission android:name=\"android.permission.INTERNET\" />\n    <application>"
        assertRejected("an internet permission", edit(good, "<application>", internet), "holds the element <uses-permission>")
        assertRejected("a uses-feature element", edit(good, "<application>", "<uses-feature android:name=\"x\" />\n    <application>"), "holds the element <uses-feature>")
        assertRejected("a second service", edit(good, "</application>", "<service android:name=\".Other\" />\n    </application>"), "holds the element <service>")
        assertRejected("a receiver", edit(good, "</application>", "<receiver android:name=\".R\" />\n    </application>"), "holds the element <receiver>")
        assertRejected("a second meta-data", edit(good, metaData, metaData + "\n            " + metaData), "holds the element <meta-data>")
        val twoActions = "<action android:name=\"android.accessibilityservice.AccessibilityService\" />\n                <action android:name=\"x\" />"
        assertRejected(
            "a second action",
            edit(good, "<action android:name=\"android.accessibilityservice.AccessibilityService\" />", twoActions),
            "holds the element <action>",
        )
        assertRejected("an attribute on the service", edit(good, "android:exported=\"true\"", "android:exported=\"true\" android:enabled=\"true\""), "has the attribute android:enabled")
        assertRejected("an attribute on the application", edit(good, "<application>", "<application android:allowBackup=\"true\">"), "has the attribute android:allowBackup")
        val extraNamespace = "xmlns:android=\"http://schemas.android.com/apk/res/android\" xmlns:tools=\"http://schemas.android.com/tools\""
        assertRejected("a second namespace declaration", edit(good, "xmlns:android=\"http://schemas.android.com/apk/res/android\"", extraNamespace), "has the attribute xmlns:tools")
        assertRejected("character data in the application", edit(good, "<application>", "<application>text"), "holds character data")
    }

    @Test
    fun `a manifest with other values or missing parts is rejected with what is wrong`() {
        assertRejected("an export switched off", edit(good, "android:exported=\"true\"", "android:exported=\"false\""), "attribute android:exported is \"false\"")
        assertRejected("another permission", edit(good, "android.permission.BIND_ACCESSIBILITY_SERVICE", "android.permission.OTHER"), "attribute android:permission")
        assertRejected(
            "another service class",
            edit(good, ".adapter.BreakerAccessibilityService", ".adapter.OtherService"),
            "attribute android:name is \".adapter.OtherService\"",
        )
        assertRejected("a literal label", edit(good, "@string/commit_accessibility_service_label", "Breaker"), "attribute android:label")
        assertRejected("another config resource", edit(good, "@xml/commit_accessibility_service_config", "@xml/other"), "attribute android:resource")
        assertRejected("another namespace", edit(good, "http://schemas.android.com/apk/res/android", "http://example.invalid/ns"), "attribute xmlns:android")
        assertRejected("no meta-data", edit(good, metaData, ""), "lacks the element <meta-data>")
        assertRejected("no permission attribute", edit(good, "\n            android:permission=\"android.permission.BIND_ACCESSIBILITY_SERVICE\">", ">"), "lacks the attribute android:permission")
        assertRejected("no application", "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"></manifest>", "lacks the element <application>")
        assertRejected("another root element", "<resources />", "is <resources>, expected <manifest>")
    }

    @Test
    fun `a service class outside the adapter package is rejected, the unqualified name and a full name too`() {
        val named = "android:name=\".adapter.BreakerAccessibilityService\""
        val unqualifiedName: String = edit(good, named, "android:name=\".BreakerAccessibilityService\"")
        assertRejected("the service named outside the adapter package", unqualifiedName, "attribute android:name is \".BreakerAccessibilityService\"")
        val other: String = edit(good, named, "android:name=\".Other\"")
        assertRejected("a class name outside the adapter package", other, "attribute android:name is \".Other\"")
        val full: String = edit(
            good,
            named,
            "android:name=\"dev.breaker.dictation.commit.accessibility.adapter.BreakerAccessibilityService\"",
        )
        assertRejected("a fully written class name", full, "attribute android:name is \"dev.breaker.dictation.commit.accessibility.adapter.BreakerAccessibilityService\"")
    }

    @Test
    fun `text that is not safe or not well formed XML is refused, not passed`() {
        val broken: List<String> = listOf(
            "",
            "   \n",
            "not xml",
            "<manifest>",
            "<manifest xmlns:android=\"x\" xmlns:android=\"x\" />",
            "<?xml version=\"1.0\"?><!DOCTYPE manifest [<!ENTITY x \"y\">]><manifest />",
        )
        for (xml in broken) {
            assertThrows(
                "commit/accessibility: the manifest gate passed text that is not safe XML: $xml",
                IllegalStateException::class.java,
            ) { manifestProblems(xml) }
        }
    }

    @Test
    fun `a missing file is reported as missing and the string names are read from a strings file`() {
        assertNull("commit/accessibility: a file that does not exist was read as text", SourceFiles.mainFileOrNull("res/no/such/file.xml"))
        val strings: String = "<resources>\n    <string name=\"one\">A</string>\n    <string name=\"two\">B</string>\n    <color name=\"c\">#fff</color>\n</resources>"
        assertEquals("commit/accessibility: the string names were misread", setOf("one", "two"), XmlTree.stringNames(strings))
    }

    private val allowedDependencies: String = "implementation(project(\":android:modules:commit\"))testImplementation(libs.junit)"

    private val goodScript: String = """plugins { alias(libs.plugins.android.library) }

dependencies {
    implementation(project(":android:modules:commit"))

    testImplementation(libs.junit)
}
"""

    /** [goodScript] with [from] replaced; fails when [from] is not there, so a sample cannot go quiet by a typo. */
    private fun scriptEdit(from: String, to: String): String {
        assertTrue("commit/accessibility: the build script sample has no text \"$from\" to change", goodScript.contains(from))
        return goodScript.replace(from, to)
    }

    private val commentRule: Regex = Regex("""//[^\n]*|/\*[\s\S]*?\*/""")

    /** The body of every `dependencies { }` block of [script], without comments and white space. */
    private fun dependencyBlocks(script: String): List<String> {
        val text: String = commentRule.replace(script, " ")
        val blocks: MutableList<String> = ArrayList()
        for (opening in Regex("""\bdependencies\s*\{""").findAll(text)) {
            var depth = 1
            var end: Int = opening.range.last + 1
            while (end < text.length && depth > 0) {
                if (text[end] == '{') depth += 1
                if (text[end] == '}') depth -= 1
                end += 1
            }
            check(depth == 0) { "commit/accessibility: a dependencies block in the build script is never closed" }
            blocks.add(text.substring(opening.range.last + 1, end - 1).filter { !it.isWhitespace() })
        }
        return blocks
    }

    @Test
    fun `the build script depends only on the parent module and the unit test library`() {
        val script = File(SourceFiles.moduleRoot, "build.gradle.kts")
        assertTrue("commit/accessibility: build.gradle.kts is missing", script.isFile)
        val blocks: List<String> = dependencyBlocks(script.readText())
        assertEquals(
            "commit/accessibility: the build script depends on more or less than the parent module and the unit test library, found $blocks",
            listOf(allowedDependencies),
            blocks,
        )
    }

    @Test
    fun `a dependencies block with more, less or other entries is told from the allowed one`() {
        assertEquals("commit/accessibility: the good build script was not read as allowed", listOf(allowedDependencies), dependencyBlocks(goodScript))
        val firing: Map<String, String> = mapOf(
            "a library from the catalog" to scriptEdit("testImplementation(libs.junit)", "implementation(libs.okhttp)\n    testImplementation(libs.junit)"),
            "a library by its coordinates" to scriptEdit("testImplementation(libs.junit)", "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")\n    testImplementation(libs.junit)"),
            "an address with two slashes" to scriptEdit("testImplementation(libs.junit)", "implementation(\"a//b\")\n    testImplementation(libs.junit)"),
            "no unit test library" to scriptEdit("    testImplementation(libs.junit)\n", ""),
            "another project" to scriptEdit(":android:modules:commit", ":android:modules:core"),
            "a second block" to goodScript + "dependencies { implementation(libs.okhttp) }\n",
            "no block" to "plugins { }",
        )
        for ((label, script) in firing) {
            assertNotEquals("commit/accessibility: the dependency check accepted $label", listOf(allowedDependencies), dependencyBlocks(script))
        }
    }

    @Test
    fun `comments and other line breaks do not change the dependencies block`() {
        val commented = "// dependencies { implementation(libs.okhttp) }\n" +
            "dependencies { // open {\n" +
            "    /* implementation(libs.okhttp) */\n" +
            "    implementation(\n" +
            "        project(\":android:modules:commit\")\n" +
            "    ) // trailing } \"x\"\n" +
            "    testImplementation(libs.junit)\n" +
            "}\n"
        val oneLine = "dependencies{implementation(project(\":android:modules:commit\"))\ntestImplementation(libs.junit)}"
        val windows: String = goodScript.replace("\n", "\r\n")
        assertEquals("commit/accessibility: comments changed the dependencies block", listOf(allowedDependencies), dependencyBlocks(commented))
        assertEquals("commit/accessibility: a compact script changed the dependencies block", listOf(allowedDependencies), dependencyBlocks(oneLine))
        assertEquals("commit/accessibility: windows line breaks changed the dependencies block", listOf(allowedDependencies), dependencyBlocks(windows))
    }
}
