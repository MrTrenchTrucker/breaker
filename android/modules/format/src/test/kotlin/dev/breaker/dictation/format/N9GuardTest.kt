package dev.breaker.dictation.format

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N9GuardTest {

    @Test
    fun `guard accepts an unchanged candidate`() {
        val raw = "the quick brown fox"
        assertTrue(N9Guard.accepts(raw, raw))
    }

    @Test
    fun `guard accepts punctuation casing and list numbers`() {
        val raw = "i have 3 things one is a, two is b, three is c"
        val cand = "I have 3 things:\n\n1. is a.\n2. is b.\n3. is c."
        assertTrue(N9Guard.accepts(raw, cand))
    }

    @Test
    fun `guard accepts list numbers of one digit`() {
        val raw = "one is a, two is b"
        val cand = "1. is a.\n2. is b."
        assertTrue(N9Guard.accepts(raw, cand))
    }

    @Test
    fun `guard refuses a four digit number not in the raw`() {
        val raw = "i have one thing"
        val cand = "I have 1000 thing."
        assertFalse(N9Guard.accepts(raw, cand))
    }

    @Test
    fun `guard refuses a new word`() {
        val raw = "hello world"
        val cand = "hello universe."
        assertFalse(N9Guard.accepts(raw, cand))
    }

    @Test
    fun `guard matches case insensitively`() {
        val raw = "Hello World"
        val cand = "hello world."
        assertTrue(N9Guard.accepts(raw, cand))
    }

    @Test
    fun `guard accepts an all digit raw`() {
        val raw = "123 456"
        val cand = "123 456."
        assertTrue(N9Guard.accepts(raw, cand))
    }

    @Test
    fun `guard refuses a blank candidate`() {
        assertFalse(N9Guard.accepts("hello world", ""))
    }

    @Test
    fun `guard refuses a candidate that is too long`() {
        val raw = "hi"
        val cand = "x".repeat(100)
        assertFalse(N9Guard.accepts(raw, cand))
    }

    @Test
    fun `guard refuses a truncated answer that drops most words`() {
        val raw = "the quick brown fox jumps over the lazy dog"
        val cand = "the."
        assertFalse(N9Guard.accepts(raw, cand))
    }

    @Test
    fun `guard accepts a candidate exactly at the length limit`() {
        val raw = "hi"
        val cand = "hi ".repeat(24)
        assertTrue(N9Guard.accepts(raw, cand))
    }

    @Test
    fun `guard refuses a candidate one character over the length limit using only raw words`() {
        val raw = "hi"
        val cand = "hi ".repeat(24) + " "
        assertFalse(N9Guard.accepts(raw, cand))
    }

    @Test
    fun `guard refuses a candidate that collapses a negation phrase`() {
        // "do not send" -> "send": RED on the old guard (the set rule passes "send"),
        // green on the new guard because "not" is a protected token missing from the candidate
        assertFalse(N9Guard.accepts("do not send", "send"))
    }

    @Test
    fun `guard accepts a negation used more often in the candidate`() {
        assertTrue(N9Guard.accepts("not go", "not not go"))
    }

    @Test
    fun `guard refuses a candidate that drops a negation`() {
        // old guard: one non-digit candidate word vs rawWords/2 = 1, passes -> RED on old
        assertFalse(N9Guard.accepts("not go", "go"))
    }

    @Test
    fun `guard refuses a candidate that drops a digit`() {
        // old guard: all candidate words are raw words, passes -> RED on old
        assertFalse(N9Guard.accepts("i have 3 apples", "i have apples"))
    }

    @Test
    fun `guard keeps raw digits of any length and refuses them dropped`() {
        assertTrue(N9Guard.accepts("3", "3"))
        assertTrue(N9Guard.accepts("2026", "2026"))
        assertTrue(N9Guard.accepts("3pm", "3pm"))
        assertTrue(N9Guard.accepts("7 or 8", "7 or 8"))
        assertFalse(N9Guard.accepts("the room is 3", "the room is"))
        assertFalse(N9Guard.accepts("year 2026", "year"))
        assertFalse(N9Guard.accepts("at 3pm", "at"))
        assertFalse(N9Guard.accepts("7 or 8", "7"))
    }

    @Test
    fun `guard does not treat not inside another word as a negation`() {
        // The raw "another one" carries no negation; the candidate must not gain one.
        // "another not one" would add the protected word "not" that is not in the raw,
        // so the candidate-side set rule (a word not in the raw) refuses it -> RED on the
        // old guard where the set rule alone passed "another one" unchanged.
        assertFalse(N9Guard.accepts("another one", "another not one"))
    }

    @Test
    fun `guard is case insensitive and punctuation tolerant for protected tokens`() {
        assertTrue(N9Guard.accepts("Don't go!", "don't go"))
        assertTrue(N9Guard.accepts("NOT NOW", "not now"))
    }

    @Test
    fun `guard refuses don't rewritten to do not`() {
        // already refused by the existing set rule: "do" and "not" are not raw tokens of "don't"
        assertFalse(N9Guard.accepts("don't go", "do not go"))
    }

    // ── protected-token survivors: each mutation must be killed by a named case ──

    @Test
    fun `protected rule refuses a dropped n't contraction`() {
        // M4 (CONTRACTION_REGEX off): without the regex, "don't" is not counted in the raw,
        // so dropping it would pass. This case makes the regex load-bearing.
        assertFalse(N9Guard.accepts("don't go", "go"))
        assertFalse(N9Guard.accepts("can't stop", "stop"))
        assertFalse(N9Guard.accepts("won't go", "go"))
        assertFalse(N9Guard.accepts("isn't ready", "ready"))
    }

    @Test
    fun `protected rule counts a capital contraction from the raw`() {
        // M5 (raw not lowercased): the regex runs over the lower-cased raw. A capital
        // contraction in the raw must still be counted, or dropping it would pass.
        assertFalse(N9Guard.accepts("Don't go", "go"))
        assertFalse(N9Guard.accepts("CAN'T stop", "stop"))
    }

    @Test
    fun `protected rule counts a curly apostrophe contraction`() {
        // M17 (curly apostrophe removed from the regex): 'don’t' (U+2019) must still count.
        assertFalse(N9Guard.accepts("don’t go", "go"))
        assertFalse(N9Guard.accepts("don’t stop", "stop"))
    }

    @Test
    fun `protected rule counts a negation word next to punctuation`() {
        // M9 (tokenise by space instead of non-alphanumerics): a comma glued to a negation
        // must not hide it. "no," must still count as the word "no".
        assertFalse(N9Guard.accepts("no, send it", "send it"))
        assertFalse(N9Guard.accepts("not. go", "go"))
    }

    @Test
    fun `each base negation word is protected`() {
        // M10-M13: each base negation word dropped from PROTECTED_TOKENS must be caught by a
        // refusal. The list mirrors the guard's own set; a word missing from the guard makes
        // its case RED. (The set is private, so the list is duplicated here on purpose: the
        // two must agree, and a guard that drops a word fails the matching case by name.)
        val baseNegations = listOf(
            "not", "no", "never", "cannot", "without", "none", "nothing", "nobody", "neither", "nor"
        )
        for (w in baseNegations) {
            assertFalse(
                "base negation '$w' must be protected: dropping it must be refused",
                N9Guard.accepts("$w something here", "something here"),
            )
        }
    }

    @Test
    fun `each n't contraction stem is protected`() {
        // M14-M17: the contraction stems can't/don't/won't (and the rest) must each be counted.
        val contractions = listOf(
            "don't", "doesn't", "didn't", "won't", "can't", "isn't", "aren't",
            "wasn't", "weren't", "haven't", "hasn't", "hadn't", "wouldn't",
            "shouldn't", "couldn't", "mustn't", "needn't"
        )
        for (c in contractions) {
            assertFalse(
                "contraction '$c' must be protected: dropping it must be refused",
                N9Guard.accepts("$c something", "something"),
            )
        }
    }

    @Test
    fun `a negation used more often in the raw is still fine`() {
        // The protected rule is >= (raw count), not == : using the word more in the candidate
        // is allowed; only dropping below the raw count is refused.
        assertTrue(N9Guard.accepts("not go", "not not go"))
        assertFalse(N9Guard.accepts("not not go", "go"))
    }
}
