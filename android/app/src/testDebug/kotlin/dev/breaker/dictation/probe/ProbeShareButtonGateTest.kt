package dev.breaker.dictation.probe

import dev.breaker.dictation.gates.AppSourceFiles
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share button on the debug probe page: a labelled button that opens a share sheet
 * (ACTION_SEND, text/plain, a chooser) whose extra text is the text the share builder
 * returns. The wiring is checked here as a source rule; the text itself is checked by
 * [ProbeLinesTest]. Every rule reads the page through [AppSourceFiles.strip]: a code rule
 * reads the comment-free code, a label or type rule reads the literals, so a commented-out
 * line or label no longer satisfies a rule.
 */
internal class ProbeShareButtonGateTest {

    private fun debugPage(): String {
        var folder: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (folder != null) {
            val file = File(folder, "src/debug/kotlin/dev/breaker/dictation/probe/KeyboardProbeActivity.kt")
            if (file.isFile) return file.readText()
            folder = folder.parentFile
        }
        error("probe: the debug probe page was not found above ${System.getProperty("user.dir")}")
    }

    /** The source text with comments and literal text blanked, so only code is read. */
    private fun codeOf(text: String): String = AppSourceFiles.strip(text).code

    /** The string literals of the source text, so a rule about a label or a type reads them and not a comment. */
    private fun literalsOf(text: String): List<String> = AppSourceFiles.strip(text).literals

    @Test
    fun `the debug page wires a share button to a share sheet`() {
        val code: String = codeOf(debugPage())
        val literals: List<String> = literalsOf(debugPage())
        assertTrue("probe: the debug page does not name the share action", code.contains("ACTION_SEND"))
        assertTrue("probe: the debug page does not set a plain text mime type", literals.any { it.contains("text/plain") })
        assertTrue("probe: the debug page does not name the chooser", literals.any { it.contains("Share the probe log") })
        assertTrue("probe: the debug page does not offer the sheet", code.contains("createChooser"))
    }

    @Test
    fun `the shared text is passed as the send extra`() {
        val code: String = codeOf(debugPage())
        val builder: Int = code.indexOf("buildShareText")
        assertTrue("probe: the debug page never builds the shared text", builder >= 0)
        assertTrue(
            "probe: the shared text is not passed as the send extra",
            code.contains("EXTRA_TEXT"),
        )
        assertTrue(
            "probe: the built text is not handed to the send intent",
            code.indexOf("EXTRA_TEXT") > builder,
        )
    }

    @Test
    fun `the button is labelled and reachable in the list`() {
        val code: String = codeOf(debugPage())
        val literals: List<String> = literalsOf(debugPage())
        assertTrue("probe: the debug page has no share button label", literals.any { it.contains("Share log") })
        assertTrue("probe: the share button has no click action", code.contains("setOnClickListener"))
    }

    @Test
    fun `the share button is added to the page`() {
        val code: String = codeOf(debugPage())
        assertTrue("probe: the share button is never added to the page", code.contains("column.addView(share)"))
    }

    @Test
    fun `shareLog feeds the kept readings and the inset lines`() {
        val code: String = codeOf(debugPage())
        val start: Int = code.indexOf("private fun shareLog()")
        assertTrue("probe: the debug page has no shareLog method", start >= 0)
        val body: String = code.substring(start)
        assertTrue(
            "probe: shareLog does not feed the kept readings",
            body.contains("readings = KeyboardProbeReadings.recent()"),
        )
        assertTrue(
            "probe: shareLog does not feed the inset lines",
            body.contains("insets = ProbeState.insets.lines()"),
        )
    }

    @Test
    fun `shareLog feeds the builder a clock so production share lines carry times`() {
        val code: String = codeOf(debugPage())
        val start: Int = code.indexOf("private fun shareLog()")
        assertTrue("probe: the debug page has no shareLog method", start >= 0)
        val body: String = code.substring(start)
        assertTrue(
            "probe: shareLog does not pass a clock to the share builder",
            body.contains("clock = ::clockText"),
        )
    }
}
