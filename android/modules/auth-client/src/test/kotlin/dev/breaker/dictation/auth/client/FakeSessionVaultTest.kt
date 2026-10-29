package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSessionVaultTest {

    @Test
    fun `load returns null before anything is saved`() {
        val fake = FakeSessionVault()
        assertNull("nothing was saved yet", fake.load())
    }

    @Test
    fun `load returns the last saved string until clear`() {
        val fake = FakeSessionVault()
        fake.save("first")
        fake.save("second")
        assertEquals("the last save wins", "second", fake.load())
        fake.clear()
        assertNull("clear removes the stored string", fake.load())
    }

    @Test
    fun `a save told to fail throws that exact object and stores nothing`() {
        val fake = FakeSessionVault()
        fake.save("kept")
        val failure = FakeVaultRefusal()
        fake.saveFailure = failure
        var thrown: RuntimeException? = null
        try {
            fake.save("lost")
        } catch (refusal: RuntimeException) {
            thrown = refusal
        }
        assertTrue("the thrown object must be the one the test gave", thrown === failure)
        assertEquals("a failed save must not replace the stored string", "kept", fake.load())
    }

    @Test
    fun `the fake records each call by name and never its content`() {
        val fake = FakeSessionVault()
        fake.save("secret-record-text")
        fake.load()
        fake.clear()
        assertEquals("only call names are recorded", listOf("save", "load", "clear"), fake.calls)
    }

    @Test
    fun `the fake's string form carries no stored content`() {
        val fake = FakeSessionVault()
        fake.save("secret-record-text")
        assertFalse("toString must not print the stored text", fake.toString().contains("secret"))
        assertEquals("toString is the fixed name", "FakeSessionVault", fake.toString())
    }
}
