package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the store refuses to construct, and what it refuses to persist.
 *
 * `AppSettings.init` and `TilePosition.init` already reject blank strings and
 * out-of-range fractions — that is core's rule, not this module's, and the rule
 * is not re-implemented here. What these tests pin is that the store cannot be
 * the way around them: a value that core would reject never reaches an
 * `AppSettings` and never reaches the file.
 *
 * The fixture is in [SettingsFileStoreTestBase]; the store is constructed as
 * `SettingsFileStore(file, keystore)`.
 */
class SettingsValidationTest : SettingsFileStoreTestBase() {

    // --- core's own rules, asserted so a change to them is visible from here ---

    @Test
    fun `core rejects a blank model size`() {
        val rejected = try {
            AppSettings(modelSize = "  ")
            false
        } catch (expected: IllegalArgumentException) {
            true
        }

        assertTrue("AppSettings accepted a blank modelSize", rejected)
    }

    @Test
    fun `core rejects a blank language`() {
        val rejected = try {
            AppSettings(language = "")
            false
        } catch (expected: IllegalArgumentException) {
            true
        }

        assertTrue("AppSettings accepted a blank language", rejected)
    }

    @Test
    fun `core rejects a tile fraction outside zero to one`() {
        val rejectedOutsideX = try {
            TilePosition(1.5f, 0.5f)
            false
        } catch (expected: IllegalArgumentException) {
            true
        }
        val rejectedOutsideY = try {
            TilePosition(0.5f, -0.1f)
            false
        } catch (expected: IllegalArgumentException) {
            true
        }

        assertTrue("TilePosition accepted x above 1", rejectedOutsideX)
        assertTrue("TilePosition accepted y below 0", rejectedOutsideY)
    }

    // --- the store cannot smuggle a rejected value past those rules ---

    @Test
    fun `a blank model size in the file cannot reach the loaded settings`() {
        val loaded = store(fileWith(KEY_MODEL_SIZE, "   ")).load()

        assertEquals("model_size", AppSettings().modelSize, loaded.modelSize)
    }

    @Test
    fun `a blank language in the file cannot reach the loaded settings`() {
        val loaded = store(fileWith(KEY_LANGUAGE, "")).load()

        assertEquals("language", AppSettings().language, loaded.language)
    }

    // --- whitespace is blank, and one blank key costs that key only ---

    /**
     * The three tests below are one property seen through three keys: a value
     * that is *whitespace* rather than empty is still a value core refuses, so
     * it must fall back like an empty one — and it must cost that key alone.
     *
     * **Why the tests above cannot see it.** They plant `""` and three spaces,
     * and `Properties` skips the whitespace that follows a separator when it
     * reads a value back — so both arrive as the empty string, where a guard
     * that accepts empty and a guard that accepts blank are
     * indistinguishable. Whitespace has to be planted in a form the format
     * carries intact, which is what these do.
     *
     * **Why every other key is asserted.** Asserting only the planted key would
     * be satisfied by a store that threw the whole file away and answered with
     * the defaults — a total loss that passes a one-key assertion. So each of
     * these writes every other key at the non-default value
     * [SettingsFileStoreTestBase.validSettings] describes and checks it survives.
     * `fileWith` writes exactly those values, so that value is both what was
     * written and what must come back.
     *
     * The planted whitespace is a tab rather than spaces because it has to
     * reach the reader intact: `Properties` skips whitespace it finds right
     * after the separator, and only a value written in escaped form is
     * guaranteed to arrive as a non-empty whitespace string. Each test proves
     * that precondition on the file it wrote, so a format that could not carry
     * the value fails loudly here instead of quietly agreeing with both
     * guards.
     */
    @Test
    fun `a whitespace-only language in the file falls back and leaves every other key loaded`() {
        val planted = "\t"
        val file = fileWith(KEY_LANGUAGE, planted)
        assertEquals(
            "the planted whitespace did not survive the file",
            planted,
            propertyOf(file, KEY_LANGUAGE),
        )

        val loaded = store(file).load()

        assertEquals("language", AppSettings().language, loaded.language)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_LANGUAGE)
    }

    @Test
    fun `a whitespace-only model size in the file falls back and leaves every other key loaded`() {
        val planted = "\t"
        val file = fileWith(KEY_MODEL_SIZE, planted)
        assertEquals(
            "the planted whitespace did not survive the file",
            planted,
            propertyOf(file, KEY_MODEL_SIZE),
        )

        val loaded = store(file).load()

        assertEquals("model size", AppSettings().modelSize, loaded.modelSize)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODEL_SIZE)
    }

    /**
     * The same for the one string key whose default is itself blank.
     *
     * `AppSettings.serverUrl` defaults to `""` and `hasServerUrl` is simply
     * `serverUrl.isNotBlank()` — whether a server address has been configured
     * at all, nothing more — so a blank address is a *legal state*, the
     * documented "not configured" default, rather than damage. Falling back is
     * therefore the correct reading of a whitespace address: it lands on the
     * documented "not configured" default rather than shipping the whitespace
     * through as if it were an address.
     */
    @Test
    fun `a whitespace-only server address in the file falls back and leaves every other key loaded`() {
        val planted = "\t"
        val file = fileWith(KEY_SERVER_URL, planted)
        assertEquals(
            "the planted whitespace did not survive the file",
            planted,
            propertyOf(file, KEY_SERVER_URL),
        )

        val loaded = store(file).load()

        // The exact default "" here is what tells isNullOrBlank from isNullOrEmpty.
        assertEquals("server address", AppSettings().serverUrl, loaded.serverUrl)
        // Consistency check only: hasServerUrl is isNotBlank(), false for a tab under either guard.
        assertEquals("a whitespace address is not a configured address", false, loaded.hasServerUrl)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_SERVER_URL)
    }
}
