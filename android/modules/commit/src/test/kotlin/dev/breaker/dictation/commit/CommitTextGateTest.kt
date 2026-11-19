package dev.breaker.dictation.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins where the commit module's user visible wording lives: `CommitService.kt` holds no string
 * literal of its own, so a new sentence has to be a constant in `CommitTexts.kt`; the refusal
 * detail is read from `CommitTexts.COPIED_AFTER_REFUSAL`; and that constant's text is unchanged.
 *
 * The scanner blanks every comment and every string literal, so a quote left in the stripped code
 * of `CommitService.kt` is a literal in the source. The two rules that pin a literal's own text
 * read the raw file instead, which is why they carry `raw = true`.
 *
 * Every rule says which text it reads in its comment. Each rule holds on the real files, is
 * broken by at least two edited samples, and stays quiet on a harmless edit; an edit whose
 * target text is missing or occurs twice fails by name.
 */
internal class CommitTextGateTest {

    /** The constants file every sentence must live in. */
    private val texts: String = "CommitTexts.kt"

    /** The service that must hold no wording of its own. */
    private val service: String = "CommitService.kt"

    private class Rule(
        val name: String,
        val pattern: String,
        val times: Int = 1,
        val file: String? = null,
        /** True when the rule reads the literal text too, not only the comment free code. */
        val raw: Boolean = false,
    ) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
        fun file(): String = file ?: "SERVICE"
    }

    private class Sample(val rule: String, val old: String, val new: String)

    private class Quiet(val file: String, val old: String, val new: String)

    /**
     * R1 reads [service] stripped: a double quote in the stripped code is a string literal in the
     * source, and there are none. R2 reads [texts] raw: the exact `COPIED_AFTER_REFUSAL` line.
     * R3 reads [service] stripped: the detail is `CommitTexts.COPIED_AFTER_REFUSAL`.
     */
    private val rules: List<Rule> = listOf(
        Rule(
            name = "COMMIT_SERVICE_HOLDS_NO_INLINE_WORDING",
            pattern = "\"",
            times = 0,
            file = "SERVICE",
        ),
        Rule(
            name = "COPIED_AFTER_REFUSAL_IS_UNCHANGED",
            pattern = "const val COPIED_AFTER_REFUSAL: String = \"The field did not accept the text, so it was copied instead\\.\"",
            file = "TEXTS",
            raw = true,
        ),
        Rule(
            name = "REFUSAL_DETAIL_COMES_FROM_COMMITTEXTS",
            pattern = """\bCommitTexts\.COPIED_AFTER_REFUSAL\b""",
            file = "SERVICE",
        ),
    )

    /** Harmless edits: comments only, each of which keeps every rule of its file true. */
    private val quiet: List<Quiet> = listOf(
        Quiet(
            "SERVICE",
            "        val detail: String? = if (refused) CommitTexts.COPIED_AFTER_REFUSAL else null",
            "        // the detail comes from the constants\n        val detail: String? = if (refused) CommitTexts.COPIED_AFTER_REFUSAL else null",
        ),
        Quiet(
            "TEXTS",
            "    /** What the user sees when the text went to the clipboard. */",
            "    // the toast text\n    /** What the user sees when the text went to the clipboard. */",
        ),
    )

    /** Each sample edits one file so that the rule it names stops holding. */
    private val firing: List<Sample> = listOf(
        Sample(
            "COMMIT_SERVICE_HOLDS_NO_INLINE_WORDING",
            "        val detail: String? = if (refused) CommitTexts.COPIED_AFTER_REFUSAL else null",
            "        val detail: String? = if (refused) CommitTexts.COPIED_AFTER_REFUSAL else null\n" +
                "        val note = \" copied\"",
        ),
        Sample(
            "COMMIT_SERVICE_HOLDS_NO_INLINE_WORDING",
            "        val detail: String? = if (refused) CommitTexts.COPIED_AFTER_REFUSAL else null",
            "        val detail: String? = if (refused) \"The field did not accept the text, so it was copied instead.\" else null",
        ),
        Sample(
            "COPIED_AFTER_REFUSAL_IS_UNCHANGED",
            "    const val COPIED_AFTER_REFUSAL: String = \"The field did not accept the text, so it was copied instead.\"",
            "    const val COPIED_AFTER_REFUSAL: String = \"The field refused the text, so it was copied.\"",
        ),
        Sample(
            "COPIED_AFTER_REFUSAL_IS_UNCHANGED",
            "    const val COPIED_AFTER_REFUSAL: String = \"The field did not accept the text, so it was copied instead.\"",
            "",
        ),
        Sample(
            "REFUSAL_DETAIL_COMES_FROM_COMMITTEXTS",
            "        val detail: String? = if (refused) CommitTexts.COPIED_AFTER_REFUSAL else null",
            "        val detail: String? = if (refused) CommitTexts.FAILED_NOWHERE else null",
        ),
        Sample(
            "REFUSAL_DETAIL_COMES_FROM_COMMITTEXTS",
            "        val detail: String? = if (refused) CommitTexts.COPIED_AFTER_REFUSAL else null",
            "        val detail: String? = null",
        ),
    )

    /** [old] replaced by [new] once. Fails by name when the text is missing or occurs twice. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "commit: a sample lost its text '$old'" }
        check(text.indexOf(old, at + 1) < 0) { "commit: a sample text occurs twice '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** The text a rule reads, by key. */
    private fun textOf(key: String): String = when (key) {
        "TEXTS" -> SourceFiles.mainSources()[texts] ?: error("commit: the main source $texts is missing")
        "SERVICE" -> SourceFiles.mainSources()[service] ?: error("commit: the main source $service is missing")
        else -> error("commit: no source named $key")
    }

    /** The code a rule reads, with [old] replaced by [new] once; raw rules keep the literal text. */
    private fun codeOf(rule: Rule, old: String? = null, new: String = ""): String {
        val text = textOf(rule.file())
        val edited = if (old == null) text else edit(text, old, new)
        return if (rule.raw) edited else SourceFiles.strip(edited).code
    }

    @Test
    fun `every commit text rule holds on the real constants`() {
        val broken = rules.filter { rule -> !rule.holds(codeOf(rule)) }.map { it.name }
        assertTrue("commit: the constants break rules $broken", broken.isEmpty())
    }

    @Test
    fun `the commit text rules fire on their edited samples`() {
        val byName = rules.associateBy { it.name }
        val unfired = firing.filter { sample ->
            val rule = byName.getValue(sample.rule)
            rule.holds(codeOf(rule, sample.old, sample.new))
        }.map { "${it.rule}: '${it.old}' => '${it.new}'" }
        assertTrue("commit: samples that leave their rule true: $unfired", unfired.isEmpty())
    }

    @Test
    fun `the commit text samples cover each rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("commit: a rule name is used twice", names.toSet().size, names.size)
        val unknown = firing.map { it.rule }.filter { it !in names }
        assertTrue("commit: samples of unknown rules: $unknown", unknown.isEmpty())
        val thin = names.filter { name -> firing.count { it.rule == name } < 2 }
        assertTrue("commit: rules with fewer than two firing samples: $thin", thin.isEmpty())
    }

    @Test
    fun `no commit text rule fires on a harmless edit`() {
        val broken = quiet.flatMap { q ->
            val edited = edit(textOf(q.file), q.old, q.new)
            rules.filter { it.file() == q.file }.filter { rule ->
                !rule.holds(if (rule.raw) edited else SourceFiles.strip(edited).code)
            }.map { "${it.name} on '${q.old}'" }
        }
        assertTrue("commit: a harmless edit broke a rule: $broken", broken.isEmpty())
    }
}
