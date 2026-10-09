package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network security config refuses clear text for every host and trusts only the
 * system certificate store, and the manifest points at it.
 *
 * A config can allow clear text for one host, trust certificates the user installed,
 * trust an app-bundled authority, or loosen everything in a debug build. The app
 * needs none of that, so the file holds one base-config with clear text off and one
 * trust anchor, the system store, and nothing else: no host entry, no debug override,
 * no pinned key, no extra attribute. The file is parsed as XML, so comments are not
 * content and attribute order does not matter.
 */
internal class NetworkConfigGateTest {

    private val configPath: String = "res/xml/network_security_config.xml"

    private val good: String = """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
"""

    private val goodManifest: String = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".BreakerApp"
        android:networkSecurityConfig="@xml/network_security_config"
        android:usesCleartextTraffic="false" />
</manifest>
"""

    private val allowed: XmlExpect = XmlExpect(
        name = "network-security-config",
        attributes = emptyMap(),
        children = listOf(
            XmlExpect(
                name = "base-config",
                attributes = mapOf("cleartextTrafficPermitted" to "false"),
                children = listOf(
                    XmlExpect(
                        name = "trust-anchors",
                        attributes = emptyMap(),
                        children = listOf(XmlExpect("certificates", mapOf("src" to "system"))),
                    ),
                ),
            ),
        ),
    )

    /** One line per difference between [xml] and the allow-list; none means it matches exactly. Throws on text that is not XML. */
    private fun configProblems(xml: String): List<String> = XmlAllowList.problems(XmlTree.parse(xml), allowed)

    /** The file below `src/main` that the manifest's network config attribute points at, or null when it points at no xml resource. */
    private fun configTarget(manifestXml: String): String? {
        val application: XmlNode = XmlTree.parse(manifestXml).children.firstOrNull { it.name == "application" } ?: return null
        val value: String = application.attributes["android:networkSecurityConfig"] ?: return null
        return if (value.startsWith("@xml/") && value.length > 5) "res/xml/" + value.removePrefix("@xml/") + ".xml" else null
    }

    /** The values the manifest and the config give for clear text, in that order; null where a value is not stated. */
    private fun cleartextSettings(manifestXml: String, configXml: String): List<String?> {
        val application: XmlNode? = XmlTree.parse(manifestXml).children.firstOrNull { it.name == "application" }
        val base: XmlNode? = XmlTree.parse(configXml).children.firstOrNull { it.name == "base-config" }
        return listOf(application?.attributes?.get("android:usesCleartextTraffic"), base?.attributes?.get("cleartextTrafficPermitted"))
    }

    private fun assertRejected(label: String, xml: String, fragments: List<String>) {
        val problems: List<String> = configProblems(xml)
        assertTrue("app: the network config gate accepted $label", problems.isNotEmpty())
        for (fragment in fragments) {
            assertTrue(
                "app: the network config gate did not say \"$fragment\" for $label, it said $problems",
                problems.any { it.contains(fragment) },
            )
        }
    }

    private val firing: List<XmlSample> = listOf(
        XmlSample("clear text permitted", "cleartextTrafficPermitted=\"false\"", "cleartextTrafficPermitted=\"true\"", "attribute cleartextTrafficPermitted is \"true\""),
        XmlSample("clear text not stated", " cleartextTrafficPermitted=\"false\"", "", "lacks the attribute cleartextTrafficPermitted"),
        XmlSample("a host entry with a clear text exception", "</network-security-config>", "<domain-config cleartextTrafficPermitted=\"true\"><domain includeSubdomains=\"true\">example.com</domain></domain-config>\n</network-security-config>", "holds the element <domain-config>"),
        XmlSample("a host entry for a local address", "</network-security-config>", "<domain-config cleartextTrafficPermitted=\"true\"><domain>10.0.2.2</domain></domain-config>\n</network-security-config>", "holds the element <domain-config>"),
        XmlSample("a debug override", "</network-security-config>", "<debug-overrides><trust-anchors><certificates src=\"user\" /></trust-anchors></debug-overrides>\n</network-security-config>", "holds the element <debug-overrides>"),
        XmlSample("a pinned key beside the base config", "</network-security-config>", "<pin-set><pin digest=\"SHA-256\">AAAA</pin></pin-set>\n</network-security-config>", "holds the element <pin-set>"),
        XmlSample("a pinned key inside the base config", "</base-config>", "<pin-set><pin digest=\"SHA-256\">AAAA</pin></pin-set>\n    </base-config>", "holds the element <pin-set>"),
        XmlSample("a second base config", "</network-security-config>", "<base-config cleartextTrafficPermitted=\"false\" />\n</network-security-config>", "holds the element <base-config>"),
        XmlSample("user certificates", "src=\"system\"", "src=\"user\"", "attribute src is \"user\""),
        XmlSample("a raw resource authority", "src=\"system\"", "src=\"@raw/extra_ca\"", "attribute src is \"@raw/extra_ca\""),
        XmlSample("user certificates beside the system store", "<certificates src=\"system\" />", "<certificates src=\"system\" />\n            <certificates src=\"user\" />", "holds the element <certificates>"),
        XmlSample("an override of pins on the anchor", "<certificates src=\"system\" />", "<certificates src=\"system\" overridePins=\"true\" />", "has the attribute overridePins"),
        XmlSample("no source on the anchor", "<certificates src=\"system\" />", "<certificates />", "lacks the attribute src"),
        XmlSample("no anchors", "        <trust-anchors>\n            <certificates src=\"system\" />\n        </trust-anchors>\n", "", "lacks the element <trust-anchors>"),
        XmlSample("an attribute on the anchors", "<trust-anchors>", "<trust-anchors extra=\"1\">", "has the attribute extra"),
        XmlSample("another attribute on the base config", "<base-config ", "<base-config android:foo=\"1\" ", "has the attribute android:foo"),
        XmlSample("an attribute on the root", "<network-security-config>", "<network-security-config xmlns:android=\"http://schemas.android.com/apk/res/android\">", "has the attribute xmlns:android"),
        XmlSample("no base config", "    <base-config cleartextTrafficPermitted=\"false\">\n        <trust-anchors>\n            <certificates src=\"system\" />\n        </trust-anchors>\n    </base-config>\n", "", "lacks the element <base-config>"),
        XmlSample("character data in the base config", "cleartextTrafficPermitted=\"false\">", "cleartextTrafficPermitted=\"false\">text", "holds character data"),
    )

    private val quiet: List<XmlSample> = listOf(
        XmlSample("a comment that names forbidden things", "<base-config", "<!-- domain-config debug-overrides pin-set src=\"user\" cleartextTrafficPermitted=\"true\" -->\n    <base-config"),
        XmlSample("single quotes and spacing on the anchor", "<certificates src=\"system\" />", "<certificates  src='system'  />"),
        XmlSample("no declaration line", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n", ""),
    )

    @Test
    fun `the real network config holds one base config with clear text off and the system store only`() {
        val text: String = AppSourceFiles.mainFile(configPath)
        assertTrue("app: src/main/$configPath is empty", text.isNotBlank())
        val problems: List<String> = configProblems(text)
        assertEquals("app: the network config differs from its allow-list: $problems", emptyList<String>(), problems)
    }

    @Test
    fun `the manifest points at the network config file this module holds`() {
        val manifest: String = AppSourceFiles.mainFile("AndroidManifest.xml")
        val target: String? = configTarget(manifest)
        assertEquals("app: the manifest does not point at @xml/network_security_config", configPath, target)
        assertTrue("app: $target is missing", AppSourceFiles.mainFileOrNull(target ?: "") != null)
    }

    @Test
    fun `the manifest and the config both refuse clear text`() {
        val settings: List<String?> = cleartextSettings(AppSourceFiles.mainFile("AndroidManifest.xml"), AppSourceFiles.mainFile(configPath))
        assertEquals("app: clear text is not refused in both the manifest and the network config", listOf<String?>("false", "false"), settings)
    }

    @Test
    fun `the exact good config passes, with comments that name forbidden parts and in another spelling`() {
        assertEquals("app: the exact good config was rejected", emptyList<String>(), configProblems(good))
        for (sample in quiet) {
            val xml: String = XmlSamples.apply("network config", good, sample)
            assertEquals("app: the network config gate flagged ${sample.label}", emptyList<String>(), configProblems(xml))
        }
    }

    @Test
    fun `a config with a host entry, an override, a pin, other anchors or other values is rejected with what is wrong`() {
        for (sample in firing) {
            assertRejected(sample.label, XmlSamples.apply("network config", good, sample), sample.fragments)
        }
        assertRejected("another root element", "<resources />", listOf("is <resources>, expected <network-security-config>"))
    }

    @Test
    fun `text that is not safe or not well formed XML is refused, not passed`() {
        val broken: List<String> = listOf(
            "",
            "not xml",
            "<network-security-config>",
            "<network-security-config><base-config a=\"1\" a=\"2\" /></network-security-config>",
            "<?xml version=\"1.0\"?><!DOCTYPE network-security-config [<!ENTITY x \"y\">]><network-security-config />",
        )
        for (xml in broken) {
            val refused: Boolean = try {
                configProblems(xml)
                false
            } catch (e: IllegalStateException) {
                true
            }
            assertTrue("app: the network config gate passed text that is not safe XML: $xml", refused)
        }
    }

    @Test
    fun `the manifest pointer and the clear text values are read from a manifest and a config text`() {
        assertEquals("app: the pointer was misread", configPath, configTarget(goodManifest))
        val other = goodManifest.replace("@xml/network_security_config", "@raw/network_security_config")
        assertNull("app: a raw resource was read as an xml pointer", configTarget(other))
        val none = goodManifest.replace("\n        android:networkSecurityConfig=\"@xml/network_security_config\"", "")
        assertNull("app: a missing pointer was read as one", configTarget(none))
        val empty = goodManifest.replace("@xml/network_security_config", "@xml/")
        assertNull("app: an empty xml name was read as a pointer", configTarget(empty))
        assertEquals("app: the clear text values were misread", listOf<String?>("false", "false"), cleartextSettings(goodManifest, good))
        val loose = good.replace("cleartextTrafficPermitted=\"false\"", "cleartextTrafficPermitted=\"true\"")
        assertEquals("app: a config that allows clear text was read as refusing it", listOf<String?>("false", "true"), cleartextSettings(goodManifest, loose))
        val manifestLoose = goodManifest.replace("usesCleartextTraffic=\"false\"", "usesCleartextTraffic=\"true\"")
        assertEquals("app: a manifest that allows clear text was read as refusing it", listOf<String?>("true", "false"), cleartextSettings(manifestLoose, good))
        val unstated = goodManifest.replace("\n        android:usesCleartextTraffic=\"false\"", "")
        assertEquals("app: a manifest that states nothing was read as stating false", listOf<String?>(null, "false"), cleartextSettings(unstated, good))
    }
}
