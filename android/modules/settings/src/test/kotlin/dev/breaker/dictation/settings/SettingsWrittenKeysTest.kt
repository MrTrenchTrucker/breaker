package dev.breaker.dictation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/**
 * Two things nothing was checking.
 *
 * **The credential-key guard, proven able to fire.** It used to intersect a
 * saved file's keys against a set that had already been filtered down to the
 * settings keys - a set none of which contain `api`, `key` or `token`, so
 * the set was empty and every assertion built on it passed unconditionally. A
 * guard that cannot fail is not a guard, and a green suite that has tested
 * nothing is worse than a red one because it is believed. The guard is now
 * handed the keys **parsed out of a file on disk** and matches `api`, `key`,
 * `token`, `secret` or `password`, case-insensitively; the test below plants
 * keys in a file the module did not write and requires the guard to report
 * them.
 *
 * **What `writtenKeys()` promises.** It is called by no other test, and its own
 * doc claims it is "used by tests to prove nothing extra is written" — which
 * was false until now. The second test is that proof, and it compares three
 * ways: the keys parsed from the saved file, `writtenKeys()`, and a
 * hand-written list of the eleven names as string literals. The hand-written
 * list is the part a two-sided comparison cannot supply: if `writtenKeys()`
 * were derived from the same settings the codec encodes, a codec that dropped a
 * blank `server_url` in both places would satisfy a file-vs-`writtenKeys()`
 * comparison on its own.
 */
class SettingsWrittenKeysTest : SettingsFileStoreTestBase() {

    /**
     * The eleven key names, written out by hand.
     *
     * Deliberately not `THE_SAVED_KEYS` and not `writtenKeys()`: both of those
     * live in the same shape of knowledge as the code under test, so a codec
     * that lost a key on the way out would lose it on both sides of the
     * comparison and the test would still pass. Literals break that circle.
     */
    private val theTenNamesByHand: Set<String> = setOf(
        "mode",
        "model_size",
        "server_url",
        "shake_wake_mode",
        "show_tile_on_keyboard",
        "tile_position",
        "language",
        "preload_model",
        "formatting_enabled",
        "theme_mode",
        "microphone_asked",
    )

    /**
     * A guard that cannot fire is not a guard.
     *
     * The file below is written here, not by the module, and carries three
     * planted keys. `api_key` is one the old `api`/`key`/`token` list also
     * reported. `app_secret` spells `secret`, which the old list did not carry,
     * so it is the plant that shows the wider list. `SESSION_TOKEN` spells
     * `token` in upper case, so it is the plant that shows the match ignores
     * case: a case-sensitive version of the current list would still report the
     * two lower-case plants and miss only this one.
     *
     * Against the old list (which was already case-insensitive), only the
     * `app_secret` assertion fails. Against a case-sensitive version of the
     * current list, only the `SESSION_TOKEN` assertion fails. Against the
     * current check, all three pass.
     *
     * Honest limit: this proves the guard **detects** a credential-shaped key in
     * a saved file. It is not a proof the writer *prevents* one; that is what
     * the three call sites now ask at save time.
     */
    @Test
    fun `the credential guard reports a planted key in a file the module did not write`() {
        val props = Properties()
        theTenNamesByHand.forEach { props.setProperty(it, "irrelevant") }
        props.setProperty("api_key", "PLANTED")
        props.setProperty("app_secret", "PLANTED")
        props.setProperty("SESSION_TOKEN", "PLANTED")
        val file = newFile()
        file.outputStream().use { props.store(it, "planted") }

        // Parsed the way the module's own reader parses: read the file from
        // disk, load it as properties, take the key names. Not a substring
        // search over the raw text — that would miss a key split across a line
        // continuation and would also match inside a value.
        val savedKeys = rawPropertiesOf(file).stringPropertyNames()

        val reported = credentialLikeKeysIn(savedKeys)

        assertTrue(
            "the guard reported nothing for $savedKeys, so it cannot fire",
            reported.isNotEmpty(),
        )
        assertTrue(
            "the guard missed api_key in $reported",
            reported.contains("api_key"),
        )
        assertTrue(
            "the guard missed app_secret in $reported: this is the key the " +
                "earlier api/key/token filter could not see",
            reported.contains("app_secret"),
        )
        assertTrue(
            "the guard missed SESSION_TOKEN in $reported: it spells its " +
                "substring in upper case, and only a case-insensitive check " +
                "sees that",
            reported.contains("SESSION_TOKEN"),
        )
    }

    /**
     * The exemption is one exact, case-sensitive name. Each planted key below
     * checks one way the exemption could go wrong:
     *
     * - show_tile_on_keyboard is the switch itself; it must not be reported.
     * - show_tile_on_keyboard_token starts with the switch name and adds a
     *   credential word; a prefix match would hide it.
     * - app_show_tile_on_keyboard ends with the switch name and adds a prefix;
     *   a suffix match would hide it.
     * - keyboard_api_key shares only the word "keyboard" with the switch name;
     *   a match on that word alone would hide it.
     * - SHOW_TILE_ON_KEYBOARD_KEY is a credential-shaped name in upper case; the
     *   substring match must still report it.
     * - SHOW_TILE_ON_KEYBOARD is the switch name in upper case; an ignore-case
     *   comparison would hide it.
     */
    @Test
    fun `the credential guard allows only the keyboard switch key by exact name`() {
        val props = Properties()
        props.setProperty("show_tile_on_keyboard", "true")
        props.setProperty("show_tile_on_keyboard_token", "PLANTED")
        props.setProperty("app_show_tile_on_keyboard", "PLANTED")
        props.setProperty("keyboard_api_key", "PLANTED")
        props.setProperty("SHOW_TILE_ON_KEYBOARD_KEY", "PLANTED")
        props.setProperty("SHOW_TILE_ON_KEYBOARD", "PLANTED")
        val file = newFile()
        file.outputStream().use { props.store(it, "planted") }

        val reported = credentialLikeKeysIn(rawPropertiesOf(file).stringPropertyNames())

        assertEquals(
            "the guard must report exactly the planted keys other than show_tile_on_keyboard, got $reported",
            setOf(
                "show_tile_on_keyboard_token",
                "app_show_tile_on_keyboard",
                "keyboard_api_key",
                "SHOW_TILE_ON_KEYBOARD_KEY",
                "SHOW_TILE_ON_KEYBOARD",
            ),
            reported,
        )
        assertTrue(
            "show_tile_on_keyboard was reported: $reported",
            !reported.contains("show_tile_on_keyboard"),
        )
    }

    /**
     * `writtenKeys()` is a real contract, checked from both ends and from the
     * middle.
     *
     * Saved with a blank `server_url`, because a blank value is exactly the
     * case a codec can quietly drop: the key is absent-but-harmless, so nothing
     * downstream notices and no other test fails. Every other key is at a
     * non-default value, so "all eleven present" is not proven by a file of eleven
     * blanks — presence and value are asserted separately.
     */
    @Test
    fun `writtenKeys matches the saved file and the hand written names`() {
        val file = newFile()
        val saved = validSettings().copy(serverUrl = "", microphoneAsked = true)

        store(file).save(saved)

        val raw = rawPropertiesOf(file)
        val fileKeys = raw.stringPropertyNames()

        // Side one: the file's own keys, parsed from disk.
        assertEquals(
            "the keys parsed from the saved file",
            SettingsPropertiesCodec.writtenKeys(),
            fileKeys,
        )
        // Side two: the hand-written literals, which is what stops the
        // comparison above from agreeing with itself.
        assertEquals(
            "the hand-written key names",
            theTenNamesByHand,
            fileKeys,
        )

        // Present *and* empty: the mutant this guards is a dropped key, and a
        // key reported present with no value would satisfy the two assertions
        // above on its own.
        assertTrue("server_url was dropped from the file", fileKeys.contains("server_url"))
        assertEquals("server_url must be written blank", "", raw.getProperty("server_url"))

        // Second, independent witness: the raw bytes carry the line itself, so
        // the claim does not rest on the parser agreeing with the writer.
        val rawText = file.readText(Charsets.UTF_8)
        assertTrue(
            "the saved file has no empty server_url= line:\n$rawText",
            rawText.lines().any { it == "server_url=" },
        )

        // The other ten at values a file of blanks could not produce.
        assertEquals("microphone_asked", "true", raw.getProperty("microphone_asked"))
        assertEquals("mode", "SERVER", raw.getProperty("mode"))
        assertEquals("model_size", "medium", raw.getProperty("model_size"))
        assertEquals("shake_wake_mode", "OFF", raw.getProperty("shake_wake_mode"))
        assertEquals("show_tile_on_keyboard", "false", raw.getProperty("show_tile_on_keyboard"))
        assertEquals("tile_position", "0.25,0.75", raw.getProperty("tile_position"))
        assertEquals("language", "de", raw.getProperty("language"))
        assertEquals("preload_model", "false", raw.getProperty("preload_model"))
        assertEquals("formatting_enabled", "false", raw.getProperty("formatting_enabled"))
        assertEquals("theme_mode", "DARK", raw.getProperty("theme_mode"))
    }
}
