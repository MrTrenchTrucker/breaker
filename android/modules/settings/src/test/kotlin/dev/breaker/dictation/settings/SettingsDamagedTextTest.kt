package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A line the file's own parser rejects costs that key and no other, and a line
 * the format never continues does not take the next one with it.
 *
 * The files here are text, not `Properties.store` output: these tests are about
 * bytes the store never writes — a truncated escape, a byte-order mark, a
 * continuation, a comment, a backslash inside a value.
 */
class SettingsDamagedTextTest : SettingsFileStoreTestBase() {

    /**
     * The nine keys at non-default values, one per line. Text, not
     * `Properties.store`: the tests below are about bytes `store` never writes —
     * a truncated escape, a byte-order mark, a continuation.
     */
    private val nonDefaultLines = listOf(
        "mode=SERVER", "model_size=medium", "server_url=https://box.local",
        "wake_gesture_enabled=false", "tile_position=0.25,0.75", "language=de",
        "preload_model=false", "formatting_enabled=false", "theme_mode=DARK",
    )

    /** [lines] joined with newlines and written verbatim as UTF-8. */
    private fun fileWithText(lines: List<String>): File =
        newFile().apply { writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8) }

    /**
     * The nine lines minus the language's, with [damaged] in its place at either
     * end. Giving up at the first bad line and at the last are both wrong; only a
     * bad line at each end tells those failures apart.
     */
    private fun fileWithDamagedLine(damaged: String, damagedLast: Boolean = false): File {
        val rest = nonDefaultLines.filterNot { it.startsWith("$KEY_LANGUAGE=") }
        return fileWithText(if (damagedLast) rest + damaged else listOf(damaged) + rest)
    }

    /** Asserts [file] loads every key but the language, which falls back. */
    private fun assertDamagedLanguageCostsOneKey(file: File) {
        val loaded = store(file).load()

        assertEquals("language", AppSettings().language, loaded.language)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_LANGUAGE)
    }

    /**
     * A truncated `\u` escape is what a half-written file leaves behind, and the
     * format's parser answers it by throwing. The store used to hand that throw to
     * its caller, so one damaged line cost all nine keys instead of one; the
     * others are non-default and required back, so answering with the defaults
     * cannot pass. The three below place the damage first, last, and mid-parse.
     */
    @Test
    fun `a malformed escape on the first line costs that key and no other`() =
        assertDamagedLanguageCostsOneKey(fileWithDamagedLine("language=\\u12"))

    /** The same as the last line, where a parser that stops early would hide. */
    @Test
    fun `a malformed escape on the last line costs that key and no other`() =
        assertDamagedLanguageCostsOneKey(fileWithDamagedLine("language=\\u12", damagedLast = true))

    /**
     * The same damage on a value the format wrapped over two physical lines. A
     * logical line is the unit a key is written on, so a bad escape on the second
     * half costs that key and nothing outside it — and a reader that split
     * physical lines would read `\u12` as a key of its own.
     */
    @Test
    fun `a malformed escape on a continued line costs that key and no other`() =
        assertDamagedLanguageCostsOneKey(fileWithDamagedLine("language=\\\n\\u12"))

    /**
     * A value wrapped over two lines reads back whole, and the keys after it
     * arrive — the standing guard on the line handling above: a continuation must
     * be joined before it is read, or a long address silently becomes a short one.
     */
    @Test
    fun `a value continued over two lines is read whole and the file after it survives`() {
        val wrapped = "server_url=https://box.\\\n  local"
        val rest = nonDefaultLines.filterNot { it.startsWith("$KEY_SERVER_URL=") }

        val loaded = store(fileWithText(listOf(wrapped) + rest)).load()

        assertEquals("server_url", "https://box.local", loaded.serverUrl)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_SERVER_URL)
    }

    /**
     * A file opening with a byte-order mark still loads every key, the first one
     * included.
     *
     * The mark is written where a real one lands: bytes `EF BB BF` followed by
     * the first key with **no newline between them**, which is what an editor
     * that emits one actually produces. The raw bytes are asserted so this test
     * cannot drift back into a shape a real file never has — a mark alone on its
     * own line would still pass with the strip removed, because the mark would
     * land on a line of its own and the first key would load either way; that
     * is the shape that let the strip go unnoticed.
     *
     * The mark is not whitespace to the format's parser, so left in place it
     * joins the first key's NAME — one this module does not know — so the first
     * setting reverted to its default while every later key arrived. The other
     * eight are asserted, to rule out a reader that drops the whole file.
     */
    @Test
    fun `a byte-order mark attached to the first key does not cost it`() {
        val file = fileWithText(listOf("\uFEFF${nonDefaultLines.first()}") + nonDefaultLines.drop(1))
        val bytes = file.readBytes()
        // The shape itself, so the test cannot pass for the wrong reason.
        assertEquals("the mark must be byte one", 0xEF.toLong(), bytes[0].toLong() and 0xFF)
        assertEquals("byte two of the mark", 0xBB.toLong(), bytes[1].toLong() and 0xFF)
        assertEquals("byte three of the mark", 0xBF.toLong(), bytes[2].toLong() and 0xFF)
        assertEquals("the first key must follow the mark directly", 'm'.code.toLong(), bytes[3].toLong() and 0xFF)

        val loaded = store(file).load()

        assertEquals("mode", SttMode.SERVER, loaded.mode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODE)
    }

    /**
     * A comment line ending in a backslash does not swallow the key under it.
     *
     * The format settles comment-ness where a logical line starts and then
     * reads the comment to its end, so a trailing backslash on it is text, not
     * a continuation. A reader that applied the backslash rule to comment lines
     * joined the next line onto this one, and the joined result still read as a
     * comment — so `mode` was silently gone, which is the whole failure this
     * module exists to prevent. The other eight are asserted, to rule out a
     * reader that drops the file.
     */
    @Test
    fun `a comment line ending in a backslash does not cost the key under it`() {
        val loaded = store(fileWithText(listOf("# a settings note \\") + nonDefaultLines)).load()

        assertEquals("mode", SttMode.SERVER, loaded.mode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODE)
    }

    /**
     * The same for a comment that is indented before its `#`, which the format
     * accepts: leading whitespace is not what makes a line a comment, so a
     * reader that looked only at the first character would keep continuing on
     * this one and lose `mode` the same way.
     */
    @Test
    fun `an indented comment ending in a backslash does not cost the key under it`() {
        val loaded = store(fileWithText(listOf("   # an indented note \\") + nonDefaultLines)).load()

        assertEquals("mode", SttMode.SERVER, loaded.mode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODE)
    }

    /**
     * The same for `!`, which starts a comment as `#` does — a reader that
     * tested for one of the two would keep continuing on the other and lose
     * `mode` the same way.
     */
    @Test
    fun `a bang comment ending in a backslash does not cost the key under it`() {
        val loaded = store(fileWithText(listOf("! a note \\") + nonDefaultLines)).load()

        assertEquals("mode", SttMode.SERVER, loaded.mode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODE)
    }

    /**
     * A comment line that does NOT end in a backslash still does not swallow the
     * key under it.
     *
     * This is the direction that already worked, and it is written down so the
     * fix above cannot be traded against it. It is worth keeping for its own
     * sake: it pins the INVERSE direction — that a comment carrying no
     * trailing backslash still does not swallow the key under it — which is a
     * real behaviour a future edit to the comment rule could break.
     *
     * CORRECTION: an earlier version of this KDoc went on to say that "a fix
     * that made every comment a continued line would satisfy the test three
     * above and fail this one", naming this test as the trade guard against
     * over-applying the fix. That was false, and it was false in a way worth
     * spelling out, so the claim is not quietly dropped. A comment line with
     * no trailing backslash has nothing to continue: there is no continuation
     * for a too-broad fix to open, so this test has no way to observe whether
     * the fix was over-applied, and it survives such a mutant. Reasoned from
     * the format's rules — no mutant has been run against this file, so no
     * survive-or-kill result here is observed.
     *
     * The tests that ARE the trade guard are the three backslash-carrying
     * comment cases, which are the ones an over-applied fix swallows the next
     * key with: `a comment line ending in a backslash does not cost the key
     * under it`, `an indented comment ending in a backslash does not cost the
     * key under it`, and `a bang comment ending in a backslash does not cost
     * the key under it`. Those three are the guard. This one is not.
     */
    @Test
    fun `a comment line without a backslash does not cost the key under it`() {
        val loaded = store(fileWithText(listOf("# a settings note") + nonDefaultLines)).load()

        assertEquals("mode", SttMode.SERVER, loaded.mode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODE)
    }

    /**
     * A line that is nothing but whitespace costs no key, and takes nothing with
     * it either — the blank half of the same rule the comment tests above cover.
     */
    @Test
    fun `a whitespace-only line costs no key`() {
        val loaded = store(fileWithText(listOf(nonDefaultLines.first(), "   ", "\t") + nonDefaultLines.drop(1))).load()

        assertEquals("mode", SttMode.SERVER, loaded.mode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODE)
    }

    /**
     * A value ending in an **escaped** backslash — two of them — ends there: the
     * count is even, so the line is not a continuation, and the next key has its
     * own line. One backslash in the value survives as itself.
     *
     * Written because nothing else here tells an even count from an odd one:
     * the only continuation in the file above has a single backslash, so a
     * reader that dropped the parity test and continued on any trailing
     * backslash would pass every other test in this class and fail only this
     * one.
     */
    @Test
    fun `a value ending in an escaped backslash ends there and the next key loads`() {
        val rest = nonDefaultLines.filterNot { it.startsWith("$KEY_SERVER_URL=") }
        val loaded = store(fileWithText(listOf("server_url=https://box.\\\\") + rest)).load()

        assertEquals("server_url", "https://box.\\", loaded.serverUrl)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_SERVER_URL)
    }

    /**
     * An escaped backslash inside a value survives as one backslash, so an
     * address carrying one is not shortened or dropped. The other half of the
     * backslash rule: the escape is the format's, and this reader only decides
     * where a line ends.
     */
    @Test
    fun `an escaped backslash inside a value survives`() {
        val rest = nonDefaultLines.filterNot { it.startsWith("$KEY_SERVER_URL=") }
        val loaded = store(fileWithText(listOf("server_url=https://box\\\\local") + rest)).load()

        assertEquals("server_url", "https://box\\local", loaded.serverUrl)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_SERVER_URL)
    }

    /**
     * A backslash before an ordinary character is DROPPED, and the key still
     * loads with the shortened value.
     *
     * This is worth pinning because it is the opposite of what the malformed
     * escape tests above would lead a reader to expect, and both are true at
     * once. The format's parser knows four escapes (`t`, `r`, `n`, `f`) and
     * `u`; for any other character after a backslash its escape branch
     * appends that character with no error, while a truncated `\u` is the only
     * thing on this path that throws. So `server_url=https://box\local` arrives
     * as `https://boxlocal` — silent, but a value, not a lost key. A reader who
     * assumed an unknown escape was rejected would expect the default here and
     * read the passing default as a rejection.
     */
    @Test
    fun `a lone backslash before an ordinary character is dropped and the key still loads`() {
        val rest = nonDefaultLines.filterNot { it.startsWith("$KEY_SERVER_URL=") }
        val loaded = store(fileWithText(listOf("server_url=https://box\\local") + rest)).load()

        assertEquals("server_url", "https://boxlocal", loaded.serverUrl)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_SERVER_URL)
    }

    /**
     * A `#` on a line the format has ALREADY continued is value text, not a
     * comment, and it is neither dropped nor treated as the start of a comment.
     *
     * The format settles comment-ness exactly once, where a logical line
     * starts, and never re-opens that decision for the rest of the logical
     * line. The comment-ness exception in `logicalLines` is therefore scoped to
     * `current.isEmpty()`: inside a continuation the `#` is just another
     * character of a value already being assembled.
     *
     * Every existing comment case in this class puts its comment at the start
     * of a logical line — which is the one place the decision is made. So a
     * reader that checked comment-ness per PHYSICAL line instead would pass all
     * of them and still break here: the second half of the wrapped value would
     * be read as a comment line of its own, and `server_url` would arrive
     * silently truncated to `https://box`. Nothing throws. The file is merely
     * one address short, and every other key looks fine — the same
     * "passes the tests but is still wrong" shape as the defect this round
     * closed.
     *
     * The continuation line carries no leading whitespace on purpose: the
     * format strips a continued line's leading whitespace, so a space here
     * would change the expected string and pin the wrong value.
     *
     * This is the trade guard for the comment tests above: a fix that made
     * EVERY comment line continue, even at a logical line start, would leave
     * this test green and fail `a comment line ending in a backslash does not
     * cost the key under it` instead.
     */
    @Test
    fun `a hash inside a continued value stays value text`() {
        val wrapped = "server_url=https://box\\\n#still-the-value"
        val rest = nonDefaultLines.filterNot { it.startsWith("$KEY_SERVER_URL=") }

        val loaded = store(fileWithText(listOf(wrapped) + rest)).load()

        assertEquals("server_url", "https://box#still-the-value", loaded.serverUrl)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_SERVER_URL)
    }
}
