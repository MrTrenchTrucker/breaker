package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the return activity in the app manifest. The allow-list is a copy of the one in ManifestGateTest,
 * because that file's helpers are private; ManifestGateTest keeps the whole-manifest allow-list. Each
 * refusal sample is an edit of the real manifest and must be rejected with the stated reason.
 */
internal class HandoffReturnManifestPinTest {

    private val permissions: List<String> = listOf(
        "android.permission.INTERNET",
        "android.permission.RECORD_AUDIO",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.SYSTEM_ALERT_WINDOW",
    )

    // A copy of the allow-list in ManifestGateTest.kt; the two must be changed together.
    private fun allowed(): XmlExpect {
        val application = XmlExpect(
            name = "application",
            attributes = mapOf(
                "android:name" to ".BreakerApp",
                "android:label" to "@string/app_name",
                "android:networkSecurityConfig" to "@xml/network_security_config",
                "android:usesCleartextTraffic" to "true",
                "android:allowBackup" to "false",
                "android:dataExtractionRules" to "@xml/data_extraction_rules",
                "android:fullBackupContent" to "@xml/backup_rules",
                "android:icon" to "@mipmap/ic_launcher",
                "android:roundIcon" to "@mipmap/ic_launcher_round",
            ),
            children = listOf(
                XmlExpect(
                    name = "activity",
                    attributes = mapOf(
                        "android:name" to ".SettingsLauncherActivity",
                        "android:exported" to "true",
                        "android:theme" to "@android:style/Theme.Material.Light.NoActionBar",
                        "android:label" to "@string/app_name",
                    ),
                    children = listOf(
                        XmlExpect(
                            name = "intent-filter",
                            attributes = emptyMap(),
                            children = listOf(
                                XmlExpect("action", mapOf("android:name" to "android.intent.action.MAIN"), key = "android:name"),
                                XmlExpect("category", mapOf("android:name" to "android.intent.category.LAUNCHER"), key = "android:name"),
                            ),
                        ),
                    ),
                ),
                XmlExpect(
                    name = "activity",
                    attributes = mapOf(
                        "android:name" to ".host.HandoffReturnActivity",
                        "android:exported" to "true",
                        "android:theme" to "@android:style/Theme.NoDisplay",
                        "android:excludeFromRecents" to "true",
                        "android:noHistory" to "true",
                    ),
                    children = listOf(
                        XmlExpect(
                            name = "intent-filter",
                            attributes = emptyMap(),
                            children = listOf(
                                XmlExpect("action", mapOf("android:name" to "android.intent.action.VIEW"), key = "android:name"),
                                XmlExpect("category", mapOf("android:name" to "android.intent.category.DEFAULT"), key = "android:name"),
                                XmlExpect("category", mapOf("android:name" to "android.intent.category.BROWSABLE"), key = "android:name"),
                                XmlExpect("data", mapOf("android:scheme" to "breaker", "android:host" to "handoff")),
                            ),
                        ),
                    ),
                ),
                XmlExpect(
                    name = "service",
                    attributes = mapOf(
                        "android:name" to ".service.DictationForegroundService",
                        "android:exported" to "false",
                        "android:foregroundServiceType" to "microphone",
                    ),
                ),
            ),
        )
        return XmlExpect(
            name = "manifest",
            attributes = mapOf("xmlns:android" to XmlTree.ANDROID_NAMESPACE),
            children = permissions.map { XmlExpect("uses-permission", mapOf("android:name" to it), key = "android:name") } + application,
        )
    }

    /** One line per difference between [xml] and the allow-list; none means it matches exactly. Throws on text that is not XML. */
    private fun manifestProblems(xml: String): List<String> = XmlAllowList.problems(XmlTree.parse(xml), allowed())

    private fun assertRejected(label: String, xml: String, fragments: List<String>) {
        val problems: List<String> = manifestProblems(xml)
        assertTrue("app: the manifest check accepted $label", problems.isNotEmpty())
        for (fragment in fragments) {
            assertTrue(
                "app: the manifest check did not say \"$fragment\" for $label, it said $problems",
                problems.any { it.contains(fragment) },
            )
        }
    }

    @Test
    fun `the return activity takes only the one breaker handoff link and stays hidden`() {
        val good: String = AppSourceFiles.mainFile("AndroidManifest.xml")
        assertEquals("app: the real manifest differs from the allow-list", emptyList<String>(), manifestProblems(good))
        val application: XmlNode = XmlTree.parse(good).children.single { it.name == "application" }
        val activities: List<XmlNode> = application.children.filter { it.name == "activity" }
        assertEquals("app: the manifest does not hold exactly two exported activities", 2, activities.count { it.attributes["android:exported"] == "true" })
        val back: XmlNode = activities.single { it.attributes["android:name"] == ".host.HandoffReturnActivity" }
        assertEquals(
            "app: the return activity attributes are not the hidden set",
            mapOf(
                "android:name" to ".host.HandoffReturnActivity",
                "android:exported" to "true",
                "android:theme" to "@android:style/Theme.NoDisplay",
                "android:excludeFromRecents" to "true",
                "android:noHistory" to "true",
            ),
            back.attributes,
        )
        val filters: List<XmlNode> = back.children.filter { it.name == "intent-filter" }
        assertEquals("app: the return activity must hold exactly one intent filter", 1, filters.size)
        val filter: XmlNode = filters.single()
        assertEquals("app: the return filter holds elements other than action, category and data", 4, filter.children.size)
        assertEquals("app: the return filter action is not the view action", listOf("android.intent.action.VIEW"), filter.children.filter { it.name == "action" }.map { it.attributes["android:name"] })
        assertEquals("app: the return filter categories are not browsable and default", listOf("android.intent.category.BROWSABLE", "android.intent.category.DEFAULT"), filter.children.filter { it.name == "category" }.map { it.attributes["android:name"] ?: "" }.sorted())
        val data: List<XmlNode> = filter.children.filter { it.name == "data" }
        assertEquals("app: the return filter must hold one data element", 1, data.size)
        assertEquals("app: the return data is not the breaker handoff pair only", mapOf("android:scheme" to "breaker", "android:host" to "handoff"), data.single().attributes)
        val refusals: List<XmlSample> = listOf(
            XmlSample("the return link scheme is another one", "android:scheme=\"breaker\"", "android:scheme=\"http\"", "attribute android:scheme is \"http\""),
            XmlSample("the return data holds a path", "android:host=\"handoff\" />", "android:host=\"handoff\" android:path=\"/x\" />", "has the attribute android:path"),
            XmlSample("the return activity is not exported", "android:exported=\"true\"\n            android:theme=\"@android:style/Theme.NoDisplay\"", "android:exported=\"false\"\n            android:theme=\"@android:style/Theme.NoDisplay\"", "attribute android:exported is \"false\""),
            XmlSample("the return activity shows a theme", "@android:style/Theme.NoDisplay", "@android:style/Theme.Material.Light.NoActionBar", "attribute android:theme is"),
        )
        for (sample in refusals) {
            assertRejected(sample.label, XmlSamples.apply("manifest", good, sample), sample.fragments)
        }
    }
}
