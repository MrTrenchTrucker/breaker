package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A physical line ends at CRLF, at a lone CR, or at a lone LF — all three are
 * line terminators to the format — and one damaged logical line costs its own
 * key whatever terminator separated it from its neighbours.
 *
 * These files are text, never `Properties.store` output: `store` always writes
 * LF, so a lone CR can only arrive from a file some other tool wrote. That is
 * exactly why it needs a test — it is a shape the store never produces itself
 * and therefore never exercises on its own.
 *
 * Every case below asserts the affected key AND the keys around it. A lone
 * "this key fell back to its default" assertion is satisfied just as well by a
 * reader that discarded the entire file, which is the failure this module exists
 * to prevent.
 */
class SettingsLineEndingTest : SettingsFileStoreTestBase() {

    /**
     * The nine keys at non-default values, one per logical line, with the
     * language's replaced by [damagedLanguage].
     */
    private fun linesWithDamagedLanguage(damagedLanguage: String): List<String> =
        nonDefaultLines.map { if (it.startsWith("$KEY_LANGUAGE=")) damagedLanguage else it }

    /**
     * [lines] each followed by its own terminator — so the file ends on one,
     * the way a real one does — written verbatim as UTF-8.
     *
     * Written as bytes rather than through `Properties.store`, which cannot
     * produce any of these shapes: it always writes LF.
     */
    private fun fileWithMixedTerminators(
        lines: List<String>,
        terminators: List<String>,
    ): File = newFile().apply {
        require(lines.size == terminators.size) { "one terminator per line, in order" }
        val text = buildString { lines.forEachIndexed { index, line -> append(line).append(terminators[index]) } }
        writeText(text, Charsets.UTF_8)
    }

    /** [lines] joined by [terminator] between each pair and after the last. */
    private fun fileJoined(lines: List<String>, terminator: String): File =
        fileWithMixedTerminators(lines, List(lines.size) { terminator })

    /** The nine keys at non-default values — text, not `Properties.store` output. */
    private val nonDefaultLines = listOf(
        "mode=SERVER", "model_size=large", "server_url=https://box.local",
        "wake_gesture_enabled=false", "tile_position=0.25,0.75", "language=de",
        "preload_model=false", "formatting_enabled=false", "theme_mode=DARK",
    )

    /** The language line as a half-written file leaves it: the escape is truncated. */
    private val truncatedEscapeLanguage = "language=\\u12"

    /**
     * Every key except the two this test spends on damage — the language (a
     * truncated escape) and the address (a value continued across a terminator).
     *
     * Seven is the right count here, and the assertion of it is what makes the
     * count deliberate rather than incidental: a reader that lost the whole file,
     * or lost everything after the damaged line, would leave fewer than seven and
     * fail.
     */
    private fun assertTheSevenUninvolvedKeysArrived(loaded: AppSettings) {
        val written = validSettings()
        assertEquals("mode", written.mode, loaded.mode)
        assertEquals("model size", written.modelSize, loaded.modelSize)
        assertEquals("wake gesture", written.wakeGestureEnabled, loaded.wakeGestureEnabled)
        assertEquals("tile x", written.tilePosition.x, loaded.tilePosition.x, 0f)
        assertEquals("tile y", written.tilePosition.y, loaded.tilePosition.y, 0f)
        assertEquals("preload model", written.preloadModel, loaded.preloadModel)
        assertEquals("formatting", written.formattingEnabled, loaded.formattingEnabled)
        assertEquals("theme mode", written.themeMode, loaded.themeMode)
    }

    /**
     * A file whose lines end at a lone CR, with one of them carrying a truncated
     * `\u` escape: the eight sound keys load at their non-default values and only
     * the damaged one falls back.
     *
     * A CR is a terminator to the format, so a CR-terminated file is nine lines,
     * not one. A reader that split on LF alone saw a single line here, so the
     * truncated escape was no longer one damaged line — it was the whole file,
     * and the store answered with core's defaults for every one of the nine
     * keys. This is the case the split exists for.
     */
    @Test
    fun `keys separated by a lone CR load, and a truncated escape on one of them costs that key alone`() {
        val lines = linesWithDamagedLanguage(truncatedEscapeLanguage)

        val loaded = store(fileJoined(lines, "\r")).load()

        assertEquals("language", AppSettings().language, loaded.language)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_LANGUAGE)
    }

    /**
     * The same nine keys and the same truncated escape, at CRLF.
     *
     * **A guard, not a regression.** This case already worked: a reader that
     * split on LF and then removed one trailing CR per piece handled CRLF
     * correctly before anything here changed, and it handles it now. It is
     * written down because the fix above touches exactly the code that made it
     * work — a fix for lone CR that consumed the CR the CRLF path depended on
     * would be caught here — and because it must stay green on both trees.
     */
    @Test
    fun `keys separated by CRLF still load, and a truncated escape on one of them still costs that key alone`() {
        val lines = linesWithDamagedLanguage(truncatedEscapeLanguage)

        val loaded = store(fileJoined(lines, "\r\n")).load()

        assertEquals("language", AppSettings().language, loaded.language)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_LANGUAGE)
    }

    /**
     * One file that mixes all three terminators, carrying both a truncated
     * escape and a value continued across a CRLF: every other key arrives, and
     * the continued address arrives whole.
     *
     * The continued address is what makes this the ordering guard. Terminating
     * on a lone CR before trying CRLF would match the CR of a CRLF on its own
     * and leave the LF at the head of the next piece, so that piece reads as a
     * line of its own: a blank one directly after the wrapped value, which
     * breaks the join and truncates the address to `https://box.` — silently,
     * with every key in the file still present and every other assertion here
     * still passing. Asserting the joined value is what distinguishes the two
     * orderings; asserting only the key count cannot.
     */
    @Test
    fun `a file mixing all three terminators loads every key and joins a value continued across a CRLF`() {
        val lines = listOf(
            "mode=SERVER",
            "server_url=https://box.\\",
            "  local",
            truncatedEscapeLanguage,
            "model_size=large",
            "wake_gesture_enabled=false",
            "tile_position=0.25,0.75",
            "preload_model=false",
            "formatting_enabled=false",
            "theme_mode=DARK",
        )
        val terminators = listOf(
            "\r\n", "\r\n", "\r", "\n", "\r\n", "\n", "\r\n", "\r", "\n", "\n",
        )

        val loaded = store(fileWithMixedTerminators(lines, terminators)).load()

        assertEquals("language", AppSettings().language, loaded.language)
        assertEquals("server_url", "https://box.local", loaded.serverUrl)
        assertEquals("mode", SttMode.SERVER, loaded.mode)
        assertTheSevenUninvolvedKeysArrived(loaded)
    }

    /**
     * A value line ending in a single backslash, continued onto the next line,
     * where the terminator between them is a lone CR, with a separate key
     * carrying a truncated escape AFTER the joined line: the joined value is
     * correct, the damaged key falls back, and every other key arrives.
     *
     * The continuation rule is about the LINE, not about the character the split
     * happened to cut on: the format joins a line ending in `\` to the next
     * physical line whatever terminated that line, so teaching the split about
     * bare CR must not stop the join from happening at one. Without this case,
     * a reader that split on CR but kept its own idea of where a line ends
     * would stop the address one entry short — `https://box.` — with every key
     * in the file present and every other assertion here green.
     *
     * **Why the damage is here and not left out.** With an intact file this
     * case cannot fail: a reader that split on LF alone hands the whole file to
     * the parser as one logical line, and the parser joins the continuation
     * itself, so the un-fixed reader passes. The malformed escape is what makes
     * the split visible — on a reader that only knows LF, this file is ONE
     * logical line, so the escape costs the whole file rather than the one key
     * it is on. It sits on its own key and after the joined line on purpose:
     * that placement is the only shape that shows both halves at once, the join
     * surviving AND the damage costing exactly one key.
     */
    @Test
    fun `a value continued across a lone CR is joined, and a truncated escape after it costs that key alone`() {
        val lines = listOf(
            "server_url=https://box.\\",
            "  local",
            truncatedEscapeLanguage,
        ) + nonDefaultLines.filterNot {
            it.startsWith("$KEY_SERVER_URL=") || it.startsWith("$KEY_LANGUAGE=")
        }

        val loaded = store(fileJoined(lines, "\r")).load()

        // Exactly right, not merely "joined": a continuation that stopped at
        // `https://box.` also joins, and would pass a looser assertion here.
        assertEquals("server_url", "https://box.local", loaded.serverUrl)
        assertEquals("language", AppSettings().language, loaded.language)
        assertTheSevenUninvolvedKeysArrived(loaded)
    }
}
