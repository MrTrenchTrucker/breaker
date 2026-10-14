package dev.breaker.shared.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clause that forbids the model from changing content is checked when a
 * prompt is built, the same way the other non-destructive clauses are.
 *
 * A prompt that has dropped the sentence still reads like a formatting prompt,
 * and its output drifts without anything saying so, so the refusal has to
 * happen at construction. These tests build prompts from the shipped wording
 * with the sentence cut out, re-laid out or cut together with another clause.
 */
class FormatPromptNeverClauseTest {

    private val shipped: String = PromptCatalog.load(PromptCatalog.CURRENT).systemPrompt

    @Test
    fun a_prompt_that_has_lost_the_never_clause_is_refused() {
        val withoutNever = shipped.replace(NEVER_SENTENCE, "")
        assertTrue(
            "the sentence must be found in the shipped wording, or the test proves nothing",
            withoutNever != shipped,
        )
        val refused = constructionRefusal(withoutNever)
        assertTrue(
            "a prompt that lost its never-add clause must be refused at construction: $refused",
            refused is IllegalArgumentException,
        )
        assertTrue(
            "the refusal should name the lost clause: $refused",
            refused?.message?.contains("never add, remove, or change content") == true,
        )
        assertEquals(
            "the refusal must carry the documented message, word for word",
            "prompt 'format-v9' is missing the clauses that forbid changing content: [never add, remove, or change content]",
            refused?.message,
        )
    }

    @Test
    fun the_never_clause_is_matched_the_way_the_required_ones_are() {
        // A clause is a phrase: a line break inside it, or upper-case letters,
        // are the same clause, so a prompt that carries it that way must build.
        val hardWrapped = shipped.replace(NEVER_SENTENCE, "Never add, remove,\nor change content.")
        val upperCase = shipped.replace(NEVER_SENTENCE, "NEVER ADD, REMOVE, OR CHANGE CONTENT.")
        assertTrue(
            "the hard-wrapped variant must differ from the shipped wording, or the test proves nothing",
            hardWrapped != shipped,
        )
        assertTrue(
            "the upper-case variant must differ from the shipped wording, or the test proves nothing",
            upperCase != shipped,
        )
        assertTrue(
            "the hard-wrapped variant must carry the clause once folded, or it proves nothing",
            hardWrapped.folded().contains(NEVER_SENTENCE.folded()),
        )
        assertTrue(
            "the upper-case variant must carry the clause once folded, or it proves nothing",
            upperCase.folded().contains(NEVER_SENTENCE.folded()),
        )
        for (variant in listOf(hardWrapped, upperCase)) {
            val refused = constructionRefusal(variant)
            assertTrue(
                "the same clause in another line layout or case must be accepted: $refused",
                refused == null,
            )
        }
    }

    @Test
    fun a_prompt_that_lost_a_required_clause_and_the_never_clause_names_the_required_one() {
        // The required check runs first, so the message names the clause the
        // existing message names, and not the never clause as well.
        val withoutNever = shipped.replace(NEVER_SENTENCE, "")
        val weakened = withoutNever.replace(PRESERVE_PHRASE, "")
        assertTrue(
            "the never sentence must be removed before the test can count it as lost",
            withoutNever != shipped,
        )
        assertTrue(
            "the required phrase must be found in the wording before it is removed",
            weakened != withoutNever,
        )
        val refused = constructionRefusal(weakened)
        assertTrue(
            "a prompt that lost both kinds of clause must be refused: $refused",
            refused is IllegalArgumentException,
        )
        assertTrue(
            "the refusal must name the required clause that is missing: $refused",
            refused?.message?.contains("preserve every fact") == true,
        )
        assertTrue(
            "the required check must run first, so the never clause is not named as well: $refused",
            refused?.message?.contains("forbid changing content") != true,
        )
    }

    @Test
    fun a_prompt_that_lost_a_required_clause_is_refused_with_the_documented_message() {
        // The REQUIRED check names the clauses it found missing. The whole
        // message is pinned here, not one phrase inside it, so a change to the
        // wording the constructor documents fails this test.
        val withoutRequired = shipped.replace("Output text only.", "")
        assertTrue(
            "the sentence must be found in the shipped wording, or the test proves nothing",
            withoutRequired != shipped,
        )
        val refused = constructionRefusal(withoutRequired)
        assertTrue(
            "a prompt that lost a required clause must be refused at construction: $refused",
            refused is IllegalArgumentException,
        )
        assertEquals(
            "the refusal must carry the documented message, word for word",
            "prompt 'format-v9' is missing the clauses that make formatting non-destructive: [output text only]",
            refused?.message,
        )
    }

    /** The refusal the constructor gives for [text], or null when it builds. */
    private fun constructionRefusal(text: String): Throwable? =
        runCatching {
            FormatPrompt(id = "format-v9", version = "format-v9", systemPrompt = text)
        }.exceptionOrNull()

    /** The text with every run of whitespace one space and lower-cased, as a clause is compared. */
    private fun String.folded(): String =
        lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private companion object {
        const val NEVER_SENTENCE = "Never add, remove, or change content."
        const val PRESERVE_PHRASE = "Preserve every fact and the exact meaning. "
    }
}
