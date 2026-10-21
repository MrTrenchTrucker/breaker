package dev.breaker.shared.api.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncBodyClientIdTest {

    private val validId = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"

    private fun body(): SyncBody = SyncBody("hi", SyncSource.LOCAL, "m", 1L, 1792584000000L)

    private fun assertRefused(name: String, family: String, block: () -> Unit) {
        try {
            block()
            fail("no refusal of " + name)
        } catch (e: SyncBodyException) {
            assertEquals("vector " + name, "sync body refused: " + family, e.message)
        }
    }

    @Test
    fun `accepts a lowercase canonical UUID`() {
        for (v in SyncBodyVectorData.okClientId) {
            assertTrue("client id " + v.name, SyncBodyCodec.isClientId(v.input))
        }
    }

    @Test
    fun `refuses every client id vector with the family client_id`() {
        for (v in SyncBodyVectorData.refuseClient) {
            assertFalse("isClientId of " + v.name, SyncBodyCodec.isClientId(v.input))
            assertRefused(v.name, "client_id") { SyncBodyCodec.plainPush(v.input, body()) }
        }
    }

    @Test
    fun `refuses a client id with a trailing newline`() {
        assertFalse("isClientId with a line feed", SyncBodyCodec.isClientId(validId + "\n"))
        assertRefused("trailing line feed", "client_id") { SyncBodyCodec.plainPush(validId + "\n", body()) }
    }

    @Test
    fun `refuses an upper case client id`() {
        val upper = "0F9C4D1E-6A2B-4C3D-9E8F-1A2B3C4D5E6F"
        assertFalse("isClientId of upper case", SyncBodyCodec.isClientId(upper))
        assertRefused("upper case", "client_id") { SyncBodyCodec.plainPush(upper, body()) }
    }

    @Test
    fun `builds a plain push with key version zero`() {
        val push = SyncBodyCodec.plainPush(validId, body())
        assertEquals("key version zero", 0, push.keyVersion)
        assertEquals("client id as given", validId, push.clientId)
        assertEquals("encrypted is the plain string", SyncBodyCodec.encodePlain(body()), push.encrypted)
    }
}
