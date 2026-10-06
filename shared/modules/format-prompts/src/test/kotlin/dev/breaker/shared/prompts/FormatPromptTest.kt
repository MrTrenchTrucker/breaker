package dev.breaker.shared.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The prompt's contract, and the file's conformance to it.
 *
 * Two things are being protected here. The first is that the shipped wording
 * says what it must: preserve the facts, add punctuation and casing, convert
 * spoken enumerations, add and remove nothing, return text only. The second is
 * that it is sent with parameters that cannot let the model rewrite — zero
 * temperature, no structured output — and with a token budget that grows with
 * the input instead of truncating it.
 */
class FormatPromptTest {

    private val repoRoot: File = File(
        System.getProperty("breaker.repoRoot")
            ?: error("breaker.repoRoot is not set; run these tests through Gradle"),
    )

    private val promptFile: File get() = File(repoRoot, PROMPT_PATH)

    private fun bail(message: String): Nothing = throw AssertionError(message)

    // ── the shipped prompt ───────────────────────────────────────────────

    @Test
    fun the_shipped_prompt_loads_and_says_what_it_must() {
        val prompt = PromptCatalog.load(PromptCatalog.CURRENT)
        assertEquals(PromptCatalog.CURRENT, prompt.id)
        assertEquals(PromptCatalog.CURRENT, prompt.version)
        assertTrue(
            "the prompt must forbid inventing content: ${prompt.systemPrompt}",
            prompt.preservesContent(),
        )
        // The clauses are matched with their wrapping collapsed, for the same
        // reason the constructor matches them that way: a clause is a phrase,
        // and a line break between two of its words is not a change to it. The
        // prompt is markdown and markdown is hard-wrapped, so
        // `FormatPrompt.kt:41` reads `remove\nfiller words` here. Checking the
        // raw text instead would fail on the wrapping alone and send someone
        // to reword a prompt that says exactly what it must — which is the
        // opposite of what this test is for.
        val shipped = prompt.systemPrompt.folded()
        for (clause in FormatPrompt.REQUIRED_CLAUSES) {
            assertTrue(
                "the shipped prompt is missing the clause '$clause'",
                clause.folded() in shipped,
            )
        }
    }

    /**
     * The text with every run of whitespace — line breaks included — one space,
     * and lower-cased.
     *
     * Written out here rather than shared with the constructor on purpose: this
     * is a second, independent reading of the same rule. If both used one
     * helper, a bug in that helper would satisfy them both at once.
     */
    private fun String.folded(): String =
        lowercase().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * The refusal reason, not just the refusal: a refusal for the WRONG
     * reason (no section vs no fence vs unterminated) is a different bug, so
     * the refusal tests assert the message, not the exception type.
     */
    private fun refusalOf(markdown: String): String? =
        runCatching { PromptCatalog.extractSystemPrompt(markdown, "format-v1") }
            .exceptionOrNull()?.message

    @Test
    fun the_prompt_file_is_the_copy_that_is_shipped() {
        // The classpath resource and the file in the repository are one file:
        // the build points at prompts/, so a consumer cannot read a different
        // wording from the one a reader sees.
        if (!promptFile.isFile) bail("$PROMPT_PATH is missing")
        val onDisk = promptFile.readText()
        val onClasspath = checkNotNull(
            javaClass.classLoader.getResourceAsStream("${PromptCatalog.CURRENT}.md"),
        ) { "${PromptCatalog.CURRENT}.md is not on the classpath" }.bufferedReader().use { it.readText() }
        assertEquals(
            "the prompt on the classpath and the prompt in the repository must be the same bytes",
            onDisk,
            onClasspath,
        )
    }

    @Test
    fun the_file_declares_the_parameters_the_code_enforces() {
        val front = promptFile.readText().substringBefore("\n---", missingDelimiterValue = "")
        if (front.isEmpty()) bail("$PROMPT_PATH has no front matter, so it states no parameters")
        fun declared(key: String): String =
            Regex("^\\s*$key:\\s*(.+)$", RegexOption.MULTILINE).find(front)?.groupValues?.get(1)?.trim()
                ?: bail("$PROMPT_PATH states no '$key'")
        assertEquals("the file and the code must agree on temperature", "0", declared("temperature"))
        assertEquals("structured_output", "false", declared("structured_output"))
        assertEquals("json_mode", "false", declared("json_mode"))
        assertEquals("response_format", "text", declared("response_format"))
        assertEquals(
            "the file's id is the id a consumer asks for",
            PromptCatalog.CURRENT,
            declared("id"),
        )
    }

    @Test
    fun the_prompt_carries_the_worked_example_the_cards_name() {
        val text = promptFile.readText()
        assertTrue(
            "the prompt file should show the spoken-enumeration case it exists for",
            "one is file a" in text && "two is file b" in text,
        )
        assertTrue(
            "the file should state the expected output the golden tests assert",
            "1. is file a." in text,
        )
    }

    // ── the parameters ───────────────────────────────────────────────────

    @Test
    fun the_prompt_runs_at_temperature_zero_with_no_structured_output() {
        val prompt = PromptCatalog.load(PromptCatalog.CURRENT)
        assertEquals(0.0, prompt.temperature, 0.0)
        assertEquals(false, prompt.structuredOutput)
        assertEquals(false, prompt.jsonMode)
        assertEquals("text", prompt.responseFormat)
    }

    @Test
    fun a_prompt_that_could_rewrite_is_refused() {
        val good = PromptCatalog.load(PromptCatalog.CURRENT).systemPrompt
        assertTrue(
            "a non-zero temperature would let the model rewrite rather than reformat",
            runCatching {
                FormatPrompt(
                    id = "format-v9",
                    version = "format-v9",
                    systemPrompt = good,
                    temperature = 0.7,
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            "structured output invites a schema the model then has to fill",
            runCatching {
                FormatPrompt(
                    id = "format-v9",
                    version = "format-v9",
                    systemPrompt = good,
                    structuredOutput = true,
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun a_prompt_that_has_lost_a_clause_is_refused() {
        val weakened = "Reformat the user's dictation into clean text. Output text only."
        val refused = runCatching {
            FormatPrompt(id = "format-v9", version = "format-v9", systemPrompt = weakened)
        }.exceptionOrNull()
        assertTrue(
            "a prompt that dropped its non-destructive clauses must be refused at construction",
            refused is IllegalArgumentException,
        )
        assertTrue(
            "the refusal should name what is missing: $refused",
            refused?.message?.contains("preserve every fact") == true,
        )
    }

    @Test
    fun an_id_this_module_does_not_ship_is_refused() {
        val refused = runCatching { PromptCatalog.load("format-v99") }.exceptionOrNull()
        assertTrue(
            "there is no such prompt, and a caller must be told rather than given a different one",
            refused is IllegalArgumentException,
        )
        assertTrue(
            "the refusal should list what exists: $refused",
            refused?.message?.contains(PromptCatalog.CURRENT) == true,
        )
    }

    @Test
    fun a_fenced_block_outside_the_system_prompt_section_is_not_the_prompt() {
        // A fence above the section and two fences in later sections must
        // not be mistaken for the system prompt: the search is bound to the
        // "## System prompt" section, first fence inside it.
        val markdown =
            "Some prose with a fenced example first:\n" +
            "\n" +
            "```text\n" +
            "this block is prose, not the prompt\n" +
            "```\n" +
            "\n" +
            "## System prompt\n" +
            "\n" +
            "Send this verbatim:\n" +
            "\n" +
            "```text\n" +
            "the real prompt\n" +
            "```\n" +
            "\n" +
            "## Worked example\n" +
            "\n" +
            "```text\n" +
            "an example input\n" +
            "```\n"
        val extracted = PromptCatalog.extractSystemPrompt(markdown, "format-v1")
        assertEquals(
            "only the fenced block inside the section is the prompt",
            "the real prompt",
            extracted,
        )
    }

    @Test
    fun a_missing_section_fence_is_refused_even_with_fences_elsewhere() {
        // No fence in the section, but fenced blocks in a LATER section:
        // the search must stay inside the section bound and fail, not pick
        // a block that belongs to something else.
        val markdown =
            "## System prompt\n" +
            "\n" +
            "Prose only, no fence here.\n" +
            "\n" +
            "## Worked example\n" +
            "\n" +
            "```text\n" +
            "a fence in a later section\n" +
            "```\n"
        val reason = refusalOf(markdown)
        assertTrue(
            "a section with no fenced prompt must be refused as having no " +
                "fenced prompt, not for some other reason: $reason",
            reason?.contains("no fenced system prompt") == true,
        )
    }

    @Test
    fun an_earlier_subheading_and_prose_mention_are_not_the_system_prompt_section() {
        // A "### System prompt notes" sub-heading above the real section, with
        // its own fenced block, must not be taken for the section: the section
        // is the line that IS "## System prompt", not a substring of a line.
        val markdown = listOf(
            "Notes before the prompt.",
            "",
            "### System prompt notes",
            "",
            "```text",
            "a note block, not the prompt",
            "```",
            "",
            "The System prompt section is below.",
            "",
            "## System prompt",
            "",
            "```text",
            "the real prompt",
            "```",
        ).joinToString("\n")
        assertEquals(
            "only the block under the real heading is the prompt",
            "the real prompt",
            PromptCatalog.extractSystemPrompt(markdown, "format-v1"),
        )
    }

    @Test
    fun a_prompt_line_that_looks_like_a_heading_is_content() {
        // A "## " line inside the fenced prompt is content, not a section end:
        // only the opening fence must sit inside the section, and once it
        // opens the block runs to its closing fence.
        val markdown = listOf(
            "## System prompt",
            "",
            "```text",
            "line one",
            "## Example",
            "line three",
            "```",
            "",
            "## Worked example",
        ).joinToString("\n")
        assertEquals(
            "the whole block is the prompt, the '## Example' line included",
            "line one\n## Example\nline three",
            PromptCatalog.extractSystemPrompt(markdown, "format-v1"),
        )
    }

    @Test
    fun a_prose_mention_of_the_heading_is_not_a_section() {
        // No line in the file IS the "## System prompt" heading (the phrase
        // only appears in prose), so there is no section, even though a
        // fenced block exists further down: it must be refused.
        val markdown = listOf(
            "See the ## System prompt section elsewhere.",
            "",
            "## Worked example",
            "",
            "```text",
            "an example input",
            "```",
        ).joinToString("\n")
        val reason = refusalOf(markdown)
        assertTrue(
            "a prose mention is not a heading, so the refusal must say there " +
                "is no system prompt section: $reason",
            reason?.contains("no system prompt section") == true,
        )
    }

    @Test
    fun an_opening_fence_without_a_closing_fence_is_refused() {
        // The prompt's block never closes, and a later section carries fences
        // of its own: the search must not borrow the later fence, it must
        // refuse the block as unterminated.
        val markdown = listOf(
            "## System prompt",
            "",
            "```text",
            "the prompt that never closes",
            "",
            "## Worked example",
            "",
            "```text",
            "input",
            "```",
        ).joinToString("\n")
        val reason = refusalOf(markdown)
        assertTrue(
            "an unclosed block must be refused as unterminated, not cut at a " +
                "later section: $reason",
            reason?.contains("unterminated") == true,
        )
    }

    @Test
    fun a_block_left_open_at_end_of_file_is_unterminated() {
        // The prompt's block opens and the file ends before any closing
        // fence — there is no later section to sail on to, so this is the
        // end-of-file case: it must be refused as unterminated, not returned
        // empty or cut short.
        val markdown = listOf(
            "## System prompt",
            "",
            "```text",
            "the prompt that never closes",
        ).joinToString("\n")
        val reason = refusalOf(markdown)
        assertTrue(
            "a block left open at end of file must be refused as unterminated: " +
                "$reason",
            reason?.contains("unterminated") == true,
        )
    }

    @Test
    fun a_closing_fence_with_trailing_spaces_closes_the_block() {
        // A closing fence with trailing spaces is still the closing fence;
        // the old reader's trimStart-only compare refused it as unterminated.
        val markdown = listOf(
            "## System prompt",
            "",
            "```text",
            "the prompt",
            "```   ",
        ).joinToString("\n")
        assertEquals(
            "trailing spaces on the closing fence are whitespace, not a second fence",
            "the prompt",
            PromptCatalog.extractSystemPrompt(markdown, "format-v1"),
        )
    }

    @Test
    fun a_non_text_fence_before_the_text_block_is_not_the_prompt() {
        // Only the line that IS "```text" opens the prompt block: a ```json
        // block in the same section must be skipped, not opened on.
        val markdown = listOf(
            "## System prompt",
            "",
            "```json",
            "{ \"not\": \"the prompt\" }",
            "```",
            "",
            "```text",
            "the real prompt",
            "```",
        ).joinToString("\n")
        assertEquals(
            "only the ```text block is the prompt",
            "the real prompt",
            PromptCatalog.extractSystemPrompt(markdown, "format-v1"),
        )
    }

    @Test
    fun an_opening_fence_with_trailing_spaces_still_opens_the_block() {
        // An opening fence with trailing whitespace is still the opening
        // fence: the line is matched on its trimmed content, not as an exact
        // string, so "```text   " opens the block the way "```text" does.
        val markdown = listOf(
            "## System prompt",
            "",
            "```text   ",
            "the prompt",
            "```",
        ).joinToString("\n")
        assertEquals(
            "trailing spaces on the opening fence are whitespace, not a different fence",
            "the prompt",
            PromptCatalog.extractSystemPrompt(markdown, "format-v1"),
        )
    }

    @Test
    fun blank_lines_just_inside_the_block_are_not_part_of_the_prompt() {
        // Blank lines directly after the opening fence and directly before the
        // closing fence are formatting, not content: the prompt the model
        // receives must not start or end with them.
        val markdown = listOf(
            "## System prompt",
            "",
            "```text",
            "",
            "the prompt",
            "",
            "```",
        ).joinToString("\n")
        assertEquals(
            "the prompt is the block's content, without its edge blank lines",
            "the prompt",
            PromptCatalog.extractSystemPrompt(markdown, "format-v1"),
        )
    }

    @Test
    fun a_section_heading_with_trailing_spaces_is_still_the_section() {
        // The heading is matched on its trimmed line, so "## System prompt   "
        // is still the section: the trailing whitespace is not a second word
        // the section must not carry.
        val markdown = listOf(
            "## System prompt   ",
            "",
            "```text",
            "the prompt",
            "```",
        ).joinToString("\n")
        assertEquals(
            "trailing spaces on the heading line do not end the section",
            "the prompt",
            PromptCatalog.extractSystemPrompt(markdown, "format-v1"),
        )
    }

    private companion object {
        const val PROMPT_PATH = "shared/modules/format-prompts/prompts/format-v1.md"

        /** Every run of whitespace, line breaks included. */
        private val WHITESPACE = Regex("\\s+")
    }
}
