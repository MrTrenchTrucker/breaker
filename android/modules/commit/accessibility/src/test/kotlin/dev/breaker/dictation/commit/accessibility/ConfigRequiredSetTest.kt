package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility service config is gated on a *required set*: its event types must include the
 * keyboard window type `typeWindowsChanged`, and its flags must include `flagRetrieveInteractiveWindows`.
 * This test pins those two requirements on their own (TEST A on the real file, TEST B on a sample),
 * so a config that keeps one requirement but drops the other is named — distinct from the exact
 * allow-list gate in ConfigGateTest.kt.
 */
internal class ConfigRequiredSetTest {

    @Test
    fun `the real service config keeps the required event type and the interactive-windows flag`() {
        val text: String? = SourceFiles.mainFileOrNull("res/xml/commit_accessibility_service_config.xml")
        assertTrue("commit/accessibility: res/xml/commit_accessibility_service_config.xml is missing", !text.isNullOrBlank())
        val root: XmlNode = XmlTree.parse(text ?: "")
        val events: String = root.attributes["android:accessibilityEventTypes"] ?: ""
        val flags: String = root.attributes["android:accessibilityFlags"] ?: ""
        assertTrue("commit/accessibility: the service config must ask for all three keyboard window types, it asks for $events", "typeViewFocused" in events.split("|") && "typeWindowsChanged" in events.split("|") && "typeWindowStateChanged" in events.split("|"))
        assertTrue("commit/accessibility: the service config must retrieve interactive windows, its flags are $flags", "flagRetrieveInteractiveWindows" in flags.split("|"))
    }

    @Test
    fun `a config missing either requirement is named by the requirement it is missing`() {
        val good: String = "<accessibility-service xmlns:android=\"http://schemas.android.com/apk/res/android\" android:accessibilityEventTypes=\"typeViewFocused|typeWindowsChanged|typeWindowStateChanged\" android:accessibilityFlags=\"flagRetrieveInteractiveWindows\" android:canRetrieveWindowContent=\"true\" android:accessibilityFeedbackType=\"feedbackGeneric\" android:description=\"@string/commit_accessibility_service_description\" />"
        // a missing event type is named (here the one that was dropped, typeWindowStateChanged)
        val noState: XmlNode = XmlTree.parse(good.replace("|typeWindowStateChanged", ""))
        assertTrue("commit/accessibility: a config missing typeWindowStateChanged must fail the required set", requiredSetProblems(noState).any { it.contains("typeWindowStateChanged") })
        // a missing flag is named
        val noFlag: XmlNode = XmlTree.parse(good.replace(" android:accessibilityFlags=\"flagRetrieveInteractiveWindows\"", ""))
        assertTrue("commit/accessibility: a config without the flag must fail the required set", requiredSetProblems(noFlag).any { it.contains("flagRetrieveInteractiveWindows") })
        // the full good config passes the required set
        assertTrue("commit/accessibility: the required set must hold for the good config", requiredSetProblems(XmlTree.parse(good)).isEmpty())
    }

    private fun requiredSetProblems(root: XmlNode): List<String> {
        val problems: MutableList<String> = ArrayList()
        val events: String = root.attributes["android:accessibilityEventTypes"] ?: ""
        if ("typeViewFocused" !in events.split("|")) problems.add("the config lacks the required event type typeViewFocused")
        if ("typeWindowsChanged" !in events.split("|")) problems.add("the config lacks the required event type typeWindowsChanged")
        if ("typeWindowStateChanged" !in events.split("|")) problems.add("the config lacks the required event type typeWindowStateChanged")
        val flags: String = root.attributes["android:accessibilityFlags"] ?: ""
        if ("flagRetrieveInteractiveWindows" !in flags.split("|")) problems.add("the config lacks the required flag flagRetrieveInteractiveWindows")
        return problems
    }
}
