package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility service config asks for focused-view events and window content,
 * and for nothing broader.
 *
 * An accessibility service can be configured to see every event of every app, to
 * perform gestures, to read the screen outside the focused field, to filter keys or
 * to take screenshots. This module needs none of that to find the focused field, so
 * the config holds exactly four attributes with exactly their values: one event type,
 * one feedback type, the window content capability, and the description string. The
 * file is parsed as XML, so comments are not content and attribute order does not
 * matter; an extra attribute, an extra element or another value fails.
 *
 * The strings file is gated too: exactly the label and the description, the description short,
 * plain ASCII with escaped apostrophes, and still saying what the service does and does not do.
 */
internal class ConfigGateTest {

    private val descriptionName: String = "commit_accessibility_service_description"
    private val labelName: String = "commit_accessibility_service_label"

    /** What the description must say, as phrases the shipped text holds; compared in lower case with white space collapsed. */
    private val claims: List<String> = listOf(
        "put the words you dictate into the field you are typing in",
        "acts only when you send",
        "finds that field and reads only its text",
        "never fills a password field",
        "keeps nothing from your screen",
        "sends nothing off the phone",
    )

    private val goodDescription: String = "Breaker uses this to put the words you dictate into the field you are typing in. It acts only when you send. " +
        "It finds that field and reads only its text, to place the new words. It never fills a password field, keeps nothing from your screen, and sends nothing off the phone."

    private val good: String = """<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeViewFocused"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:description="@string/commit_accessibility_service_description" />
"""

    private val allowed: XmlExpect = XmlExpect(
        name = "accessibility-service",
        attributes = mapOf(
            "xmlns:android" to XmlTree.ANDROID_NAMESPACE,
            "android:accessibilityEventTypes" to "typeViewFocused",
            "android:accessibilityFeedbackType" to "feedbackGeneric",
            "android:canRetrieveWindowContent" to "true",
            "android:description" to "@string/$descriptionName",
        ),
    )

    /** One line per difference between [xml] and the allow-list; none means it matches exactly. Throws on text that is not XML. */
    private fun configProblems(xml: String): List<String> = XmlAllowList.problems(XmlTree.parse(xml), allowed)

    /** A strings file with the label (none when [label] is null), the description and any [extra] entries. */
    private fun stringsXml(description: String, label: String? = "Breaker", extra: String = ""): String {
        val labelLine: String = if (label == null) "" else "    <string name=\"" + labelName + "\">" + label + "</string>\n"
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" + labelLine +
            "    <string name=\"" + descriptionName + "\">" + description + "</string>\n" + extra + "</resources>\n"
    }

    /** One line per way the strings file differs from its rules; none means it matches. Throws on text that is not XML. */
    private fun stringsProblems(xml: String): List<String> {
        val root: XmlNode = XmlTree.parse(xml)
        val problems: MutableList<String> = ArrayList()
        if (root.name != "resources") problems.add("the root is <" + root.name + ">, expected <resources>")
        for (other in root.children.filter { it.name != "string" }) {
            problems.add("holds the element <" + other.name + ">, which is not allowed")
        }
        val entries: List<XmlNode> = root.children.filter { it.name == "string" }
        val names: List<String> = entries.map { it.attributes["name"] ?: "" }.sorted()
        if (names != listOf(descriptionName, labelName).sorted()) {
            problems.add("the string names are " + names + ", expected exactly the label and the description")
        }
        val label: String? = entries.firstOrNull { it.attributes["name"] == labelName }?.text
        if (label != null && label != "Breaker") problems.add("the label is \"" + label + "\", expected \"Breaker\"")
        val text: String = entries.firstOrNull { it.attributes["name"] == descriptionName }?.text ?: return problems
        if (text.length > 300) problems.add("the description is " + text.length + " characters, more than 300")
        if (text.any { it.code > 127 }) problems.add("the description holds a character outside ASCII")
        if (text.indices.any { text[it] == '\'' && (it == 0 || text[it - 1] != '\\') }) {
            problems.add("the description has an apostrophe without a backslash")
        }
        val plain: String = text.lowercase().replace(Regex("""\s+"""), " ")
        for (claim in claims) {
            if (!plain.contains(claim)) { problems.add("the description does not say \"" + claim + "\"") }
        }
        return problems
    }

    /** [source] with every [from] replaced; fails when [from] is not there, so a sample cannot go quiet by a typo. */
    private fun edit(source: String, from: String, to: String): String {
        assertTrue("commit/accessibility: the config sample has no text \"$from\" to change", source.contains(from))
        return source.replace(from, to)
    }

    private fun assertRejected(label: String, xml: String, fragment: String) {
        val problems: List<String> = configProblems(xml)
        assertTrue("commit/accessibility: the config gate accepted $label", problems.isNotEmpty())
        assertTrue(
            "commit/accessibility: the config gate did not say \"$fragment\" for $label, it said $problems",
            problems.any { it.contains(fragment) },
        )
    }

    @Test
    fun `the real service config holds exactly the four allowed attributes`() {
        val text: String? = SourceFiles.mainFileOrNull("res/xml/commit_accessibility_service_config.xml")
        assertTrue("commit/accessibility: res/xml/commit_accessibility_service_config.xml is missing", text != null)
        assertTrue("commit/accessibility: res/xml/commit_accessibility_service_config.xml is empty", !text.isNullOrBlank())
        val problems: List<String> = configProblems(text ?: "")
        assertEquals("commit/accessibility: the service config differs from its allow-list: $problems", emptyList<String>(), problems)
    }

    @Test
    fun `the service config description points at a string this module defines`() {
        val strings: String? = SourceFiles.mainFileOrNull("res/values/strings.xml")
        assertTrue("commit/accessibility: src/main/res/values/strings.xml is missing", strings != null)
        assertTrue(
            "commit/accessibility: strings.xml defines no string named $descriptionName",
            descriptionName in XmlTree.stringNames(strings ?: "<resources/>"),
        )
    }

    @Test
    fun `the exact good config passes, with comments that name forbidden attributes, and in another attribute order`() {
        assertEquals("commit/accessibility: the exact good config was rejected", emptyList<String>(), configProblems(good))
        val commented: String = edit(
            good,
            "<accessibility-service",
            "<!-- android:accessibilityFlags=\"flagDefault\" android:packageNames=\"x\" android:canPerformGestures=\"true\" typeAllMask -->\n<accessibility-service",
        )
        assertEquals("commit/accessibility: a comment that names forbidden attributes was read as content", emptyList<String>(), configProblems(commented))
        val reordered: String = edit(
            good,
            "android:canRetrieveWindowContent=\"true\"\n    android:description=\"@string/commit_accessibility_service_description\"",
            "android:description=\"@string/commit_accessibility_service_description\"\n    android:canRetrieveWindowContent=\"true\"",
        )
        assertEquals("commit/accessibility: attribute order changed the verdict", emptyList<String>(), configProblems(reordered))
    }

    @Test
    fun `a config that asks for more than focused-view events and window content is rejected`() {
        assertRejected(
            "a second event type",
            edit(good, "typeViewFocused", "typeViewFocused|typeViewClicked"),
            "attribute android:accessibilityEventTypes is \"typeViewFocused|typeViewClicked\"",
        )
        assertRejected("every event type", edit(good, "typeViewFocused", "typeAllMask"), "attribute android:accessibilityEventTypes")
        assertRejected("another feedback type", edit(good, "feedbackGeneric", "feedbackSpoken"), "attribute android:accessibilityFeedbackType")
        assertRejected("the window capability switched off", edit(good, "canRetrieveWindowContent=\"true\"", "canRetrieveWindowContent=\"false\""), "attribute android:canRetrieveWindowContent is \"false\"")
        assertRejected("a literal description", edit(good, "@string/commit_accessibility_service_description", "Does things"), "attribute android:description")
        assertRejected("another namespace", edit(good, "http://schemas.android.com/apk/res/android", "http://example.invalid/ns"), "attribute xmlns:android")
    }

    @Test
    fun `an extra capability or setting attribute is rejected by name`() {
        val extras: List<String> = listOf(
            "android:canPerformGestures=\"true\"",
            "android:canRequestTouchExplorationMode=\"true\"",
            "android:canRequestFilterKeyEvents=\"true\"",
            "android:canRequestEnhancedWebAccessibility=\"true\"",
            "android:canControlMagnification=\"true\"",
            "android:canTakeScreenshot=\"true\"",
            "android:accessibilityFlags=\"flagRequestFilterKeyEvents\"",
            "android:packageNames=\"com.example.app\"",
            "android:notificationTimeout=\"100\"",
            "android:settingsActivity=\"com.example.Settings\"",
            "android:isAccessibilityTool=\"true\"",
            "android:interactiveUiTimeout=\"1000\"",
        )
        for (extra in extras) {
            val name: String = extra.substringBefore("=")
            assertRejected(
                "the extra attribute $name",
                edit(good, "android:canRetrieveWindowContent=\"true\"", "android:canRetrieveWindowContent=\"true\" $extra"),
                "has the attribute $name",
            )
        }
    }

    @Test
    fun `a config with a missing attribute, a child element or another root is rejected`() {
        assertRejected(
            "no description",
            edit(good, "\n    android:description=\"@string/commit_accessibility_service_description\" />", " />"),
            "lacks the attribute android:description",
        )
        assertRejected("no event type", edit(good, "\n    android:accessibilityEventTypes=\"typeViewFocused\"", ""), "lacks the attribute android:accessibilityEventTypes")
        assertRejected(
            "a child element",
            edit(good, "description\" />", "description\"><meta-data android:name=\"x\" /></accessibility-service>"),
            "holds the element <meta-data>",
        )
        assertRejected("another root element", edit(good, "<accessibility-service", "<other"), "is <other>, expected <accessibility-service>")
        assertRejected("a second namespace declaration", edit(good, "<accessibility-service", "<accessibility-service xmlns:tools=\"http://schemas.android.com/tools\""), "has the attribute xmlns:tools")
    }

    @Test
    fun `text that is not safe or not well formed XML is refused, not passed`() {
        val broken: List<String> = listOf(
            "",
            "   \n",
            "not xml",
            "<accessibility-service",
            "<accessibility-service android:a=\"1\" android:a=\"2\" />",
            "<?xml version=\"1.0\"?><!DOCTYPE accessibility-service [<!ENTITY x SYSTEM \"file:///x\">]><accessibility-service />",
        )
        for (xml in broken) {
            assertThrows(
                "commit/accessibility: the config gate passed text that is not safe XML: $xml",
                IllegalStateException::class.java,
            ) { configProblems(xml) }
        }
    }

    @Test
    fun `the real strings file holds exactly the two strings and the description keeps its claims`() {
        val strings: String? = SourceFiles.mainFileOrNull("res/values/strings.xml")
        assertTrue("commit/accessibility: src/main/res/values/strings.xml is missing or empty", !strings.isNullOrBlank())
        val problems: List<String> = stringsProblems(strings ?: "")
        assertEquals("commit/accessibility: strings.xml differs from its rules: " + problems, emptyList<String>(), problems)
    }

    @Test
    fun `the good strings text passes, also in capitals, over several lines, at 300 characters and with an escaped apostrophe`() {
        assertTrue("commit/accessibility: the sample description is already over 300 characters", goodDescription.length <= 300)
        val samples: Map<String, String> = mapOf(
            "the exact good text" to goodDescription,
            "capitals" to goodDescription.uppercase(),
            "claims broken over lines" to edit(goodDescription, "acts only when you send", "acts only\n        when   you\n send"),
            "exactly 300 characters" to goodDescription + "x".repeat(300 - goodDescription.length),
            "an escaped apostrophe" to edit(goodDescription, "to place the new words", "to place what\\'s new"),
        )
        for ((label, description) in samples) {
            assertEquals("commit/accessibility: the strings gate rejected " + label, emptyList<String>(), stringsProblems(stringsXml(description)))
        }
    }

    @Test
    fun `a description that drops one of its claims is rejected and names the claim`() {
        val phrases: List<String> = listOf(
            "put the words you dictate into the field you are typing in", "acts only when you send",
            "finds that field and reads only its text", "never fills a password field",
            "keeps nothing from your screen", "sends nothing off the phone",
        )
        assertEquals("commit/accessibility: the strings gate no longer checks exactly these claims", phrases, claims)
        for (claim in phrases) {
            assertEquals(
                "commit/accessibility: the strings gate accepted a description without \"" + claim + "\"",
                listOf("the description does not say \"" + claim + "\""),
                stringsProblems(stringsXml(edit(goodDescription, claim, ""))),
            )
        }
    }

    @Test
    fun `a strings file with another string set, another label or a broken description is rejected`() {
        val shapes: Map<String, Pair<String, String>> = mapOf(
            "a third string" to Pair(stringsXml(goodDescription, extra = "    <string name=\"extra\">x</string>\n"), "the string names are"),
            "no label" to Pair(stringsXml(goodDescription, label = null), "the string names are"),
            "a repeated name" to Pair(stringsXml(goodDescription, extra = "    <string name=\"" + labelName + "\">Breaker</string>\n"), "the string names are"),
            "another element" to Pair(stringsXml(goodDescription, extra = "    <plurals name=\"p\" />\n"), "holds the element <plurals>"),
            "another label text" to Pair(stringsXml(goodDescription, label = "Other"), "the label is \"Other\""),
            "301 characters" to Pair(stringsXml(goodDescription + "x".repeat(301 - goodDescription.length)), "more than 300"),
            "a letter outside ASCII" to Pair(stringsXml(goodDescription + Char(233)), "outside ASCII"),
            "the first non-ASCII code" to Pair(stringsXml(goodDescription + Char(128)), "outside ASCII"),
            "an apostrophe" to Pair(stringsXml(edit(goodDescription, "to place the new words", "to place what's new")), "apostrophe without a backslash"),
            "an apostrophe as an entity" to Pair(stringsXml(edit(goodDescription, "to place the new words", "to place what&apos;s new")), "apostrophe without a backslash"),
            "an apostrophe at the start" to Pair(stringsXml("'" + goodDescription), "apostrophe without a backslash"),
            "another root" to Pair(stringsXml(goodDescription).replace("resources", "other"), "the root is <other>"),
        )
        for ((label, shape) in shapes) {
            val problems: List<String> = stringsProblems(shape.first)
            assertTrue("commit/accessibility: the strings gate accepted " + label, problems.isNotEmpty())
            assertTrue(
                "commit/accessibility: the strings gate did not say \"" + shape.second + "\" for " + label + ", it said " + problems,
                problems.any { it.contains(shape.second) },
            )
        }
    }

    @Test
    fun `strings text that is not well formed XML is refused, not passed`() {
        for (xml in listOf("", "not xml", "<resources><string name=\"a\">x</resources>")) {
            assertThrows(
                "commit/accessibility: the strings gate passed text that is not XML: " + xml,
                IllegalStateException::class.java,
            ) { stringsProblems(xml) }
        }
    }
}
