package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * The settings store, backed by a [java.util.Properties] file on disk.
 *
 * **Construction.** Both the [file] and the [keystore] port are required and
 * have no default: `SettingsNoFallbackTest` asserts that a store cannot be
 * built without the secret port, so there is no constructor path that quietly
 * persists settings while dropping the credential reference. There is
 * deliberately no secondary constructor and no no-argument factory.
 *
 * **What this store holds.** Nine property keys of plain settings. It holds
 * **no secret**, and it does not put the key reference in the file either —
 * but it does persist that reference, through the [keystore] port rather than
 * on disk. [save] hands `apiKeyRef` to [Keystore.setActiveRef] and [load] reads
 * it back from [Keystore.activeRef], which is what lets a saved reference
 * survive a restart at all; `SettingsKeystoreRefTest` proves both directions
 * and proves the written file still contains neither the reference nor a key.
 * The reference is a non-secret handle, so keeping it here leaks nothing — and
 * note what is not implemented yet: a component that holds a secret at all.
 * [Keystore] can only carry the reference, and the device Keystore that would
 * hold the secret does not exist here.
 *
 * **Round trip.** Values must survive a restart, which is what a store is for:
 * a second instance over the same [file] and [keystore] reads back what the
 * first one saved. Every key is saved at a non-default value in the tests, the
 * three booleans included — they default to true, so saving false is the only
 * way the test can tell a stored `false` from an absent key.
 *
 * **Missing file.** When [file] is not there — the first-run case, not an
 * error — [load] yields core's own defaults for the nine file-backed keys *and*
 * the reference the [keystore] port remembers. A file that does not exist says
 * nothing about the credential, so the port answers for it;
 * `SettingsDefaultsTest.a missing file still reports the reference the port
 * remembers` is the proof, and it asserts the nine defaults in the same breath.
 *
 * **IO failures propagate.** [save] does not catch, swallow or log-and-continue
 * an [IOException]: a settings write that silently failed would leave the user
 * believing a change was kept. A caller that wants a fallback catches it
 * itself, having been told it happened. [load] holds to the same rule — see
 * [readProperties].
 *
 * **Character set: UTF-8, symmetric.** Writes go out through an
 * [OutputStreamWriter] over [StandardCharsets.UTF_8] and come back through a
 * matching [InputStreamReader]. [Properties.store] escapes what it must and
 * writes the rest literally, so non-ASCII text in a language tag or a URL
 * survives a round trip; the same choice is made on both sides on purpose.
 * Decoding uses [Properties.load] and never `org.json`, whose JVM stubs would
 * make the tests pass or fail for reasons that have nothing to do with this
 * code.
 */
class SettingsFileStore(
    val file: File,
    val keystore: Keystore,
) : SettingsStore {

    /**
     * The stored settings, or core's defaults for the nine file-backed keys when
     * nothing is stored yet.
     *
     * A missing [file] yields core's defaults for those nine keys *and* the
     * reference the [keystore] port remembers — the file cannot supply the
     * reference, so the port does, on this path and on every other one. A
     * present but damaged file is read key by key: an unparsable value, one
     * core rejects, or a logical line the format's own parser rejects costs
     * that key its default and nothing else — `SettingsFallbackIsolationTest`
     * for the first two and `SettingsDamagedTextTest` for the third.
     *
     * The credential reference is not in the file, so it comes from the
     * [keystore] port — the only place it was ever written. It is read once,
     * before the file is touched, so no condition below can substitute another
     * source for it; `SettingsDefaultsTest.a missing file still reports the
     * reference the port remembers` and its empty-file twin are the proof.
     */
    override fun load(): AppSettings {
        // The port is the single authority for the reference, asked on every
        // path — a missing or empty file has nothing to say about it, so it must
        // not be allowed to answer instead.
        val ref = keystore.activeRef()
        if (!file.exists()) return SettingsPropertiesCodec.decode(Properties(), apiKeyRef = ref)
        return SettingsPropertiesCodec.decode(readProperties(), apiKeyRef = ref)
    }

    /**
     * The file's properties, assembled one logical line at a time.
     *
     * [Properties.load] reads a document whole or not at all: a single truncated
     * `\uXXXX` escape raises `IllegalArgumentException` out of the call, taking every
     * key in the file with it rather than the one that is damaged. So the text
     * is split into the format's LOGICAL lines here and each is parsed on its
     * own into a throwaway [Properties]; a logical line the parser rejects costs
     * that key — and the continuation lines that belong to it — and nothing else.
     * `SettingsDamagedTextTest` plants the bad escape first and last in the file
     * and requires the other eight keys both times.
     *
     * **A byte-order mark is not a key.** One leading U+FEFF is dropped first.
     * Left in place it sticks to the first key's NAME, which is then a name this
     * module does not know, and the first setting silently reverts to its
     * default while every later one loads.
     *
     * **Only a malformed line is caught.** Reading the file still propagates an
     * [IOException]: an unreadable file is an error, not a damaged key, and
     * swallowing it would report defaults for settings nobody asked to reset.
     */
    private fun readProperties(): Properties {
        val text = InputStreamReader(file.inputStream(), StandardCharsets.UTF_8).use { it.readText() }
        val merged = Properties()
        for (logicalLine in logicalLines(text.removePrefix(BOM))) {
            val parsed = parseLogicalLine(logicalLine) ?: continue
            merged.putAll(parsed)
        }
        return merged
    }

    /**
     * [text] split the way the format defines a line: at any of the three
     * terminators the format accepts (see [LINE_TERMINATOR]), except that a
     * physical line ending in an odd number of backslashes continues onto the
     * next one and loses that final backslash.
     *
     * **A continuation is joined across whichever terminator came before it.**
     * The rule is applied to the piece, not to a character the piece was cut
     * on, so a value wrapped at a bare CR joins exactly as one wrapped at `\n`
     * does; only the split in [LINE_TERMINATOR] knows about terminators.
     *
     * **A comment or a blank line never continues**, however many backslashes it
     * ends with. The format settles comment-ness when a logical line starts — a
     * comment is a line whose first non-whitespace character is `#` or `!`, and a
     * blank line is one that is nothing but whitespace — and then reads such a
     * line to its end and stops. A reader that applied the backslash rule to
     * them too would join the next line onto a comment, and the joined result
     * would read as the comment it starts with, so the key on the line after it
     * would be gone with nothing to show for it. Getting this wrong would split a
     * wrapped value and drop every key after it, so it is exercised directly by
     * `SettingsDamagedTextTest`, which covers both directions: a comment ending
     * in a backslash and one that does not.
     */
    private fun logicalLines(text: String): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (physical in text.split(LINE_TERMINATOR)) {
            // Comment-ness is settled where a logical line STARTS. Inside a
            // continued line a `#` is part of the value — the format has
            // already passed its comment decision — so the exception below is
            // scoped to `current.isEmpty()` and a wrapped value keeps its `#`.
            if (current.isEmpty() && neverContinues(physical)) {
                lines += physical
                continue
            }
            // A continued line's leading whitespace is layout, not value: the
            // format drops it, so it is dropped here too.
            current.append(
                if (current.isEmpty()) physical
                else physical.trimStart(' ', '\t', '\u000C'),
            )
            if (endsWithUnescapedBackslash(current)) {
                current.deleteCharAt(current.length - 1)
            } else {
                lines += current.toString()
                current.setLength(0)
            }
        }
        // Reached when the file ends mid-continuation: that partial line is a
        // line the file does describe, so it is read like any other.
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /**
     * Whether [line] is a comment or blank, and so ends at its own end whatever
     * backslashes it finishes with.
     *
     * Leading whitespace is not what makes a line a comment, so it is looked
     * through; the format accepts an indented `#` or `!` as the start of one.
     */
    private fun neverContinues(line: String): Boolean {
        val content = line.trimStart(' ', '\t', '\u000C')
        return content.isEmpty() || content[0] == '#' || content[0] == '!'
    }

    /** Whether [line] ends in a backslash that escapes the line break after it. */
    private fun endsWithUnescapedBackslash(line: CharSequence): Boolean {
        var backslashes = 0
        for (index in line.length - 1 downTo 0) {
            if (line[index] != '\\') break
            backslashes++
        }
        return backslashes % 2 == 1
    }

    /** [logicalLine] parsed by itself, or null when the format's parser rejects it. */
    private fun parseLogicalLine(logicalLine: String): Properties? = try {
        Properties().also { it.load(StringReader(logicalLine)) }
    } catch (malformedLine: IllegalArgumentException) {
        // One damaged logical line — a truncated escape is what a half-written
        // file leaves — costs the key on that line and nothing else.
        null
    }

    /**
     * Write [settings] to [file], replacing what was there, and point the
     * [keystore] at the settings' credential reference.
     *
     * The reference is handed over after the file is written, so a store that
     * persisted it first and then failed to write would leave the port
     * claiming a save that never happened.
     *
     * **Propagates.** An [IOException] from opening or writing the file leaves
     * this method, uncaught and unswallowed.
     */
    override fun save(settings: AppSettings) {
        val properties = SettingsPropertiesCodec.encode(settings)
        file.parentFile?.mkdirs()
        OutputStreamWriter(
            file.outputStream(),
            StandardCharsets.UTF_8,
        ).use { writer ->
            properties.store(writer, HEADER_COMMENT)
        }
        keystore.setActiveRef(settings.apiKeyRef)
    }

    private companion object {
        /** Properties comment on save; a timestamp follows it on disk. */
        const val HEADER_COMMENT = "Breaker settings"

        /** A UTF-8 byte-order mark, carried by some editors and none of our keys. */
        const val BOM = "\uFEFF"

        /**
         * Where a physical line ends: CRLF, a lone CR, or a lone LF — all three
         * are terminators to `java.util.Properties`, so all three are terminators
         * here too.
         *
         * **THE ORDER OF THE ALTERNATION IS LOAD-BEARING: CRLF FIRST.**
         * These alternatives are tried left to right at each position and the
         * first match wins, so with a bare-CR alternative first the CR of a
         * CRLF would match on its own, and the LF after it would then match as
         * a terminator of its own — so what follows is an EMPTY piece, not a
         * piece that begins with a stray LF. That empty piece is emitted as a
         * line of its own, so every CRLF-terminated entry is followed by a
         * BLANK line. A blank line costs no key, which is what makes it a quiet
         * defect — but it also ends the continuation that reaches it, so a
         * joined address is cut short and the remainder becomes a line of its
         * own, the same damage as any other blank line. That would be a
         * regression introduced while fixing the lone-CR case this regex exists
         * to handle.
         *
         * Written as one alternation with the order on its face rather than as a
         * chain of `removeSuffix` calls, so the invariant is readable here
         * instead of being spread across three statements.
         */
        val LINE_TERMINATOR = Regex("\r\n|\r|\n")
    }
}
