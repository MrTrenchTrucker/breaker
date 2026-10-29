package dev.breaker.dictation.auth.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the source text of the session record and the vault port: no log or print call, and no
 * import of a crypto or security package. A missing file is a failure, never a pass, and the
 * patterns are first shown to fire on planted samples.
 */
class SessionVaultSourceScanTest {
    private val folder = "src/main/kotlin/dev/breaker/dictation/auth/client/"
    private val mainFiles = listOf("SessionRecord.kt", "SessionVault.kt")
    private val logCall = Regex("\\b(println|print)\\s*\\(|\\bLog\\.|\\bLogger\\b|\\bjava\\.util\\.logging\\b|\\bSystem\\.(out|err)\\b")
    private val cryptoImport = Regex("^import\\s+.*\\b(crypto|security)\\b", RegexOption.MULTILINE)

    private fun sourceOf(name: String): String {
        val file = File(folder + name)
        assertTrue("the main source must exist and be scanned: " + name, file.isFile)
        return file.readText()
    }

    @Test
    fun `the session record and vault sources contain no log or print call`() {
        val hits = ArrayList<String>()
        for (name in mainFiles) {
            if (logCall.containsMatchIn(sourceOf(name))) hits.add(name)
        }
        assertTrue("log or print call in: " + hits, hits.isEmpty())
    }

    @Test
    fun `the session record and vault sources import no crypto or security package`() {
        val hits = ArrayList<String>()
        for (name in mainFiles) {
            if (cryptoImport.containsMatchIn(sourceOf(name))) hits.add(name)
        }
        assertTrue("crypto or security import in: " + hits, hits.isEmpty())
    }

    @Test
    fun `the source scan finds a planted log call and a planted crypto import`() {
        assertTrue("a planted print call is found", logCall.containsMatchIn("fun f() { println(\"x\") }"))
        assertTrue("a planted crypto import is found", cryptoImport.containsMatchIn("import javax.crypto.Cipher\n"))
        assertFalse("a clean import is not flagged", cryptoImport.containsMatchIn("import dev.breaker.dictation.core.model.AuthSession"))
        assertFalse("a clean line is not flagged", logCall.containsMatchIn("val fingerprint = 1"))
    }

    @Test
    fun `a missing source file is reported and never passes`() {
        var passed = false
        try {
            sourceOf("NoSuchFile.kt")
            passed = true
        } catch (expected: AssertionError) {
            passed = false
        }
        assertFalse("a missing source file must not pass the scan", passed)
    }
}
