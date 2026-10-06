package dev.breaker.shared.prompts

/**
 * The formatting prompt contract, in a form a compiler checks.
 *
 * The prompt text is the data of record (`prompts/format-v1.md`); these types
 * are the parameters and rules that text is held to. The formatter that sends
 * it is `android/modules/format`, and the LLM that receives it is the server's
 * — neither lives here.
 *
 * The rule behind all of it: **formatting is non-destructive.** Same meaning,
 * better shape. Anything that lets the model add, drop or alter content defeats
 * the purpose, which is why the temperature is fixed at zero and structured
 * output is off.
 */
object PromptParameters {

    /**
     * The temperature the prompt is sent with.
     *
     * Zero, and not "a low value": at any other temperature the output is a
     * sample rather than a function of the input, and a sample can rewrite.
     */
    const val TEMPERATURE: Double = 0.0

    /** Structured output is off; see the module card's known gotchas. */
    const val STRUCTURED_OUTPUT: Boolean = false

    /** JSON mode is off, for the same reason. */
    const val JSON_MODE: Boolean = false

    /** The model returns the text itself, not a description of it. */
    const val RESPONSE_FORMAT: String = "text"

    /**
     * Tokens allowed per token of input.
     *
     * `max_tokens` is proportional to the input rather than fixed, so a long
     * dictation is not silently truncated. Formatting adds punctuation, casing
     * and list markers — it does not add words — so the budget is a small
     * multiple of the input plus [HEADROOM_TOKENS], and never below
     * [MIN_TOKENS].
     */
    const val TOKENS_PER_INPUT_TOKEN: Double = 1.5
    const val HEADROOM_TOKENS: Int = 64
    const val MIN_TOKENS: Int = 256

    /**
     * The `max_tokens` to send for an input of [inputTokens] tokens.
     *
     * Monotonic and never below [MIN_TOKENS]: a longer dictation never gets a
     * smaller budget, so the output cannot be cut short by a fixed ceiling.
     * The budget is computed in [Long] and clamped to [Int.MAX_VALUE], so a very
     * large input cannot wrap around to a small one.
     */
    fun maxTokensFor(inputTokens: Int): Int {
        require(inputTokens >= 0) { "input token count must not be negative, was $inputTokens" }
        val scaled = (inputTokens.toLong() * TOKENS_PER_INPUT_TOKEN).toLong() + HEADROOM_TOKENS
        return scaled.coerceIn(MIN_TOKENS.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}

/**
 * A versioned formatting prompt.
 *
 * A prompt is identified by its [id], which is also the resource it loads from,
 * so a caller names the wording it wants rather than a path it assembles. The
 * catalogue refuses an id it does not carry: shipping a prompt the phone cannot
 * load is a start-up failure, not a fallback.
 */
data class FormatPrompt(
    val id: String,
    val version: String,
    val systemPrompt: String,
    val temperature: Double = PromptParameters.TEMPERATURE,
    val structuredOutput: Boolean = PromptParameters.STRUCTURED_OUTPUT,
    val jsonMode: Boolean = PromptParameters.JSON_MODE,
    val responseFormat: String = PromptParameters.RESPONSE_FORMAT,
) {
    init {
        require(id.isNotBlank()) { "a prompt needs an id" }
        require(id == version) {
            "a prompt's id and version are the same string, so a consumer can name either; " +
                "id was '$id' and version was '$version'"
        }
        require(temperature == 0.0) {
            "the formatting prompt runs at temperature 0; $temperature would let the model rewrite"
        }
        require(!structuredOutput) { "structured output is off: the model returns text, not a schema" }
        require(!jsonMode) { "json mode is off: the model returns text, not a schema" }
        require(responseFormat == PromptParameters.RESPONSE_FORMAT) {
            "the prompt expects plain text, not '$responseFormat'"
        }
        requireClausesHeld()
    }

    /**
     * The non-destructive clauses the prompt must carry.
     *
     * Checked on construction, because a prompt that has lost one of these is
     * the failure this module exists to prevent — and it fails quietly, as
     * output that is merely a little different.
     *
     * The text is matched with its wrapping collapsed, because a clause is a
     * phrase and a line break between two words is not a change to it. The
     * prompt is markdown, and markdown is hard-wrapped; a check that read the
     * wrap as a missing clause would fail on the wrapping alone and send
     * someone to reword a prompt that says exactly what it must.
     */
    private fun requireClausesHeld() {
        val text = systemPrompt.folded()
        val missing = REQUIRED_CLAUSES.filterNot { it.folded() in text }
        require(missing.isEmpty()) {
            "prompt '$id' is missing the clauses that make formatting non-destructive: $missing"
        }
    }

    /** True when the text forbids inventing content. */
    fun preservesContent(): Boolean =
        NEVER_CLAUSES.all { it.folded() in systemPrompt.folded() }

    /** The text with every run of whitespace — line breaks included — one space. */
    private fun String.folded(): String =
        lowercase().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        /** Every run of whitespace, line breaks included. */
        private val WHITESPACE = Regex("\\s+")

        /** Lower-cased fragments the prompt must contain, for the contract it states. */
        val REQUIRED_CLAUSES = listOf(
            "preserve every fact",
            "add punctuation",
            "fix casing",
            "remove filler words",
            "numbered lists",
            "output text only",
        )

        /** The clauses that forbid the model adding or dropping content. */
        val NEVER_CLAUSES = listOf(
            "never add, remove, or change content",
        )
    }
}

/** The prompts this module ships, by id. */
object PromptCatalog {

    /** The wording shipped today. */
    const val CURRENT: String = "format-v1"

    /**
     * Loads a prompt by id, refusing an id this module does not ship.
     *
     * A missing prompt is a build or deployment fault, never something to work
     * around at runtime: falling back to a different wording would change what
     * the user gets without anything saying so.
     */
    fun load(id: String, classLoader: ClassLoader = PromptCatalog::class.java.classLoader): FormatPrompt {
        require(SHIPPED.contains(id)) {
            "no formatting prompt with id '$id'; this module ships $SHIPPED"
        }
        val resource = classLoader.getResourceAsStream(id.removePrefix("/") + ".md")
            ?: throw IllegalStateException("prompt '$id' is registered but not on the classpath")
        val text = resource.bufferedReader().use { it.readText() }
        return FormatPrompt(
            id = id,
            version = id,
            systemPrompt = extractSystemPrompt(text, id),
        )
    }

    /** The ids this module ships, newest last. */
    val SHIPPED: List<String> = listOf(CURRENT)

    /**
     * The system prompt, as the fenced block the prompt file carries it in.
     *
     * The file is markdown for humans; the model gets the block and nothing
     * else, so the prose around it can be edited freely. `internal` (visible
     * within this module — Kotlin has no package-private) so the unit test
     * can reach it; it is not part of the public interface.
     *
     * The section is the LINE that is the "## System prompt" heading — the
     * name at the start of the line, never a substring, so a "### System
     * prompt notes" sub-heading or a prose mention is not it — and it runs to
     * the next "## " heading or end of file. Only the OPENING fence has to
     * sit inside that section: the line that IS "```text" (surrounding
     * whitespace aside) opens the block, and once it opens the block runs to
     * its closing fence — the line that IS "```". A "## " line inside the
     * prompt is content, not a section end; any other fence line means the
     * block never closed.
     */
    internal fun extractSystemPrompt(markdown: String, id: String): String {
        val lines = markdown.lines()
        var headingLine = -1
        for (i in lines.indices) {
            if (lines[i].trimEnd() == "## System prompt") { headingLine = i; break }
        }
        if (headingLine < 0) {
            throw IllegalStateException("prompt '$id' has no system prompt section")
        }
        // One ordered scan of the section. Outside the block, a "## " line ends
        // the search (no block was opened, so the section carried no prompt);
        // the line that IS "```text" opens the block. Inside the block a "## "
        // line is content (the prompt may show an example), the line that IS
        // "```" closes it, and any other fence line means the block never
        // closed.
        var openLine = -1
        for (i in (headingLine + 1) until lines.size) {
            val line = lines[i]
            if (openLine < 0) {
                if (line.startsWith("## ")) break
                if (line.trim() == "```text") openLine = i
            } else {
                val trimmed = line.trim()
                if (trimmed == "```") {
                    return lines.subList(openLine + 1, i).joinToString("\n").trim()
                }
                if (trimmed.startsWith("```")) {
                    throw IllegalStateException("prompt '$id' has an unterminated system prompt block")
                }
            }
        }
        if (openLine < 0) {
            throw IllegalStateException("prompt '$id' has no fenced system prompt")
        }
        throw IllegalStateException("prompt '$id' has an unterminated system prompt block")
    }
}
