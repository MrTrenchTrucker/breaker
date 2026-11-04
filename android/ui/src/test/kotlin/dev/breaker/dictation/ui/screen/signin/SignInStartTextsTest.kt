package dev.breaker.dictation.ui.screen.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The sign-in start words, the server line, the node ids, and source-text checks of the host view.
 *
 * The host is an Android view and is not run here. Its source is read for three facts a change could
 * break: its parent class, the number of kit nodes it draws, and that the server address is read at
 * each drawing and never kept in a field.
 */
class SignInStartTextsTest {

    private val highestAscii = 127

    private fun joined(vararg pieces: String): String = pieces.joinToString("")

    private val bannedWords = listOf(
        joined("ar", "med"), joined("ser", "vice"), joined("re", "lay"), joined("ha", "ul"),
        joined("sent", "inel"), joined("gr", "ip"), joined("sli", "ce"), joined("coun", "cil"),
        joined("sub", "-agent"), joined("rea", "dback"), joined("rul", "ing"), joined("buil", "der"),
        joined("hand", "off"), joined("inject", "ion"), joined("it", "em"), joined("rou", "nd"),
        joined("ga", "te"), joined("verd", "ict"), joined("rul", "ed"), joined("lea", "d"),
        joined("review", "er"), joined("plan ", "file"),
    )

    private fun wordPattern(word: String) =
        Regex("(?<![A-Za-z0-9-])" + Regex.escape(word) + "(?![A-Za-z0-9-])", RegexOption.IGNORE_CASE)

    private val constants: Map<String, String> = mapOf(
        "ACTION" to SignInStartTexts.ACTION,
        "HINT" to SignInStartTexts.HINT,
        "NO_SERVER_LINE" to SignInStartTexts.NO_SERVER_LINE,
        "OPENING" to SignInStartTexts.OPENING,
        "WAITING" to SignInStartTexts.WAITING,
        "REFUSED_NO_SERVER" to SignInStartTexts.REFUSED_NO_SERVER,
        "REFUSED_BAD_ADDRESS" to SignInStartTexts.REFUSED_BAD_ADDRESS,
        "RETURNED_NOT_FINISHED" to SignInStartTexts.RETURNED_NOT_FINISHED,
        "RETURNED_FINISHED" to SignInStartTexts.RETURNED_FINISHED,
    )

    private fun breaks(named: Map<String, String>): List<String> = buildList<String> {
        for ((name, text) in named) {
            if (text.isBlank()) add("$name is blank")
            if (text.any { it.code > highestAscii }) add("$name has a character that is not ASCII")
            for (word in bannedWords) {
                if (wordPattern(word).containsMatchIn(text)) add("$name contains the word $word")
            }
        }
    }

    private fun hostSource(): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/ui/render/SignInStartHostView.kt")
        assertTrue("the host source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `every sign-in text is plain ASCII and not blank and free of forbidden words`() {
        val serverLine = mapOf("serverLine" to SignInStartTexts.serverLine("http://192.0.2.10:8080"))
        assertEquals("problems in the texts", emptyList<String>(), breaks(constants + serverLine))
    }

    @Test
    fun `no sign-in text names a link and no sign-in text names a key or a code`() {
        for ((name, text) in constants) {
            assertTrue("$name names a link", !text.contains("://") && !text.contains("#"))
            assertTrue(
                "$name names a key or a code",
                !text.contains("app_key") && !text.contains("challenge") && !text.contains("code"),
            )
        }
    }

    @Test
    fun `each sign-in text reads exactly as the contract states it`() {
        assertEquals("Sign in with the website", SignInStartTexts.ACTION)
        assertEquals("You sign in on the website, then come back to Breaker.", SignInStartTexts.HINT)
        assertEquals("No server set", SignInStartTexts.NO_SERVER_LINE)
        assertEquals("Opening the browser", SignInStartTexts.OPENING)
        assertEquals("Waiting for the website", SignInStartTexts.WAITING)
        assertEquals("Set the server address first", SignInStartTexts.REFUSED_NO_SERVER)
        assertEquals("That server address cannot be opened", SignInStartTexts.REFUSED_BAD_ADDRESS)
        assertEquals("Back from the website. Sign-in is not finished yet.", SignInStartTexts.RETURNED_NOT_FINISHED)
        assertEquals("Signed in", SignInStartTexts.RETURNED_FINISHED)
    }

    @Test
    fun `the server line shows the address with its outer spaces removed`() {
        assertEquals("Server: http://192.0.2.10:8080", SignInStartTexts.serverLine("  http://192.0.2.10:8080  "))
        assertEquals("Server: https://sync.example.test/breaker", SignInStartTexts.serverLine("https://sync.example.test/breaker"))
    }

    @Test
    fun `a blank server address shows the no-server line`() {
        assertEquals(SignInStartTexts.NO_SERVER_LINE, SignInStartTexts.serverLine(""))
        assertEquals(SignInStartTexts.NO_SERVER_LINE, SignInStartTexts.serverLine("   "))
    }

    @Test
    fun `each state shows its own line and idle shows none`() {
        assertEquals(null, SignInStartTexts.stateLine(SignInStartState.Idle))
        assertEquals(SignInStartTexts.OPENING, SignInStartTexts.stateLine(SignInStartState.Opening))
        assertEquals(SignInStartTexts.WAITING, SignInStartTexts.stateLine(SignInStartState.Waiting))
        assertEquals(SignInStartTexts.REFUSED_NO_SERVER, SignInStartTexts.stateLine(SignInStartState.RefusedNoServer))
        assertEquals(SignInStartTexts.REFUSED_BAD_ADDRESS, SignInStartTexts.stateLine(SignInStartState.RefusedAddress))
        assertEquals(SignInStartTexts.RETURNED_NOT_FINISHED, SignInStartTexts.stateLine(SignInStartState.ReturnedNotFinished))
        assertEquals(SignInStartTexts.RETURNED_FINISHED, SignInStartTexts.stateLine(SignInStartState.ReturnedFinished))
    }

    @Test
    fun `the node ids name no link and no code`() {
        val ids = listOf(SignInStartNodeIds.START, SignInStartNodeIds.SERVER, SignInStartNodeIds.HINT, SignInStartNodeIds.STATE)
        for (id in ids) {
            assertTrue("$id names a link or a code", !id.contains("link") && !id.contains("code"))
        }
        assertEquals("signin.start", SignInStartNodeIds.START)
    }

    @Test
    fun `the host view is a frame layout over the control and not a scroll view`() {
        val header = Regex("""\) : FrameLayout\(context\), SignInStartControl \{""")
        assertTrue("the host header is not a frame layout over the control", header.containsMatchIn(hostSource()))
    }

    @Test
    fun `the host draws exactly three labels and one action`() {
        val source = hostSource()
        assertEquals("labels drawn by the host", 3, Regex("""\bLabel\(""").findAll(source).count())
        assertEquals("actions drawn by the host", 1, Regex("""\bAction\(""").findAll(source).count())
    }

    @Test
    fun `the server address is read at each drawing and never kept in a field`() {
        val source = hostSource()
        assertEquals("calls that read the server address", 1, Regex("""serverUrl\(\)""").findAll(source).count())
        assertTrue("the server address is copied into a field", !source.contains("= serverUrl()"))
    }
}
