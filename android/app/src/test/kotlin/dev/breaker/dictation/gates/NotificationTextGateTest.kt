package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words of the service notification are four short, plain, ASCII texts.
 *
 * They are shown to the person using the phone, so they are plain words: no technical
 * term, no placeholder, no digits-only line, nothing that needs an escape the file does
 * not have, each short enough for a notification, and the title is not the line under
 * it. The exact words are not pinned here, because they are meant to be edited; the
 * names and the shape are. The file is parsed as XML, so comments are not content.
 */
internal class NotificationTextGateTest {

    private val names: List<String> = listOf(
        "dictation_channel_name",
        "dictation_notification_title",
        "dictation_notification_text",
        "dictation_action_off",
    )
    private val titleName: String = "dictation_notification_title"
    private val textName: String = "dictation_notification_text"

    /** The banned word is written in two pieces so that this file does not hold it; samples carry [token] where it goes. */
    private val word: String = "inj" + "ection"
    private val token: String = "#W#"

    private val forbidden: Regex = Regex(
        """\b(?:accessibility|overlay|foreground|service|permission|debug)\w*|\b(?:api|sdk)s?\b|\b""" + word + """\w*""",
        RegexOption.IGNORE_CASE,
    )
    private val placeholderWords: Regex = Regex("""\b(?:todo|tbd|fixme|lorem|ipsum|xxx+|placeholder)\b""", RegexOption.IGNORE_CASE)

    private val good: String = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="dictation_channel_name">Dictation</string>
    <string name="dictation_notification_title">Breaker is on</string>
    <string name="dictation_notification_text">Tap the tile to start dictating.</string>
    <string name="dictation_action_off">Switch off</string>
</resources>
"""

    /** One line per way [xml] breaks the rules; none means it matches. Throws on text that is not XML. */
    private fun stringsProblems(xml: String): List<String> {
        val root: XmlNode = XmlTree.parse(xml)
        val problems: MutableList<String> = ArrayList()
        if (root.name != "resources") problems.add("the root is <${root.name}>, expected <resources>")
        for (key in root.attributes.keys.sorted()) problems.add("the root has the attribute $key, which is not allowed")
        if (root.text.isNotBlank()) problems.add("the root holds character data, which is not allowed")
        for (other in root.children.filter { it.name != "string" }) {
            problems.add("holds the element <${other.name}>, which is not allowed")
        }
        val entries: List<XmlNode> = root.children.filter { it.name == "string" }
        val found: List<String> = entries.map { it.attributes["name"] ?: "" }.sorted()
        if (found != names.sorted()) problems.add("the string names are $found, expected exactly ${names.sorted()}")
        for (entry in entries) {
            val id: String = entry.attributes["name"] ?: ""
            for (key in entry.attributes.keys.sorted().filter { it != "name" }) {
                problems.add("$id has the attribute $key, which is not allowed")
            }
            if (entry.children.isNotEmpty()) problems.add("$id holds an element, which is not allowed")
            problems.addAll(textProblems(id, entry.text))
        }
        val title: String? = entries.firstOrNull { it.attributes["name"] == titleName }?.text
        val text: String? = entries.firstOrNull { it.attributes["name"] == textName }?.text
        if (title != null && title == text) problems.add("the title and the text are the same string")
        return problems
    }

    private fun textProblems(id: String, text: String): List<String> {
        val problems: MutableList<String> = ArrayList()
        if (text.isBlank()) {
            problems.add("$id is empty")
            return problems
        }
        if (text != text.trim()) problems.add("$id has white space at an end")
        if (text.any { it.code > 127 }) problems.add("$id holds a character outside ASCII")
        if (text.indices.any { text[it] == '\'' && (it == 0 || text[it - 1] != '\\') }) {
            problems.add("$id has an apostrophe without a backslash")
        }
        if (text.indices.any { text[it] == '"' && (it == 0 || text[it - 1] != '\\') }) {
            problems.add("$id has a double quote without a backslash")
        }
        if (text.length > 60) problems.add("$id is ${text.length} characters, more than 60")
        if (text.none { it.isLetter() }) problems.add("$id has no letters, so it is not words")
        if (text.any { it in "%{}\$_" } || placeholderWords.containsMatchIn(text)) problems.add("$id looks like placeholder text")
        for (match in forbidden.findAll(text)) {
            problems.add("$id uses the word \"${match.value}\", which is not a plain word for a user")
        }
        return problems
    }

    /** [sample] with the token replaced by the banned word in its edit and in what the gate must say. */
    private fun resolved(sample: XmlSample): XmlSample = XmlSample(
        sample.label,
        sample.from,
        sample.to.replace(token, word),
        *sample.fragments.map { it.replace(token, word) }.toTypedArray(),
    )

    private fun assertRejected(label: String, xml: String, fragments: List<String>) {
        val problems: List<String> = stringsProblems(xml)
        assertTrue("app: the notification text gate accepted $label", problems.isNotEmpty())
        for (fragment in fragments) {
            assertTrue(
                "app: the notification text gate did not say \"$fragment\" for $label, it said $problems",
                problems.any { it.contains(fragment) },
            )
        }
    }

    private val firing: List<XmlSample> = listOf(
        XmlSample("a name missing", "    <string name=\"dictation_action_off\">Switch off</string>\n", "", "the string names are"),
        XmlSample("a name renamed", "dictation_action_off", "dictation_action_stop", "the string names are"),
        XmlSample("an extra string", "</resources>", "<string name=\"extra\">Hi</string>\n</resources>", "the string names are"),
        XmlSample("a duplicated string", "</resources>", "<string name=\"dictation_action_off\">Again</string>\n</resources>", "the string names are"),
        XmlSample("another kind of entry", "</resources>", "<color name=\"c\">#fff</color>\n</resources>", "holds the element <color>"),
        XmlSample("a string array", "</resources>", "<string-array name=\"a\" />\n</resources>", "holds the element <string-array>"),
        XmlSample("an attribute on a string", "<string name=\"dictation_action_off\">", "<string name=\"dictation_action_off\" translatable=\"false\">", "dictation_action_off has the attribute translatable"),
        XmlSample("an attribute on the root", "<resources>", "<resources xmlns:tools=\"http://schemas.android.com/tools\">", "the root has the attribute xmlns:tools"),
        XmlSample("an element inside a string", ">Switch off<", "><b>Switch</b> off<", "dictation_action_off holds an element"),
        XmlSample("an empty text", ">Switch off<", "><", "dictation_action_off is empty"),
        XmlSample("a blank text", ">Switch off<", "> <", "dictation_action_off is empty"),
        XmlSample("white space at the end", ">Switch off<", ">Switch off <", "dictation_action_off has white space at an end"),
        XmlSample("an apostrophe without a backslash", ">Switch off<", ">Don't switch<", "dictation_action_off has an apostrophe without a backslash"),
        XmlSample("a quote without a backslash", ">Switch off<", ">Say \"off\"<", "dictation_action_off has a double quote without a backslash"),
        XmlSample("a text of 61 characters", ">Tap the tile to start dictating.<", ">Tap the tile to start dictating, then tap this tile to send..<", "dictation_notification_text is 61 characters, more than 60"),
        XmlSample("digits only", ">Switch off<", ">12345<", "dictation_action_off has no letters"),
        XmlSample("symbols only", ">Switch off<", ">...<", "dictation_action_off has no letters"),
        XmlSample("a format placeholder", ">Switch off<", ">Switch %d off<", "dictation_action_off looks like placeholder text"),
        XmlSample("a brace placeholder", ">Switch off<", ">Switch {name} off<", "dictation_action_off looks like placeholder text"),
        XmlSample("a TODO text", ">Switch off<", ">TODO<", "dictation_action_off looks like placeholder text"),
        XmlSample("a resource name as the text", ">Switch off<", ">dictation_action_off<", "dictation_action_off looks like placeholder text"),
        XmlSample("a lorem text", ">Switch off<", ">Lorem ipsum<", "dictation_action_off looks like placeholder text"),
        XmlSample("the word accessibility", ">Breaker is on<", ">Accessibility is on<", "uses the word \"Accessibility\""),
        XmlSample("the word overlay", ">Breaker is on<", ">The overlay is on<", "uses the word \"overlay\""),
        XmlSample("the word foreground", ">Breaker is on<", ">Foreground on<", "uses the word \"Foreground\""),
        XmlSample("the word service", ">Breaker is on<", ">Service is running<", "uses the word \"Service\""),
        XmlSample("the word permission", ">Breaker is on<", ">Needs permission<", "uses the word \"permission\""),
        XmlSample("the word permissions", ">Breaker is on<", ">Permissions needed<", "uses the word \"Permissions\""),
        XmlSample("the word API", ">Breaker is on<", ">API is ready<", "uses the word \"API\""),
        XmlSample("the word SDK", ">Breaker is on<", ">SDK ready<", "uses the word \"SDK\""),
        XmlSample("the banned word", ">Breaker is on<", ">Text #W#<", "uses the word \"#W#\""),
        XmlSample("the word debug", ">Breaker is on<", ">Debug mode<", "uses the word \"Debug\""),
        XmlSample("a forbidden word in capitals", ">Switch off<", ">TURN THE SERVICE OFF<", "uses the word \"SERVICE\""),
        XmlSample("the title and the text the same", ">Tap the tile to start dictating.<", ">Breaker is on<", "the title and the text are the same string"),
    )

    private val quiet: List<XmlSample> = listOf(
        XmlSample("a comment that names forbidden things", "</resources>", "<!-- accessibility overlay foreground service permission API SDK debug %d TODO -->\n</resources>"),
        XmlSample("an escaped apostrophe", ">Switch off<", ">Don\\'t switch<"),
        XmlSample("escaped quotes", ">Switch off<", ">Say \\\"off\\\"<"),
        XmlSample("a text of exactly 60 characters", ">Tap the tile to start dictating.<", ">Tap the tile to start dictating, then tap this tile to send.<"),
        XmlSample("words that only hold a forbidden one inside", ">Breaker is on<", ">Capital debt and overseas<"),
        XmlSample("a digit among words", ">Switch off<", ">Switch off in 5 minutes<"),
        XmlSample("the strings in another order", "    <string name=\"dictation_channel_name\">Dictation</string>\n    <string name=\"dictation_notification_title\">Breaker is on</string>", "    <string name=\"dictation_notification_title\">Breaker is on</string>\n    <string name=\"dictation_channel_name\">Dictation</string>"),
        XmlSample("no declaration line", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n", ""),
    )

    @Test
    fun `the real strings file holds exactly the four notification texts and they are plain`() {
        val text: String = AppSourceFiles.mainFile("res/values/strings.xml")
        assertTrue("app: src/main/res/values/strings.xml is empty", text.isNotBlank())
        val problems: List<String> = stringsProblems(text)
        assertEquals("app: the strings file breaks its rules: $problems", emptyList<String>(), problems)
    }

    @Test
    fun `the exact good strings pass, with comments that name forbidden words, an escaped apostrophe and 60 characters`() {
        assertEquals("app: the exact good strings were rejected", emptyList<String>(), stringsProblems(good))
        for (sample in quiet) {
            val xml: String = XmlSamples.apply("strings", good, sample)
            assertEquals("app: the notification text gate flagged ${sample.label}", emptyList<String>(), stringsProblems(xml))
        }
    }

    @Test
    fun `strings that break a rule are rejected with what is wrong`() {
        for (sample in firing) {
            val use: XmlSample = resolved(sample)
            assertRejected(use.label, XmlSamples.apply("strings", good, use), use.fragments)
        }
        assertRejected("another root element", "<strings />", listOf("the root is <strings>, expected <resources>"))
    }

    @Test
    fun `a character outside ASCII is rejected`() {
        val accented: String = Char(233).toString()
        val xml: String = XmlSamples.edit("strings", "a non-ASCII text", good, ">Switch off<", ">Switch " + accented + "<")
        assertRejected("a character outside ASCII", xml, listOf("dictation_action_off holds a character outside ASCII"))
    }

    @Test
    fun `text that is not safe or not well formed XML is refused, not passed`() {
        val broken: List<String> = listOf(
            "",
            "not xml",
            "<resources>",
            "<resources><string name=\"a\" name=\"b\">x</string></resources>",
            "<?xml version=\"1.0\"?><!DOCTYPE resources [<!ENTITY x \"y\">]><resources />",
        )
        for (xml in broken) {
            val refused: Boolean = try {
                stringsProblems(xml)
                false
            } catch (e: IllegalStateException) {
                true
            }
            assertTrue("app: the notification text gate passed text that is not safe XML: $xml", refused)
        }
    }
}
