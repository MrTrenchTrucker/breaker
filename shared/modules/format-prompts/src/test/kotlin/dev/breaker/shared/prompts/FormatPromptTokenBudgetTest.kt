package dev.breaker.shared.prompts

import org.junit.Assert.assertEquals
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

    @Test
    fun the_token_budget_does_not_overflow_for_very_large_inputs() {
        // The scaled budget passes the Int range for inputs above about 1.4
        // billion tokens. A larger input must still never get a smaller budget
        // than a smaller one, so the budget must not wrap around there.
        val small = PromptParameters.maxTokensFor(1_000_000)
        val large = PromptParameters.maxTokensFor(1_500_000_000)
        assertTrue(
            "a longer dictation must never get a smaller budget: " +
                "1,000,000 tokens -> $small, 1,500,000,000 tokens -> $large",
            large >= small,
        )
    }

    @Test
    fun the_token_budget_is_monotonic_across_the_wrap_boundary() {
        val inputs = listOf(1_431_655_722, 1_431_655_723, 1_431_655_764, 1_431_655_765, 2_000_000_000, Int.MAX_VALUE)
        var previous = 0
        for (inputTokens in inputs) {
            val budget = PromptParameters.maxTokensFor(inputTokens)
            assertTrue(
                "a longer dictation must never get a smaller budget: " +
                    "$inputTokens tokens -> $budget, after $previous",
                budget >= previous,
            )
            previous = budget
        }
    }

    @Test
    fun the_token_budget_clamps_to_int_max_and_preserves_small_inputs() {
        assertEquals(
            "Int.MAX_VALUE input must return Int.MAX_VALUE",
            Int.MAX_VALUE,
            PromptParameters.maxTokensFor(Int.MAX_VALUE),
        )
        assertEquals(
            "0 tokens must return MIN_TOKENS",
            256,
            PromptParameters.maxTokensFor(0),
        )
        assertEquals(
            "1000 tokens must return 1564",
            1564,
            PromptParameters.maxTokensFor(1000),
        )
    }
}
