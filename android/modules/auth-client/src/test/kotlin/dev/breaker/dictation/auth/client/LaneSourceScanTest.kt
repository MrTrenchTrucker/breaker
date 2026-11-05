package dev.breaker.dictation.auth.client

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneSourceScanTest {
    private val folder = "src/main/kotlin/dev/breaker/dictation/auth/client"
    private val laneFiles = listOf("AuthHttp.kt", "AuthFailure.kt", "AccountRules.kt")

    private val offender = Regex(
        """(\bprintln\s*\(|\bprint\s*\(|\bprintStackTrace\s*\(|\bLog\.|\bLogger\b|\bSystem\.(out|err)\b|java\.util\.logging|^import\s+(javax\.crypto|dev\.breaker\.dictation\.crypto)\b)""",
        RegexOption.MULTILINE,
    )

    private fun offenders(name: String, text: String): List<String> {
        val found = mutableListOf<String>()
        for (match in offender.findAll(text)) {
            found.add("$name: ${match.value}")
        }
        return found
    }

    @Test
    fun `the lane's main files print nothing and import no crypto code`() {
        val found = mutableListOf<String>()
        for (name in laneFiles) {
            found.addAll(offenders(name, File(folder, name).readText()))
        }
        assertTrue("a lane file holds a print, log or crypto import: $found", found.isEmpty())
    }

    @Test
    fun `the scan flags a print call and a crypto import in planted text`() {
        val planted = "import javax.crypto.Cipher\nfun leak() = println(\"x\")\nimport dev.breaker.dictation.crypto.Thing\n"
        val found = offenders("planted.kt", planted)
        assertTrue("the scan let the planted print or crypto import through: $found", found.size == 3)
    }

    @Test
    fun `the scan leaves ordinary code alone`() {
        val ordinary = "import java.security.SecureRandom\nval label = \"print the form\"\nval logger = 1\n"
        val found = offenders("ordinary.kt", ordinary)
        assertTrue("the scan flagged ordinary code: $found", found.isEmpty())
    }
}
