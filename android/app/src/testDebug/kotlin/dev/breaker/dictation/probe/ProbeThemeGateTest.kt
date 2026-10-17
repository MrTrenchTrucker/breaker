package dev.breaker.dictation.probe

import dev.breaker.dictation.gates.AppSourceFiles
import dev.breaker.dictation.gates.XmlNode
import dev.breaker.dictation.gates.XmlSample
import dev.breaker.dictation.gates.XmlSamples
import dev.breaker.dictation.gates.XmlTree
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The debug manifest gives the probe activity the framework theme without an action bar. The rule
 * reads the debug manifest as XML, finds the probe activity element and checks its android:theme value.
 */
internal class ProbeThemeGateTest {

    private val activityName: String = ".probe.KeyboardProbeActivity"
    private val themeName: String = "android:theme"
    private val wantedTheme: String = "@android:style/Theme.DeviceDefault.NoActionBar"
    private val themeProblem: String = "probe: the probe activity must have the theme without an action bar"

    /** One problem unless the activity element named [activityName] carries exactly [wantedTheme]. */
    private fun themeProblems(xml: String): List<String> {
        val root: XmlNode = XmlTree.parse(xml)
        val application: XmlNode? = root.children.firstOrNull { it.name == "application" }
        val activity: XmlNode? = application?.children?.firstOrNull { it.name == "activity" && it.attributes["android:name"] == activityName }
        return if (activity != null && activity.attributes[themeName] == wantedTheme) emptyList() else listOf(themeProblem)
    }

    @Test
    fun `the debug manifest gives the probe activity the theme without an action bar`() {
        val manifest: String = File(AppSourceFiles.moduleRoot, "src/debug/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertEquals("probe: the debug manifest must give the probe activity the theme without an action bar", emptyList<String>(), themeProblems(manifest))
        val removed: String = XmlSamples.apply(
            "manifest",
            manifest,
            XmlSample("the theme attribute removed", "\n            android:theme=\"@android:style/Theme.DeviceDefault.NoActionBar\"", ""),
        )
        assertEquals("probe: a planted activity without the theme must be refused", listOf(themeProblem), themeProblems(removed))
        val bar: String = XmlSamples.apply(
            "manifest",
            manifest,
            XmlSample("the theme with an action bar", "\"@android:style/Theme.DeviceDefault.NoActionBar\"", "\"@android:style/Theme.DeviceDefault\""),
        )
        assertEquals("probe: a planted theme with an action bar must be refused", listOf(themeProblem), themeProblems(bar))
    }
}
