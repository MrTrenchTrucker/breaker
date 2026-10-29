package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A second copy of the config gate, reading the same allow-list the finished release file must hold.
 * It asserts what a release build is allowed to see: the union `typeViewFocused|typeWindowsChanged|
 * typeWindowStateChanged` with `flagRetrieveInteractiveWindows` passes its own allow-list, and the
 * real config passes too; while a config carrying an event type outside the three-type union is
 * refused by that same allow-list. Reusing the gate's [XmlTree]/[XmlAllowList] keeps these faithful
 * to the gate's own logic without editing it.
 */
internal class ConfigUnionTest {

    private val descriptionName: String = "commit_accessibility_service_description"

    /** The allow-list that matches the finished release config (union of events + flag). */
    private val allowed: XmlExpect = XmlExpect(
        name = "accessibility-service",
        attributes = mapOf(
            "xmlns:android" to XmlTree.ANDROID_NAMESPACE,
            "android:accessibilityEventTypes" to "typeViewFocused|typeWindowsChanged|typeWindowStateChanged",
            "android:accessibilityFeedbackType" to "feedbackGeneric",
            "android:canRetrieveWindowContent" to "true",
            "android:accessibilityFlags" to "flagRetrieveInteractiveWindows",
            "android:description" to "@string/$descriptionName",
        ),
    )

    private fun problems(xml: String): List<String> =
        XmlAllowList.problems(XmlTree.parse(xml), allowed)

    @Test
    fun `a config carrying the three-event union and the flag passes its allow-list`() {
        val withUnionFlag = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<accessibility-service xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:accessibilityEventTypes=\"typeViewFocused|typeWindowsChanged|typeWindowStateChanged\"\n    android:accessibilityFeedbackType=\"feedbackGeneric\"\n    android:canRetrieveWindowContent=\"true\"\n    android:accessibilityFlags=\"flagRetrieveInteractiveWindows\"\n    android:description=\"@string/commit_accessibility_service_description\" />"
        assertEquals(
            "commit/accessibility: the union+flag config should pass its allow-list",
            emptyList<String>(),
            problems(withUnionFlag),
        )
    }

    @Test
    fun `a real finished config passes the same allow-list`() {
        val text: String? = SourceFiles.mainFileOrNull("res/xml/commit_accessibility_service_config.xml")
        assertTrue("commit/accessibility: res/xml/commit_accessibility_service_config.xml is missing", text != null)
        assertEquals(
            "commit/accessibility: the real finished config differs from its allow-list",
            emptyList<String>(),
            problems(text ?: ""),
        )
    }

    @Test
    fun `a config carrying a type outside the three-type union is rejected by the allow-list`() {
        // a config that keeps two of the allowed events but adds an event outside the union (typeViewPortChanged)
        val bad = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<accessibility-service xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:accessibilityEventTypes=\"typeViewFocused|typeWindowsChanged|typeViewPortChanged\"\n    android:accessibilityFeedbackType=\"feedbackGeneric\"\n    android:canRetrieveWindowContent=\"true\"\n    android:accessibilityFlags=\"flagRetrieveInteractiveWindows\"\n    android:description=\"@string/commit_accessibility_service_description\" />"
        val problems = problems(bad)
        assertTrue(
            "commit/accessibility: the allow-list should reject an event outside the three-type union, said $problems",
            problems.any { it.contains("android:accessibilityEventTypes") },
        )
    }
}
