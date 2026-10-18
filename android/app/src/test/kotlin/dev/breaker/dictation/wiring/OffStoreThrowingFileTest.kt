package dev.breaker.dictation.wiring

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Protects the promise that an unreadable store answers not off. */
internal class OffStoreThrowingFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class DeniedFile(parent: File, name: String) : File(parent, name) {
        override fun isFile(): Boolean = throw SecurityException("access denied")
    }

    @Test
    fun `a file that cannot be inspected makes the store answer not off`() {
        val store = FileOffStore(DeniedFile(tmp.root, "dictation-off"))
        assertFalse("app: an unreadable store must answer not off, not off-by-error", store.isOff())
    }
}
