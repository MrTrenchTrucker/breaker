package dev.breaker.shared.api.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncSourceTest {

    @Test
    fun `maps the two wire words to their enum values`() {
        assertEquals("local", SyncSource.LOCAL, SyncSource.fromWire("local"))
        assertEquals("server", SyncSource.SERVER, SyncSource.fromWire("server"))
    }

    @Test
    fun `returns null for a word that is not local or server`() {
        assertNull("remote", SyncSource.fromWire("remote"))
    }

    @Test
    fun `refuses the capitalised word Local`() {
        assertNull("Local", SyncSource.fromWire("Local"))
    }
}
