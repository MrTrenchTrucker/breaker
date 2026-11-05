package dev.breaker.dictation.ui.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The save decision of one text box, on its own: the text goes out at most once, the first
 * time the person is done with it and it differs from the text the box started with. The
 * rule is plain Kotlin, so these tests run on the JVM without a device.
 */
class EntryCommitRuleTest {

    @Test
    fun `a changed text is sent once`() {
        val sent = mutableListOf<String>()
        val rule = EntryCommitRule("a") { sent.add(it) }
        rule.finished("b")
        assertEquals(listOf("b"), sent)
    }

    @Test
    fun `the same text again is not sent a second time`() {
        val sent = mutableListOf<String>()
        val rule = EntryCommitRule("a") { sent.add(it) }
        rule.finished("b")
        rule.finished("b")
        assertEquals(listOf("b"), sent)
    }

    @Test
    fun `a second different text after a send is not sent`() {
        val sent = mutableListOf<String>()
        val rule = EntryCommitRule("a") { sent.add(it) }
        rule.finished("b")
        rule.finished("c")
        assertEquals(listOf("b"), sent)
    }

    @Test
    fun `an unchanged text is never sent`() {
        val sent = mutableListOf<String>()
        val rule = EntryCommitRule("a") { sent.add(it) }
        rule.finished("a")
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `an empty start text and an empty typed text are never sent`() {
        val sent = mutableListOf<String>()
        val rule = EntryCommitRule("") { sent.add(it) }
        rule.finished("")
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `an emptied box sends the empty text when the start text was set`() {
        val sent = mutableListOf<String>()
        val rule = EntryCommitRule("https://box.example") { sent.add(it) }
        rule.finished("")
        assertEquals(listOf(""), sent)
    }

    @Test
    fun `the compare does not trim the text`() {
        val sent = mutableListOf<String>()
        val rule = EntryCommitRule("a") { sent.add(it) }
        rule.finished("a ")
        assertEquals(listOf("a "), sent)
    }

    @Test
    fun `the start text is the one the compare uses`() {
        val sentSame = mutableListOf<String>()
        EntryCommitRule("x") { sentSame.add(it) }.finished("x")
        assertEquals(emptyList<String>(), sentSame)
        val sentOther = mutableListOf<String>()
        EntryCommitRule("y") { sentOther.add(it) }.finished("x")
        assertEquals(listOf("x"), sentOther)
    }

    @Test
    fun `a send that throws still leaves the rule marked as sent`() {
        var calls = 0
        var threw = false
        val rule = EntryCommitRule("a") {
            calls += 1
            throw IllegalStateException("send refused")
        }
        try {
            rule.finished("b")
        } catch (refused: IllegalStateException) {
            threw = true
        }
        rule.finished("c")
        assertTrue("the send was expected to throw and did not", threw)
        assertEquals(1, calls)
    }

    @Test
    fun `a send that finishes again is not sent twice`() {
        val sent = mutableListOf<String>()
        lateinit var rule: EntryCommitRule
        rule = EntryCommitRule("a") { typed ->
            sent.add(typed)
            rule.finished("c")
        }
        rule.finished("b")
        assertEquals(listOf("b"), sent)
    }
}
