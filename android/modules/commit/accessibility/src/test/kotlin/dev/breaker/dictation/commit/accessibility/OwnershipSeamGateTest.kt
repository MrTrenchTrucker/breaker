package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ownership seam of the focused field: the window member is declared once in each of
 * the two files that declare it, the name windowId belongs to exactly the three files of the
 * seam, [NodeFocusedField] keeps no node or window handle and names no platform node or window
 * type, and the take-start comparison sits after the password, editable and enabled guards.
 *
 * Every rule says which text it reads in its comment. Each rule holds on the real files, is
 * broken by at least two edited samples, and stays quiet on a harmless edit; an edit whose
 * target text is missing or occurs twice fails by name. The platform prefix in R3 stands for
 * the node and window types; this file names no platform type.
 */
internal class OwnershipSeamGateTest {

    /** The port, its one implementation, and the class that reads through the port. */
    private val fieldNode: String = "FieldNode.kt"
    private val adapterFieldNode: String = "adapter/AndroidFieldNode.kt"
    private val nodeFocusedField: String = "NodeFocusedField.kt"

    private class Rule(
        val name: String,
        val pattern: String,
        val times: Int = 1,
        val file: String,
    ) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
    }

    private class Sample(val rule: String, val file: String, val old: String, val new: String)

    private class Quiet(val file: String, val old: String, val new: String)

    /**
     * R1 reads the comment free code of the two member files joined: the exact two window member
     * declarations. R2 reads one line per main source, its path then its comment free code with
     * the newlines flattened: exactly three lines name windowId. R3 reads the comment free code
     * of [nodeFocusedField]: no stored property of the node port, the window value or a platform
     * node or window type. R4 reads the comment free code of [nodeFocusedField]: the guard reads
     * run before the take-start comparison.
     */
    private val rules: List<Rule> = listOf(
        Rule(
            name = "WINDOW_MEMBER_DECLARED_ONCE_EACH",
            pattern = """\bval\s+windowId\s*:\s*Int\s*\?""",
            times = 2,
            file = "WINDOW_MEMBER_BOTH",
        ),
        Rule(
            name = "WINDOW_NAMED_ONLY_IN_THREE_FILES",
            pattern = """(?m)^[^\n:]+ :: [^\n]*\bwindowId\b""",
            times = 3,
            file = "MAIN_INDEX",
        ),
        Rule(
            name = "NO_NODE_OR_WINDOW_HANDLE_STORED",
            pattern = """(?m)^    (?:private\s+)?(?:var|val)\s+\w+\s*:\s*(?:FieldNode|TakeStartWindow|Accessibility)""",
            times = 0,
            file = nodeFocusedField,
        ),
        Rule(
            name = "COMPARISON_SITS_AFTER_THE_FLAGS",
            pattern = """(?s)node\.isPassword\b.*?node\.isEditable\b.*?node\.isEnabled\b.*?anchor\.windowId""",
            times = 1,
            file = nodeFocusedField,
        ),
    )

    /** Harmless edits: a comment and a blank line, each of which keeps every rule true. */
    private val quiet: List<Quiet> = listOf(
        Quiet(
            nodeFocusedField,
            "    /** Hand the node back; a node that fails to go back cannot change what was already decided. */",
            "    // the node goes back every time\n" +
                "    /** Hand the node back; a node that fails to go back cannot change what was already decided. */",
        ),
        Quiet(
            fieldNode,
            "    /** True for a password field. An insert must refuse it. Never log or store it. */",
            "\n    /** True for a password field. An insert must refuse it. Never log or store it. */",
        ),
    )

    /** Each sample edits one file so that the rule it names stops holding. */
    private val firing: List<Sample> = listOf(
        Sample(
            "WINDOW_MEMBER_DECLARED_ONCE_EACH",
            fieldNode,
            "    val windowId: Int?",
            "",
        ),
        Sample(
            "WINDOW_MEMBER_DECLARED_ONCE_EACH",
            adapterFieldNode,
            "    override val windowId: Int?",
            "    override val windowId: Int?\n    override val windowId: Int?",
        ),
        Sample(
            "WINDOW_NAMED_ONLY_IN_THREE_FILES",
            "InsertPlan.kt",
            "internal object InsertPlan {",
            "internal object InsertPlan { val windowId: Int? = 0",
        ),
        Sample(
            "WINDOW_NAMED_ONLY_IN_THREE_FILES",
            fieldNode,
            "    val windowId: Int?",
            "",
        ),
        Sample(
            "NO_NODE_OR_WINDOW_HANDLE_STORED",
            nodeFocusedField,
            ") : FocusedField {",
            ") : FocusedField {\n    private var kept: FieldNode? = null",
        ),
        Sample(
            "NO_NODE_OR_WINDOW_HANDLE_STORED",
            nodeFocusedField,
            ") : FocusedField {",
            ") : FocusedField {\n    private val kept: TakeStartWindow? = null",
        ),
        Sample(
            "COMPARISON_SITS_AFTER_THE_FLAGS",
            nodeFocusedField,
            "        val isPassword: Boolean = node.isPassword\n" +
                "        if (isPassword) return FieldCommit.REFUSED\n" +
                "        val isEditable: Boolean = node.isEditable\n" +
                "        if (!isEditable) return FieldCommit.REFUSED\n" +
                "        val isEnabled: Boolean = node.isEnabled\n" +
                "        if (!isEnabled) return FieldCommit.REFUSED\n" +
                "\n" +
                "        // The take-start window, when a take was captured. Short-circuit: with no anchor the\n" +
                "        // node's window is not read at all, and with an anchor it is read at most once.\n" +
                "        val anchor: TakeStartWindow? = TakeStartAnchor.current()\n" +
                "        if (anchor != null && (anchor.windowId == null ||\n" +
                "            anchor.windowId != node.windowId || anchor.packageName != node.packageName)) {\n" +
                "            return FieldCommit.NO_FIELD\n" +
                "        }\n",
            "        // The take-start window, when a take was captured. Short-circuit: with no anchor the\n" +
                "        // node's window is not read at all, and with an anchor it is read at most once.\n" +
                "        val anchor: TakeStartWindow? = TakeStartAnchor.current()\n" +
                "        if (anchor != null && (anchor.windowId == null ||\n" +
                "            anchor.windowId != node.windowId || anchor.packageName != node.packageName)) {\n" +
                "            return FieldCommit.NO_FIELD\n" +
                "        }\n" +
                "        val isPassword: Boolean = node.isPassword\n" +
                "        if (isPassword) return FieldCommit.REFUSED\n" +
                "        val isEditable: Boolean = node.isEditable\n" +
                "        if (!isEditable) return FieldCommit.REFUSED\n" +
                "        val isEnabled: Boolean = node.isEnabled\n" +
                "        if (!isEnabled) return FieldCommit.REFUSED\n",
        ),
        Sample(
            "COMPARISON_SITS_AFTER_THE_FLAGS",
            nodeFocusedField,
            "        val isEnabled: Boolean = node.isEnabled\n        if (!isEnabled) return FieldCommit.REFUSED\n",
            "",
        ),
    )

    /** [old] replaced by [new] once. Fails by name when the text is missing or occurs twice. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "commit/accessibility: a sample lost its text '$old'" }
        check(text.indexOf(old, at + 1) < 0) { "commit/accessibility: a sample text occurs twice '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** The text a rule reads, by key, with [overrides] replacing the named real files. */
    private fun textOf(key: String, overrides: Map<String, String> = emptyMap()): String {
        val sources = SourceFiles.mainSources()
        fun one(path: String): String = overrides[path]
            ?: sources[path]
            ?: error("commit/accessibility: no main source named $path")
        return when (key) {
            "WINDOW_MEMBER_BOTH" -> one(fieldNode) + "\n" + one(adapterFieldNode)
            "MAIN_INDEX" -> (sources.keys + overrides.keys).sorted().joinToString("\n") { path ->
                path + " :: " + SourceFiles.strip(one(path)).code.replace('\n', ' ')
            }
            else -> one(key)
        }
    }

    /** The code a rule reads, with [overrides] replacing the named real files. */
    private fun codeOf(rule: Rule, overrides: Map<String, String> = emptyMap()): String =
        SourceFiles.strip(textOf(rule.file, overrides)).code

    @Test
    fun `every ownership seam rule holds on the real files`() {
        val broken = rules.filter { rule -> !rule.holds(codeOf(rule)) }.map { it.name }
        assertTrue("commit/accessibility: the seam breaks rules $broken", broken.isEmpty())
    }

    @Test
    fun `the ownership seam rules fire on their edited samples`() {
        val byName = rules.associateBy { it.name }
        val unfired = firing.filter { sample ->
            val rule = byName.getValue(sample.rule)
            val edited = edit(textOf(sample.file), sample.old, sample.new)
            rule.holds(codeOf(rule, mapOf(sample.file to edited)))
        }.map { "${it.rule}: '${it.old}' => '${it.new}'" }
        assertTrue("commit/accessibility: samples that leave their rule true: $unfired", unfired.isEmpty())
    }

    @Test
    fun `the ownership seam samples cover each rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("commit/accessibility: a rule name is used twice", names.toSet().size, names.size)
        val unknown = firing.map { it.rule }.filter { it !in names }
        assertTrue("commit/accessibility: samples of unknown rules: $unknown", unknown.isEmpty())
        val thin = names.filter { name -> firing.count { it.rule == name } < 2 }
        assertTrue("commit/accessibility: rules with fewer than two firing samples: $thin", thin.isEmpty())
    }

    @Test
    fun `no ownership seam rule fires on a harmless edit`() {
        val broken = quiet.flatMap { q ->
            val edited = edit(textOf(q.file), q.old, q.new)
            rules.filter { rule -> !rule.holds(codeOf(rule, mapOf(q.file to edited))) }.map { it.name }
        }
        assertTrue("commit/accessibility: a harmless edit broke a rule: $broken", broken.isEmpty())
    }
}
