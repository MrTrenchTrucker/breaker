package dev.breaker.dictation.ui.testing

import dev.breaker.dictation.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** The shared fake store and the non-default settings behave as the other tests rely on. */
class FakeSettingsStoreTest {
    private val edited = NONDEFAULT.copy(language = "fr")

    @Test
    fun `every field of the non-default settings differs from the defaults`() {
        val defaults = AppSettings()
        val fields = AppSettings::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertTrue("fields found: ${fields.map { it.name }}", fields.size >= 10)
        assertTrue(fields.any { it.name == "themeMode" })
        for (field in fields) {
            field.isAccessible = true
            assertNotEquals("field ${field.name}", field.get(defaults), field.get(NONDEFAULT))
        }
    }

    @Test
    fun `load returns the stored settings and counts each call`() {
        val store = FakeSettingsStore(NONDEFAULT)
        assertEquals(NONDEFAULT, store.load())
        assertEquals(NONDEFAULT, store.load())
        assertEquals(2, store.loadAttempts)
        assertEquals(0, store.saveAttempts)
    }

    @Test
    fun `a successful save is stored, counted, listed and logged`() {
        val store = FakeSettingsStore(NONDEFAULT)
        store.events.add("note")
        store.save(edited)
        assertEquals(edited, store.load())
        assertEquals(edited, store.current)
        assertEquals(1, store.saveAttempts)
        assertEquals(listOf(edited), store.saves)
        assertEquals(listOf("note", "save"), store.events)
    }

    @Test
    fun `a failing save counts, lists nothing and leaves the stored settings unchanged`() {
        val store = FakeSettingsStore(NONDEFAULT)
        store.failSave = true
        val thrown = assertThrows(IllegalStateException::class.java) { store.save(edited) }
        assertTrue(thrown.message.orEmpty().contains(FAILURE_MARKER))
        assertTrue(thrown.message.orEmpty().contains(FAILURE_URL))
        assertEquals(1, store.saveAttempts)
        assertEquals(emptyList<AppSettings>(), store.saves)
        assertEquals(emptyList<String>(), store.events)
        assertEquals(NONDEFAULT, store.current)
        assertEquals(NONDEFAULT, store.load())
    }

    @Test
    fun `a failing load counts, throws the marked message and changes nothing`() {
        val store = FakeSettingsStore(NONDEFAULT)
        store.failLoad = true
        val thrown = assertThrows(IllegalStateException::class.java) { store.load() }
        assertTrue(thrown.message.orEmpty().contains(FAILURE_MARKER))
        assertTrue(thrown.message.orEmpty().contains(FAILURE_URL))
        assertEquals(1, store.loadAttempts)
        assertEquals(0, store.saveAttempts)
        assertEquals(NONDEFAULT, store.current)
    }

    @Test
    fun `each failure flag fails only its own call and a cleared flag works again`() {
        val store = FakeSettingsStore(NONDEFAULT)
        store.failLoad = true
        store.save(edited)
        assertEquals(edited, store.current)
        store.failLoad = false
        store.failSave = true
        assertEquals(edited, store.load())
        store.failSave = false
        store.save(NONDEFAULT)
        assertEquals(listOf(edited, NONDEFAULT), store.saves)
    }

    @Test
    fun `replaceStored changes what load returns without counting a save`() {
        val store = FakeSettingsStore(NONDEFAULT)
        store.replaceStored(edited)
        assertEquals(edited, store.load())
        assertEquals(0, store.saveAttempts)
        assertEquals(emptyList<AppSettings>(), store.saves)
    }
}
