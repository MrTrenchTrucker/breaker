package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The tile pair: what the range guard accepts, what a malformed pair costs, and
 * what a round trip does under a locale whose decimal separator is not a dot.
 */
class SettingsTilePositionTest : SettingsFileStoreTestBase() {

    @Test
    fun `a tile fraction above one in the file cannot reach the loaded settings`() {
        val loaded = store(fileWith(KEY_TILE_POSITION, "1.5,0.25")).load()

        assertEquals("tile x", AppSettings().tilePosition.x, loaded.tilePosition.x, 0f)
        assertEquals("tile y", AppSettings().tilePosition.y, loaded.tilePosition.y, 0f)
    }

    @Test
    fun `a tile fraction below zero in the file cannot reach the loaded settings`() {
        val loaded = store(fileWith(KEY_TILE_POSITION, "0.25,-0.5")).load()

        assertEquals("tile x", AppSettings().tilePosition.x, loaded.tilePosition.x, 0f)
        assertEquals("tile y", AppSettings().tilePosition.y, loaded.tilePosition.y, 0f)
    }

    @Test
    fun `a tile at the exact bounds is accepted`() {
        val loaded = store(fileWith(KEY_TILE_POSITION, "0,1")).load()

        assertEquals(0f, loaded.tilePosition.x, 0f)
        assertEquals(1f, loaded.tilePosition.y, 0f)
    }

    /**
     * A tile position with a third number falls back rather than keeping two of
     * the three. The format stores the pair as `x,y`, so a third part means the
     * value is not the pair this key describes; the guard that rejects it had
     * no test, which is how a weakening of it went unnoticed.
     */
    @Test
    fun `a tile position with a third number falls back instead of keeping the first two`() {
        val loaded = store(fileWith(KEY_TILE_POSITION, "0.25,0.75,0.9")).load()

        assertEquals("tile x", AppSettings().tilePosition.x, loaded.tilePosition.x, 0f)
        assertEquals("tile y", AppSettings().tilePosition.y, loaded.tilePosition.y, 0f)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_TILE_POSITION)
    }

    /**
     * A value that names a float but not a usable one falls back. `NaN` and both
     * infinities parse, so the unparsable-value guard never sees them and the
     * range check rejects them anyway — leaving the finite check unobservable.
     * It is pinned here, on the function that owns it.
     */
    @Test
    fun `a float value that is not finite falls back`() {
        for (raw in listOf("NaN", "Infinity", "-Infinity")) {
            assertEquals(raw, 0.5f, SettingsValidation.floatOrFallback(raw, 0.5f), 0f)
        }
    }

    // --- tile fractions must not depend on the device locale ---

    /**
     * The default locale is JVM-global state shared with every other test in
     * this module, so it is captured before and restored after, and the
     * round trip itself is wrapped so a failure mid-test cannot leave the
     * locale changed for whatever runs next.
     */
    private var originalLocale: Locale = Locale.getDefault()

    @Before
    fun rememberLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    /**
     * Saves and reloads a tile under a locale whose decimal separator is a
     * comma.
     *
     * A store that formats the fractions through the default locale writes
     * "0,25" here, which cannot be read back as 0.25 — the read either throws
     * or quietly yields the centred default. Both outcomes fail this test,
     * because the value saved is off-centre (0.25/0.75) and the assertion is on
     * that value: the centred fallback can never satisfy it.
     *
     * It kills locale-DEPENDENT formatting, and only that. An implementation
     * that pins its formatting to a fixed locale writes "0.25" on every device
     * and is correct by construction — there is nothing here for a test to
     * detect, and nothing is claimed about it.
     */
    @Test
    fun `tile fractions survive a round trip under a comma-decimal locale`() {
        Locale.setDefault(Locale.GERMANY)

        val file = newFile()
        val reloaded = try {
            store(file).save(validSettings(tile = TilePosition(0.25f, 0.75f)))
            store(file).load()
        } catch (thrown: RuntimeException) {
            fail("reading back a tile saved under Locale.GERMANY threw $thrown; the saved " +
                "fraction must not depend on the device locale")
            throw AssertionError(thrown)
        }

        assertEquals("tile x", 0.25f, reloaded.tilePosition.x, 0f)
        assertEquals("tile y", 0.75f, reloaded.tilePosition.y, 0f)
    }

    /**
     * The same round trip under the locale it happened to start in, so a test
     * that passes everywhere is not mistaken for one that passes only where the
     * default locale is English.
     */
    @Test
    fun `tile fractions survive a round trip under a dot-decimal locale`() {
        Locale.setDefault(Locale.US)

        val file = newFile()
        store(file).save(validSettings(tile = TilePosition(0.25f, 0.75f)))
        val reloaded = store(file).load()

        assertEquals("tile x", 0.25f, reloaded.tilePosition.x, 0f)
        assertEquals("tile y", 0.75f, reloaded.tilePosition.y, 0f)
    }
}
