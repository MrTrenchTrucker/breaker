package dev.breaker.dictation.updater

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pins the sweep of the temporary files that an interrupted ledger write leaves in the ledger's folder. */
internal class LedgerTempSweepTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ledger(): File = File(tmp.root, "update-ledger.txt")

    private fun touch(name: String): File {
        val file = File(tmp.root, name)
        Files.write(file.toPath(), ByteArray(0))
        return file
    }

    @Test
    fun `sweep removes a temp file with the marker and digits`() {
        val temp = touch("update-ledger.txt.tmp7")
        sweepStaleLedgerTemps(ledger())
        assertFalse("a temp file with the marker and digits must be removed", temp.exists())
    }

    @Test
    fun `sweep never removes the ledger file`() {
        val ledgerFile = touch("update-ledger.txt")
        sweepStaleLedgerTemps(ledgerFile)
        assertTrue("the ledger file must stay", ledgerFile.exists())
    }

    @Test
    fun `sweep keeps the marker with no digits`() {
        val bare = touch("update-ledger.txt.tmp")
        sweepStaleLedgerTemps(ledger())
        assertTrue("a marker with no digits is not a temp file", bare.exists())
    }

    @Test
    fun `sweep keeps the marker with letters after it`() {
        val letters = touch("update-ledger.txt.tmpabc")
        val mixed = touch("update-ledger.txt.tmp1x")
        sweepStaleLedgerTemps(ledger())
        assertTrue("a marker followed by letters is kept", letters.exists())
        assertTrue("a digit followed by a letter is kept", mixed.exists())
    }

    @Test
    fun `sweep keeps the ledger name with a digit appended`() {
        val appended = touch("update-ledger.txt5")
        sweepStaleLedgerTemps(ledger())
        assertTrue("the ledger name with a digit appended is kept", appended.exists())
    }

    @Test
    fun `sweep keeps other files and an empty folder with a temp name`() {
        val other = touch("other.txt")
        val folder = File(tmp.root, "update-ledger.txt.tmp1")
        Files.createDirectory(folder.toPath())
        sweepStaleLedgerTemps(ledger())
        assertTrue("another file is kept", other.exists())
        assertTrue("a folder with a temp name is kept", folder.isDirectory)
    }

    @Test
    fun `sweep does nothing for a missing folder or a file in its place`() {
        sweepStaleLedgerTemps(File(File(tmp.root, "no-such-folder"), "update-ledger.txt"))
        val blocker = touch("blocker")
        sweepStaleLedgerTemps(File(blocker, "update-ledger.txt"))
        assertTrue("a file in place of the folder is left alone", blocker.exists())
    }

    @Test
    fun `sweep ignores a temp file it cannot remove`() {
        val temp = touch("update-ledger.txt.tmp9")
        tmp.root.setWritable(false)
        try {
            assumeTrue("the process must be unable to write to the folder", !tmp.root.canWrite())
            sweepStaleLedgerTemps(ledger())
            assertTrue("a file that cannot be removed stays, and no exception escapes", temp.exists())
        } finally {
            tmp.root.setWritable(true)
        }
    }

    @Test
    fun `the temp name is the ledger name then dot tmp then the sequence`() {
        assertEquals("update-ledger.txt.tmp3", ledgerTempName("update-ledger.txt", 3))
    }

    @Test
    fun `sweep removes the name that the temp name function makes`() {
        val made = touch(ledgerTempName("update-ledger.txt", 12))
        sweepStaleLedgerTemps(ledger())
        assertFalse("the sweep must remove the name the naming function makes", made.exists())
    }
}
