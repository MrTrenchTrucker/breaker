package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyHistoryStoreTest {
    private val delegate = RecordingHistoryStore()
    private var supplierCalls = 0

    private val lazyStore = LazyHistoryStore {
        supplierCalls += 1
        delegate
    }

    private fun row(id: String) = Transcription(id, "text", TranscriptionSource.LOCAL, "small", 10L, 1_000L)

    @Test
    fun `building the store does not call the supplier`() {
        assertEquals("app: the supplier ran before any use", 0, supplierCalls)
        assertTrue("app: the delegate was used before any use", delegate.calls.isEmpty())
    }

    @Test
    fun `the first save calls the supplier once and passes the row to the delegate`() {
        val saved = row("a")
        lazyStore.save(saved)
        assertEquals("app: the first save should call the supplier exactly once", 1, supplierCalls)
        assertEquals("app: the delegate should have been saved to once", listOf("save"), delegate.calls)
        assertSame("app: the delegate should receive the very row that was saved", saved, delegate.saved[0])
    }

    @Test
    fun `the first list calls the supplier once, passes the limit and returns the delegate's rows`() {
        val rows = listOf(row("a"), row("b"))
        delegate.listResult = rows
        assertSame("app: list should return the delegate's rows", rows, lazyStore.list(7))
        assertEquals("app: the first list should call the supplier exactly once", 1, supplierCalls)
        assertEquals("app: list should pass the limit on", 7, delegate.lastLimit)
    }

    @Test
    fun `the first delete calls the supplier once, passes the id and returns the delegate's answer`() {
        delegate.deleteResult = true
        assertTrue("app: delete should return the delegate's true", lazyStore.delete("gone"))
        assertEquals("app: the first delete should call the supplier exactly once", 1, supplierCalls)
        assertEquals("app: delete should pass the id on", "gone", delegate.lastDeletedId)
        delegate.deleteResult = false
        assertFalse("app: delete should return the delegate's false", lazyStore.delete("other"))
    }

    @Test
    fun `the supplier is called once however many members are used`() {
        lazyStore.save(row("a"))
        lazyStore.list(1)
        lazyStore.delete("a")
        lazyStore.save(row("b"))
        assertEquals("app: the supplier should run once for four uses", 1, supplierCalls)
        assertEquals(
            "app: every use should reach the same delegate, in order",
            listOf("save", "list", "delete", "save"),
            delegate.calls,
        )
    }

    @Test
    fun `a supplier that throws is not remembered and is called again by the next use`() {
        var attempts = 0
        val flaky = LazyHistoryStore {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("database is not ready")
            delegate
        }
        val thrown = assertThrows(IllegalStateException::class.java) { flaky.save(row("a")) }
        assertEquals("app: the supplier's failure should reach the caller", "database is not ready", thrown.message)
        assertTrue("app: a failed first use must not reach the delegate", delegate.calls.isEmpty())
        flaky.save(row("b"))
        assertEquals("app: the next use should call the supplier again", 2, attempts)
        assertEquals("app: the second try should reach the delegate", listOf("save"), delegate.calls)
        flaky.list(1)
        assertEquals("app: a built store must not be built again", 2, attempts)
    }
}
