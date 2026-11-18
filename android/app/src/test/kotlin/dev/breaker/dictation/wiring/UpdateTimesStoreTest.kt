package dev.breaker.dictation.wiring

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateTimesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun checkFile(): File = File(tmp.root, "update-last-check")

    @Test
    fun `both times read back as the numbers that were recorded`() {
        val store = UpdateTimesStore(tmp.root)
        store.recordCheckAtMs(1_700_000_000_123L)
        store.recordUpdateAtMs(1_700_000_999_000L)
        assertEquals("app: the check time must read back", 1_700_000_000_123L, store.lastCheckAtMs())
        assertEquals("app: the update time must read back", 1_700_000_999_000L, store.lastUpdateAtMs())
    }

    @Test
    fun `a missing file gives null for both times`() {
        val store = UpdateTimesStore(tmp.root)
        assertNull("app: a missing check file must give null", store.lastCheckAtMs())
        assertNull("app: a missing install file must give null", store.lastUpdateAtMs())
    }

    @Test
    fun `an empty file gives null`() {
        checkFile().writeText("")
        assertNull("app: an empty file must give null", UpdateTimesStore(tmp.root).lastCheckAtMs())
    }

    @Test
    fun `garbage content gives null`() {
        checkFile().writeText("garbage")
        assertNull("app: text that is not digits must give null", UpdateTimesStore(tmp.root).lastCheckAtMs())
    }

    @Test
    fun `a value of nineteen digits gives null`() {
        checkFile().writeText("1".repeat(19))
        assertNull("app: nineteen digits must give null", UpdateTimesStore(tmp.root).lastCheckAtMs())
    }

    @Test
    fun `a value of eighteen digits reads back`() {
        checkFile().writeText("9".repeat(18))
        assertEquals("app: eighteen digits must read back", "9".repeat(18).toLong(), UpdateTimesStore(tmp.root).lastCheckAtMs())
    }

    @Test
    fun `a negative sign gives null`() {
        checkFile().writeText("-5")
        assertNull("app: a value with a sign must give null", UpdateTimesStore(tmp.root).lastCheckAtMs())
    }

    @Test
    fun `zero reads back as zero`() {
        val store = UpdateTimesStore(tmp.root)
        store.recordCheckAtMs(0L)
        assertEquals("app: zero must read back as zero", 0L, store.lastCheckAtMs())
    }

    @Test
    fun `a record over an old value replaces it`() {
        val store = UpdateTimesStore(tmp.root)
        store.recordCheckAtMs(5L)
        store.recordCheckAtMs(9L)
        assertEquals("app: the newer record must replace the old value", 9L, store.lastCheckAtMs())
    }

    @Test
    fun `a record into a missing folder creates it`() {
        val store = UpdateTimesStore(File(tmp.root, "made/later"))
        store.recordCheckAtMs(7L)
        assertEquals("app: the record must create its folder and read back", 7L, store.lastCheckAtMs())
    }

    @Test
    fun `a record into a path that is a regular file never throws and reads back null`() {
        val blocker = File(tmp.root, "blocker")
        blocker.writeText("x")
        val store = UpdateTimesStore(blocker)
        store.recordCheckAtMs(3L)
        store.recordUpdateAtMs(4L)
        assertNull("app: a failed write must leave no check time", store.lastCheckAtMs())
        assertNull("app: a failed write must leave no install time", store.lastUpdateAtMs())
    }

    @Test
    fun `the two times do not overwrite each other`() {
        val store = UpdateTimesStore(tmp.root)
        store.recordCheckAtMs(11L)
        store.recordUpdateAtMs(22L)
        assertEquals("app: the check time must keep its value", 11L, store.lastCheckAtMs())
        assertEquals("app: the install time must keep its value", 22L, store.lastUpdateAtMs())
        store.recordCheckAtMs(33L)
        assertEquals("app: a later check record must leave the install time alone", 22L, store.lastUpdateAtMs())
    }

    @Test
    fun `the stored text is the decimal number with no newline`() {
        UpdateTimesStore(tmp.root).recordCheckAtMs(42L)
        assertEquals("app: the file must hold the decimal digits only", "42", checkFile().readText())
    }
}
