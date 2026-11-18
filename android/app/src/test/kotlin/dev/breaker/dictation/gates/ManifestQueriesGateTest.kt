package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one queries element of the app manifest. It must be exactly one, placed before the application
 * element, and hold exactly one intent whose only child is one action, the install confirm action. A package
 * element, a provider element, a second intent or action, or the all-packages permission would let the app see
 * or reach more than the system package installer, so each one is refused here with its reason. The manifest is
 * parsed as XML, so comments and attribute quoting are not content. Each refusal is an edit of the real manifest
 * text, and an edit whose target text is missing fails by name.
 */
internal class ManifestQueriesGateTest {

    private val confirm: String = "android.content.pm.action.CONFIRM_INSTALL"
    private val allPackages: String = "android.permission.QUERY_ALL_PACKAGES"

    private val queriesExpect: XmlExpect = XmlExpect(
        name = "queries",
        attributes = emptyMap(),
        children = listOf(
            XmlExpect(
                name = "intent",
                attributes = emptyMap(),
                children = listOf(XmlExpect(name = "action", attributes = mapOf("android:name" to confirm), key = "android:name")),
            ),
        ),
    )

    private val refusals: List<XmlSample> = listOf(
        XmlSample("an empty queries element", "    <queries>\n        <intent>\n            <action android:name=\"android.content.pm.action.CONFIRM_INSTALL\" />\n        </intent>\n    </queries>", "    <queries />", "queries lacks the element <intent>"),
        XmlSample("a package element in the queries element", "        </intent>", "        </intent>\n        <package android:name=\"dev.example.other\" />", "queries holds the element <package>"),
        XmlSample("a provider element in the queries element", "        </intent>", "        </intent>\n        <provider android:authorities=\"dev.example.other\" />", "queries holds the element <provider>"),
        XmlSample("a second intent", "        </intent>", "        </intent>\n        <intent>\n            <action android:name=\"android.content.pm.action.CONFIRM_INSTALL\" />\n        </intent>", "queries holds the element <intent>"),
        XmlSample("a second action in the intent", "        </intent>", "            <action android:name=\"android.intent.action.VIEW\" />\n        </intent>", "queries/intent holds the element <action>"),
        XmlSample("another action in place of the confirm action", "android:name=\"android.content.pm.action.CONFIRM_INSTALL\" />", "android:name=\"android.intent.action.VIEW\" />", "queries/intent lacks the element <action>"),
        XmlSample("the all-packages permission", "    <queries>", "    <uses-permission android:name=\"android.permission.QUERY_ALL_PACKAGES\" />\n    <queries>", "android.permission.QUERY_ALL_PACKAGES"),
        XmlSample("a second queries element", "    <queries>", "    <queries />\n    <queries>", "the manifest holds 2 queries elements"),
    )

    private val quiet: List<XmlSample> = listOf(
        XmlSample("a comment in the intent that names a package and all packages", "        </intent>", "        <!-- package provider android.permission.QUERY_ALL_PACKAGES -->\n        </intent>"),
        XmlSample("single quotes around the action name", "android:name=\"android.content.pm.action.CONFIRM_INSTALL\" />", "android:name='android.content.pm.action.CONFIRM_INSTALL' />"),
        XmlSample("extra blank lines before the closing queries tag", "    </queries>", "\n\n      </queries>"),
    )

    private fun manifestText(): String = AppSourceFiles.mainFile("AndroidManifest.xml")

    /** Every attribute value of [node] and of its children, in document order. */
    private fun valuesIn(node: XmlNode): List<String> =
        node.attributes.values.toList() + node.children.flatMap { valuesIn(it) }

    /** One line per way the queries element of [xml] differs from the allowed one; none means it is exactly that. */
    private fun queryProblems(xml: String): List<String> {
        val root: XmlNode = XmlTree.parse(xml)
        val found: MutableList<String> = ArrayList()
        val queries: List<XmlNode> = root.children.filter { it.name == "queries" }
        if (queries.size != 1) {
            found.add("the manifest holds ${queries.size} queries elements, expected one")
        } else {
            found.addAll(XmlAllowList.problems(queries[0], queriesExpect))
        }
        val order: List<String> = root.children.map { it.name }
        if (order.indexOf("queries") > order.indexOf("application")) {
            found.add("the queries element comes after the application element")
        }
        if (allPackages in valuesIn(root)) {
            found.add("the manifest names $allPackages, which is not allowed")
        }
        return found
    }

    @Test
    fun `the real manifest holds exactly one queries element with one intent and one action`() {
        assertEquals("app: the queries element of the manifest is not the one allowed element", emptyList<String>(), queryProblems(manifestText()))
    }

    @Test
    fun `the queries element comes before the application element in the real manifest`() {
        val order: List<String> = XmlTree.parse(manifestText()).children.map { it.name }
        assertTrue("app: no queries element before application in the manifest: $order", order.indexOf("queries") >= 0 && order.indexOf("queries") < order.indexOf("application"))
    }

    @Test
    fun `a manifest with a queries element that is refused for a reason is reported with that reason`() {
        for (sample in refusals) {
            val problems: List<String> = queryProblems(XmlSamples.apply("manifest", manifestText(), sample))
            assertTrue("app: the queries check accepted ${sample.label}", problems.isNotEmpty())
            for (fragment in sample.fragments) {
                assertTrue("app: the queries check did not say '$fragment' for ${sample.label}, it said $problems", problems.any { it.contains(fragment) })
            }
        }
    }

    @Test
    fun `a queries element placed after the application element is refused`() {
        val before = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><queries><intent><action android:name=\"android.content.pm.action.CONFIRM_INSTALL\" /></intent></queries><application /></manifest>"
        val after = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application /><queries><intent><action android:name=\"android.content.pm.action.CONFIRM_INSTALL\" /></intent></queries></manifest>"
        assertEquals("app: the queries element before application was refused", emptyList<String>(), queryProblems(before))
        assertTrue("app: the queries element after application was accepted", queryProblems(after).any { it.contains("comes after the application element") })
    }

    @Test
    fun `quiet spacing comments and single quotes in the queries element are not reported`() {
        for (sample in quiet) {
            assertEquals("app: the queries check flagged ${sample.label}", emptyList<String>(), queryProblems(XmlSamples.apply("manifest", manifestText(), sample)))
        }
    }
}
