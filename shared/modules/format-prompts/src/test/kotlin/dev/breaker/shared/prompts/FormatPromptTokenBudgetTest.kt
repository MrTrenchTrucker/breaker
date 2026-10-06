package dev.breaker.shared.prompts

import org.junit.Assert.assertTrue
import org.junit.Test

/** The token budget the prompt is sent with: it grows with the input and never truncates it. */
class FormatPromptTokenBudgetTest {

    @Test
    fun the_token_budget_grows_with_the_input_and_never_truncates() {
        var previous = 0
        for (inputTokens in listOf(0, 1, 50, 200, 1_000, 8_000, 40_000)) {
            val budget = PromptParameters.maxTokensFor(inputTokens)
            assertTrue(
                "a longer dictation must never get a smaller budget: " +
                    "$inputTokens tokens -> $budget, after $previous",
                budget >= previous,
            )
            assertTrue(
                "the budget for $inputTokens tokens must leave room for the text itself",
                budget > inputTokens,
            )
            previous = budget
        }
    }

    @Test
    fun a_negative_input_is_refused() {
        assertTrue(
            runCatching { PromptParameters.maxTokensFor(-1) }.exceptionOrNull() is IllegalArgumentException
        )
    }
}
