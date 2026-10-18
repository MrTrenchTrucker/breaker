package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug manifest holds the probe page without a launcher filter. The probe activity has no
 * launcher filter of its own; the launcher activity carries the one shortcut hook, and the probe
 * provider is not exported. The debug manifest is read as XML with the same parser and the same
 * exact allow-list as the other manifest rules. The rule holds on the real file and refuses the
 * plant samples written beside it.
 */
internal class ProbeDebugManifestGateTest {

    private val debugManifest: XmlExpect = XmlExpect(
        name = "manifest",
        attributes = mapOf("xmlns:android" to XmlTree.ANDROID_NAMESPACE),
        children = listOf(
            XmlExpect(
                name = "application",
                attributes = emptyMap(),
                children = listOf(
                    XmlExpect(
                        name = "activity",
                        attributes = mapOf(
                            "android:name" to ".probe.KeyboardProbeActivity",
                            "android:exported" to "false",
                            "android:label" to "Breaker probe",
                            "android:theme" to "@android:style/Theme.DeviceDefault.NoActionBar",
                        ),
                        key = "android:name",
                    ),
                    XmlExpect(
                        name = "activity",
                        attributes = mapOf("android:name" to ".SettingsLauncherActivity"),
                        children = listOf(
                            XmlExpect(
                                name = "meta-data",
                                attributes = mapOf(
                                    "android:name" to "android.app.shortcuts",
                                    "android:resource" to "@xml/probe_shortcuts",
                                ),
                            ),
                        ),
                        key = "android:name",
                    ),
                    XmlExpect(
                        name = "provider",
                        attributes = mapOf(
                            "android:name" to ".probe.ProbeStartProvider",
                            "android:authorities" to "\${applicationId}.probe",
                            "android:exported" to "false",
                        ),
                    ),
                ),
            ),
        ),
    )

    /** The text of the debug manifest; fails by name when the file is missing. */
    private fun debugManifestText(): String {
        val file = File(AppSourceFiles.moduleRoot, "src/debug/AndroidManifest.xml")
        check(file.isFile) { "app: the debug manifest is missing" }
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `the debug manifest holds the probe activity without a launcher filter and the shortcut hook and one provider that is not exported`() {
        val text: String = debugManifestText()
        assertEquals("app: the debug manifest is not the allowed one", emptyList<String>(), XmlAllowList.problems(XmlTree.parse(text), debugManifest))
        val second = XmlSamples.apply(
            "manifest",
            text,
            XmlSample("a second activity", "</application>", "<activity android:name=\".probe.Other\" android:exported=\"false\" />\n    </application>"),
        )
        val exported = XmlSamples.apply(
            "manifest",
            text,
            XmlSample("an exported provider", "android:exported=\"false\" />", "android:exported=\"true\" />"),
        )
        val filter = XmlSamples.apply(
            "manifest",
            text,
            XmlSample(
                "the launcher filter on the probe activity",
                "android:theme=\"@android:style/Theme.DeviceDefault.NoActionBar\" />",
                "android:theme=\"@android:style/Theme.DeviceDefault.NoActionBar\">\n" +
                    "            <intent-filter>\n" +
                    "                <action android:name=\"android.intent.action.MAIN\" />\n" +
                    "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
                    "            </intent-filter>\n" +
                    "        </activity>",
            ),
        )
        val hook = XmlSamples.apply(
            "manifest",
            text,
            XmlSample(
                "the shortcut hook removed",
                "<meta-data\n                android:name=\"android.app.shortcuts\"\n                android:resource=\"@xml/probe_shortcuts\" />",
                "",
            ),
        )
        assertTrue("app: the debug manifest accepted a second activity", XmlAllowList.problems(XmlTree.parse(second), debugManifest).isNotEmpty())
        assertTrue("app: the debug manifest accepted an exported provider", XmlAllowList.problems(XmlTree.parse(exported), debugManifest).isNotEmpty())
        assertTrue("app: the debug manifest accepted a launcher filter on the probe activity", XmlAllowList.problems(XmlTree.parse(filter), debugManifest).isNotEmpty())
        assertTrue("app: the debug manifest accepted a dropped shortcut hook", XmlAllowList.problems(XmlTree.parse(hook), debugManifest).isNotEmpty())
    }
}
