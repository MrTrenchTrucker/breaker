package dev.breaker.dictation.ui.theme

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six cases of the Home theme's one resolution, and the mode the mic is handed.
 *
 * The Home view resolves its theme once, through [storedTheme], and the mode it draws with is the mode
 * the mic is handed. This pins the resolution against the module-visible [shownMode] and [storedTheme] —
 * the same functions the page uses, so a second copy of the rule cannot pass by accident — and pins the
 * two cases where the mic must NOT follow the phone.
 *
 * GREEN today by construction: the resolution is already right. This is the guard that keeps it right
 * when the fix wires the mic to it, and the contract the delivery is checked against. It is not a RED.
 */
class HomeMicThemeResolutionTest {

    private fun storeWith(mode: StoredThemeMode) = FakeSettingsStore(NONDEFAULT.copy(themeMode = mode))

    private class Row(val stored: StoredThemeMode, val phoneDark: Boolean, val shown: ShownThemeMode)

    /** The six cases, written out here apart from the code: stored choice x phone mode -> scheme drawn. */
    private val rows = listOf(
        Row(StoredThemeMode.SYSTEM, true, ShownThemeMode.DARK),
        Row(StoredThemeMode.SYSTEM, false, ShownThemeMode.LIGHT),
        Row(StoredThemeMode.LIGHT, true, ShownThemeMode.LIGHT),
        Row(StoredThemeMode.LIGHT, false, ShownThemeMode.LIGHT),
        Row(StoredThemeMode.DARK, true, ShownThemeMode.DARK),
        Row(StoredThemeMode.DARK, false, ShownThemeMode.DARK),
    )

    /** The scheme the phone's own night flag would give, which the mic must not be handed. */
    private fun phonesOwn(phoneDark: Boolean): ShownThemeMode = if (phoneDark) ShownThemeMode.DARK else ShownThemeMode.LIGHT

    @Test
    fun `the six cases resolve as written, through shownMode and through the shared reader`() {
        assertEquals(StoredThemeMode.entries.toSet(), rows.map { it.stored }.toSet())
        assertEquals(6, rows.size)
        for (row in rows) {
            val where = "stored ${row.stored}, phone dark ${row.phoneDark}"
            assertEquals(where, row.shown, shownMode(row.stored, row.phoneDark))
            assertEquals(where, row.shown, storedTheme(storeWith(row.stored), row.phoneDark).mode)
        }
    }

    @Test
    fun `the mic is handed the page's resolution, never the phone's own reading`() {
        for (row in rows) {
            val where = "stored ${row.stored}, phone dark ${row.phoneDark}"
            val handed = storedTheme(storeWith(row.stored), row.phoneDark).mode
            assertEquals(where, row.shown, handed)
            if (row.stored != StoredThemeMode.SYSTEM && phonesOwn(row.phoneDark) != row.shown) {
                // A stored LIGHT or DARK wins over the phone: the two cases the defect got wrong.
                assertNotEquals(where, phonesOwn(row.phoneDark), handed)
            }
        }
    }

    @Test
    fun `a SYSTEM choice is resolved to a drawable scheme, never handed as the stored word`() {
        for (phoneDark in listOf(true, false)) {
            val handed = storedTheme(storeWith(StoredThemeMode.SYSTEM), phoneDark).mode
            assertEquals(
                "SYSTEM on a ${if (phoneDark) "dark" else "light"} phone",
                phonesOwn(phoneDark),
                handed,
            )
            assertTrue("SYSTEM was handed unresolved", handed in listOf(ShownThemeMode.LIGHT, ShownThemeMode.DARK))
        }
    }
}
