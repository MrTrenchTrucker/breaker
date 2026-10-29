package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins how a checked copy maps to the installer's answer: a match gives no answer, a bad or oversized copy is a mismatch. */
internal class CopyResultTest {
    @Test
    fun `a matched copy gives no installer result`() {
        assertNull(resultOfCheckedCopy(CheckedCopy.Matched))
    }

    @Test
    fun `a digest mismatch gives digest mismatch`() {
        assertEquals(InstallerResult.DigestMismatch, resultOfCheckedCopy(CheckedCopy.Mismatched))
    }

    @Test
    fun `a copy over the cap gives digest mismatch`() {
        assertEquals(InstallerResult.DigestMismatch, resultOfCheckedCopy(CheckedCopy.TooLarge))
    }

    @Test
    fun `a read failure gives failed`() {
        assertEquals(InstallerResult.Failed, resultOfCheckedCopy(CheckedCopy.Failed))
    }
}
