package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app manifest declares six permissions, the launcher activity and the one
 * microphone service, and nothing else.
 *
 * Adding a permission, a receiver, a provider, a second service, an export, an
 * intent filter on the service, a clear-text setting or any attribute or element
 * beyond the allow-list alters what the app may do on the phone, so it fails here
 * until it is argued for and the list is changed on purpose. The file is parsed as
 * XML, not searched as text: comments are not content, attribute order does not
 * matter, and nothing outside the allow-list passes.
 */
internal class ManifestGateTest {

    private val permissions: List<String> = listOf(
        "android.permission.INTERNET",
        "android.permission.RECORD_AUDIO",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.SYSTEM_ALERT_WINDOW",
    )

    private val good: String = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <application
        android:name=".BreakerApp"
        android:networkSecurityConfig="@xml/network_security_config"
        android:usesCleartextTraffic="false">
        <activity
            android:name=".SettingsLauncherActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name=".service.DictationForegroundService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
    </application>
</manifest>
"""

    private fun allowed(): XmlExpect {
        val application = XmlExpect(
            name = "application",
            attributes = mapOf(
                "android:name" to ".BreakerApp",
                "android:networkSecurityConfig" to "@xml/network_security_config",
                "android:usesCleartextTraffic" to "false",
            ),
            children = listOf(
                XmlExpect(
                    name = "activity",
                    attributes = mapOf("android:name" to ".SettingsLauncherActivity", "android:exported" to "true"),
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
        assertTrue("app: the manifest gate accepted $label", problems.isNotEmpty())
        for (fragment in fragments) {
            assertTrue(
                "app: the manifest gate did not say \"$fragment\" for $label, it said $problems",
                problems.any { it.contains(fragment) },
            )
        }
    }

    private val more: List<XmlSample> = listOf(
        XmlSample("a second system alert window permission", "<application", "<uses-permission android:name=\"android.permission.SYSTEM_ALERT_WINDOW\" />\n    <application", "holds the element <uses-permission>"),
        XmlSample("a camera permission", "<application", "<uses-permission android:name=\"android.permission.CAMERA\" />\n    <application", "holds the element <uses-permission>", "android.permission.CAMERA"),
        XmlSample("a network state permission", "<application", "<uses-permission android:name=\"android.permission.ACCESS_NETWORK_STATE\" />\n    <application", "holds the element <uses-permission>", "android.permission.ACCESS_NETWORK_STATE"),
        XmlSample("a contacts permission", "<application", "<uses-permission android:name=\"android.permission.READ_CONTACTS\" />\n    <application", "holds the element <uses-permission>", "android.permission.READ_CONTACTS"),
        XmlSample("a second internet permission", "<application", "<uses-permission android:name=\"android.permission.INTERNET\" />\n    <application", "holds the element <uses-permission>"),
        XmlSample("a max sdk attribute on a permission", "<uses-permission android:name=\"android.permission.INTERNET\" />", "<uses-permission android:name=\"android.permission.INTERNET\" android:maxSdkVersion=\"30\" />", "has the attribute android:maxSdkVersion"),
        XmlSample("a uses-feature element", "<application", "<uses-feature android:name=\"android.hardware.microphone\" />\n    <application", "holds the element <uses-feature>"),
        XmlSample("a queries element", "<application", "<queries />\n    <application", "holds the element <queries>"),
        XmlSample("a receiver", "</application>", "<receiver android:name=\".R\" android:exported=\"true\" />\n    </application>", "holds the element <receiver>"),
        XmlSample("a provider", "</application>", "<provider android:name=\".P\" android:authorities=\"x\" />\n    </application>", "holds the element <provider>"),
        XmlSample("a second service", "</application>", "<service android:name=\".Other\" />\n    </application>", "holds the element <service>"),
        XmlSample("a second activity", "</application>", "<activity android:name=\".Other\" />\n    </application>", "holds the element <activity>"),
        XmlSample("an activity alias", "</application>", "<activity-alias android:name=\".A\" android:targetActivity=\".B\" />\n    </application>", "holds the element <activity-alias>"),
        XmlSample("a meta-data element in the application", "</application>", "<meta-data android:name=\"a\" android:value=\"b\" />\n    </application>", "holds the element <meta-data>"),
        XmlSample("a meta-data element in the service", "android:foregroundServiceType=\"microphone\" />", "android:foregroundServiceType=\"microphone\"><meta-data android:name=\"a\" android:value=\"b\" /></service>", "holds the element <meta-data>"),
        XmlSample("an intent filter on the service", "android:foregroundServiceType=\"microphone\" />", "android:foregroundServiceType=\"microphone\"><intent-filter><action android:name=\"x\" /></intent-filter></service>", "holds the element <intent-filter>"),
        XmlSample("a permission attribute on the service", "android:exported=\"false\"", "android:exported=\"false\"\n            android:permission=\"android.permission.BIND_JOB_SERVICE\"", "has the attribute android:permission"),
        XmlSample("a process attribute on the service", "android:exported=\"false\"", "android:exported=\"false\" android:process=\":x\"", "has the attribute android:process"),
        XmlSample("a debuggable attribute", "android:name=\".BreakerApp\"", "android:name=\".BreakerApp\"\n        android:debuggable=\"true\"", "has the attribute android:debuggable"),
        XmlSample("an allowBackup attribute", "android:name=\".BreakerApp\"", "android:name=\".BreakerApp\"\n        android:allowBackup=\"true\"", "has the attribute android:allowBackup"),
        XmlSample("a testOnly attribute", "android:name=\".BreakerApp\"", "android:name=\".BreakerApp\"\n        android:testOnly=\"true\"", "has the attribute android:testOnly"),
        XmlSample("an icon attribute", "android:name=\".BreakerApp\"", "android:name=\".BreakerApp\"\n        android:icon=\"@mipmap/x\"", "has the attribute android:icon"),
        XmlSample("a package attribute on the manifest", "<manifest ", "<manifest package=\"x.y\" ", "has the attribute package"),
        XmlSample("a second namespace declaration", "<manifest ", "<manifest xmlns:tools=\"http://schemas.android.com/tools\" ", "has the attribute xmlns:tools"),
        XmlSample("an attribute on the launcher activity", "android:exported=\"true\"", "android:exported=\"true\" android:permission=\"x\"", "has the attribute android:permission"),
        XmlSample("a second filter on the launcher activity", "</activity>", "<intent-filter><action android:name=\"android.intent.action.VIEW\" /></intent-filter>\n        </activity>", "holds the element <intent-filter>"),
        XmlSample("a second category in the launcher filter", "<category android:name=\"android.intent.category.LAUNCHER\" />", "<category android:name=\"android.intent.category.LAUNCHER\" />\n                <category android:name=\"android.intent.category.DEFAULT\" />", "holds the element <category>"),
        XmlSample("character data in the application", "android:usesCleartextTraffic=\"false\">", "android:usesCleartextTraffic=\"false\">text", "holds character data"),
    )

    private val changed: List<XmlSample> = listOf(
        XmlSample("the service exported", "android:exported=\"false\"", "android:exported=\"true\"", "attribute android:exported is \"true\""),
        XmlSample("the service export missing", "            android:exported=\"false\"\n", "", "lacks the attribute android:exported"),
        XmlSample("the service type changed", "android:foregroundServiceType=\"microphone\"", "android:foregroundServiceType=\"mediaPlayback\"", "attribute android:foregroundServiceType is \"mediaPlayback\""),
        XmlSample("two service types", "android:foregroundServiceType=\"microphone\"", "android:foregroundServiceType=\"microphone|camera\"", "attribute android:foregroundServiceType is \"microphone|camera\""),
        XmlSample("no service type", "\n            android:foregroundServiceType=\"microphone\"", "", "lacks the attribute android:foregroundServiceType"),
        XmlSample("the service named outside the service package", ".service.DictationForegroundService", ".DictationForegroundService", "attribute android:name is \".DictationForegroundService\""),
        XmlSample("another service class", ".service.DictationForegroundService", ".service.Other", "attribute android:name is \".service.Other\""),
        XmlSample("no service", "        <service\n            android:name=\".service.DictationForegroundService\"\n            android:exported=\"false\"\n            android:foregroundServiceType=\"microphone\" />\n", "", "lacks the element <service>"),
        XmlSample("clear text allowed", "android:usesCleartextTraffic=\"false\"", "android:usesCleartextTraffic=\"true\"", "attribute android:usesCleartextTraffic is \"true\""),
        XmlSample("clear text not stated", "\n        android:usesCleartextTraffic=\"false\">", ">", "lacks the attribute android:usesCleartextTraffic"),
        XmlSample("no network config", "\n        android:networkSecurityConfig=\"@xml/network_security_config\"", "", "lacks the attribute android:networkSecurityConfig"),
        XmlSample("another network config", "@xml/network_security_config", "@xml/other_config", "attribute android:networkSecurityConfig is \"@xml/other_config\""),
        XmlSample("another application class", "android:name=\".BreakerApp\"", "android:name=\".Other\"", "attribute android:name is \".Other\""),
        XmlSample("the launcher activity not exported", "android:exported=\"true\"", "android:exported=\"false\"", "attribute android:exported is \"false\""),
        XmlSample("another launcher activity", ".SettingsLauncherActivity", ".Other", "attribute android:name is \".Other\""),
        XmlSample("no launcher category", "\n                <category android:name=\"android.intent.category.LAUNCHER\" />", "", "lacks the element <category>"),
        XmlSample("no record audio permission", "    <uses-permission android:name=\"android.permission.RECORD_AUDIO\" />\n", "", "lacks the element <uses-permission>", "android.permission.RECORD_AUDIO"),
        XmlSample("no foreground service permission", "    <uses-permission android:name=\"android.permission.FOREGROUND_SERVICE\" />\n", "", "lacks the element <uses-permission>", "android.permission.FOREGROUND_SERVICE\""),
        XmlSample("no notifications permission", "    <uses-permission android:name=\"android.permission.POST_NOTIFICATIONS\" />\n", "", "lacks the element <uses-permission>", "android.permission.POST_NOTIFICATIONS"),
        XmlSample("no system alert window permission", "    <uses-permission android:name=\"android.permission.SYSTEM_ALERT_WINDOW\" />\n", "", "lacks the element <uses-permission>", "android.permission.SYSTEM_ALERT_WINDOW"),
        XmlSample("a permission renamed", "android.permission.FOREGROUND_SERVICE_MICROPHONE", "android.permission.FOREGROUND_SERVICE_CAMERA", "lacks the element <uses-permission>", "android.permission.FOREGROUND_SERVICE_MICROPHONE", "android.permission.FOREGROUND_SERVICE_CAMERA"),
        XmlSample("another namespace", "http://schemas.android.com/apk/res/android", "http://example.invalid/ns", "attribute xmlns:android"),
    )

    private val quiet: List<XmlSample> = listOf(
        XmlSample("a comment that names forbidden things", "<application", "<!-- uses-permission android.permission.SYSTEM_ALERT_WINDOW android:exported=\"true\" <service android:name=\".X\" /> android:usesCleartextTraffic=\"true\" -->\n    <application"),
        XmlSample("a comment between the elements", "</activity>", "</activity>\n        <!-- receiver provider -->"),
        XmlSample("application attributes in another order", "android:name=\".BreakerApp\"\n        android:networkSecurityConfig=\"@xml/network_security_config\"", "android:networkSecurityConfig=\"@xml/network_security_config\"\n        android:name=\".BreakerApp\""),
        XmlSample("permissions in another order", "    <uses-permission android:name=\"android.permission.INTERNET\" />\n    <uses-permission android:name=\"android.permission.RECORD_AUDIO\" />", "    <uses-permission android:name=\"android.permission.RECORD_AUDIO\" />\n    <uses-permission android:name=\"android.permission.INTERNET\" />"),
        XmlSample("single quoted values", "android:exported=\"false\"", "android:exported='false'"),
        XmlSample("service attributes on one line", "android:name=\".service.DictationForegroundService\"\n            android:exported=\"false\"", "android:exported=\"false\" android:name=\".service.DictationForegroundService\""),
    )

    @Test
    fun `the real manifest declares exactly the six permissions, the launcher activity and the one service`() {
        val text: String = AppSourceFiles.mainFile("AndroidManifest.xml")
        assertTrue("app: src/main/AndroidManifest.xml is empty", text.isNotBlank())
        val declared: List<String> = XmlTree.parse(text).children
            .filter { it.name == "uses-permission" }
            .map { it.attributes["android:name"] ?: "" }
            .sorted()
        assertEquals("app: the manifest permissions are not the six allowed ones", permissions.sorted(), declared)
        val problems: List<String> = manifestProblems(text)
        assertEquals("app: the manifest differs from its allow-list: $problems", emptyList<String>(), problems)
    }

    @Test
    fun `the exact good manifest passes, and so do comments, another attribute order and single quotes`() {
        assertEquals("app: the exact good manifest was rejected", emptyList<String>(), manifestProblems(good))
        for (sample in quiet) {
            val xml: String = XmlSamples.apply("manifest", good, sample)
            assertEquals("app: the manifest gate flagged ${sample.label}", emptyList<String>(), manifestProblems(xml))
        }
    }

    @Test
    fun `a manifest with more than the allow-list is rejected with what is wrong`() {
        for (sample in more) {
            assertRejected(sample.label, XmlSamples.apply("manifest", good, sample), sample.fragments)
        }
    }

    @Test
    fun `a manifest with other values or missing parts is rejected with what is wrong`() {
        for (sample in changed) {
            assertRejected(sample.label, XmlSamples.apply("manifest", good, sample), sample.fragments)
        }
        val ns = "xmlns:android=\"http://schemas.android.com/apk/res/android\""
        assertRejected("no application", "<manifest $ns></manifest>", listOf("lacks the element <application>"))
        assertRejected("another root element", "<resources />", listOf("is <resources>, expected <manifest>"))
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
                "app: the manifest gate passed text that is not safe XML: $xml",
                IllegalStateException::class.java,
            ) { manifestProblems(xml) }
        }
    }

    @Test
    fun `a missing file is reported by name and is never read as empty`() {
        assertNull("app: a file that does not exist was read as text", AppSourceFiles.mainFileOrNull("res/no/such/file.xml"))
        val failure: IllegalStateException = assertThrows(
            "app: reading a missing file did not fail",
            IllegalStateException::class.java,
        ) { AppSourceFiles.mainFile("res/no/such/file.xml") }
        val said: String = failure.message ?: ""
        assertTrue("app: the missing-file failure does not name the file: $said", said.startsWith("app: ") && said.contains("res/no/such/file.xml"))
    }
}
