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
}
