package dev.breaker.dictation.auth.client

import dev.breaker.dictation.auth.client.DeviceListFixtures.listText
import dev.breaker.dictation.auth.client.DeviceListFixtures.quoted
import dev.breaker.dictation.auth.client.DeviceListFixtures.rowText
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * devices(): GET /v1/auth/devices with the stored bearer, the answer decoded by the device
 * list decoder. Null means nothing was sent (no readable record, an undecodable record, an
 * address that differs, no endpoint); a list, possibly empty, only when the server answered
 * 200 and the decoder accepted it. The call never clears the vault.
 */
class ServerDevicesTest {
    private val rowsBody: String = listText(
        listOf(
            rowText(id = "4", name = quoted("Pixel 8"), lastSeen = quoted("1970-01-01T00:00:01.500Z")),
            rowText(id = "9", name = quoted("Tablet"), current = "false"),
        ),
    )

    @Test
    fun `a 200 answer decodes into the rows in the order given`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(200, rowsBody)
        val rows = rig.service().devices()
        assertEquals(2, rows?.size)
        assertEquals(DeviceRow(4L, "Pixel 8", 1500L, true), rows?.get(0))
        assertEquals(DeviceRow(9L, "Tablet", 1_794_052_805_250L, false), rows?.get(1))
    }

    @Test
    fun `the request is a GET to the devices path with the bearer as its only header`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(200, rowsBody)
        rig.service().devices()
        val request = rig.http.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://box.example/v1/auth/devices", request.url)
        assertEquals(listOf("Authorization" to "Bearer tok-out"), request.headers)
        assertNull(request.body)
    }

    @Test
    fun `a 200 with zero rows gives an empty list, not null`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(200, listText(emptyList()))
        assertEquals(emptyList<DeviceRow>(), rig.service().devices())
    }

    @Test
    fun `a 401 answers bad credentials and leaves the stored session in the vault`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(401)
        val thrown = org.junit.Assert.assertThrows(AuthFailure::class.java) { rig.service().devices() }
        assertEquals(AuthFailureReason.BAD_CREDENTIALS, thrown.reason)
        assertEquals(ADA_RECORD, rig.vault.stored)
    }

    @Test
    fun `a 403 answers a server error and leaves the stored session in the vault`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(403)
        val thrown = org.junit.Assert.assertThrows(AuthFailure::class.java) { rig.service().devices() }
        assertEquals(AuthFailureReason.SERVER_ERROR, thrown.reason)
        assertEquals(ADA_RECORD, rig.vault.stored)
    }

    @Test
    fun `a 503 answers a server error`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(503)
        val thrown = org.junit.Assert.assertThrows(AuthFailure::class.java) { rig.service().devices() }
        assertEquals(AuthFailureReason.SERVER_ERROR, thrown.reason)
    }

    @Test
    fun `a 500 answers a server error`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(500)
        val thrown = org.junit.Assert.assertThrows(AuthFailure::class.java) { rig.service().devices() }
        assertEquals(AuthFailureReason.SERVER_ERROR, thrown.reason)
    }

    @Test
    fun `a 200 that does not decode answers an unreadable reply`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(200, "{not a list")
        val thrown = org.junit.Assert.assertThrows(AuthFailure::class.java) { rig.service().devices() }
        assertEquals(AuthFailureReason.UNREADABLE_REPLY, thrown.reason)
    }

    @Test
    fun `a network failure answers a server unreachable error`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.failWith(IOException("fake connection refused"))
        val thrown = org.junit.Assert.assertThrows(AuthFailure::class.java) { rig.service().devices() }
        assertEquals(AuthFailureReason.SERVER_UNREACHABLE, thrown.reason)
    }

    @Test
    fun `a port failure that is not a network failure answers a server error`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.failWith(IllegalStateException("fake port refused"))
        val thrown = org.junit.Assert.assertThrows(AuthFailure::class.java) { rig.service().devices() }
        assertEquals(AuthFailureReason.SERVER_ERROR, thrown.reason)
    }

    @Test
    fun `nothing stored answers null and the address provider is not called`() {
        val rig = Rig()
        assertNull(rig.service().devices())
        assertEquals(0, rig.http.requests.size)
        assertEquals(0, rig.baseReads)
    }

    @Test
    fun `an undecodable stored record answers null and sends no request`() {
        val rig = Rig()
        rig.vault.stored = "not a record"
        assertNull(rig.service().devices())
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `a stored address that differs from the address read now answers null and sends no request`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.base = "https://other.example"
        assertNull(rig.service().devices())
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `a stored address with a query answers null when no endpoint can be built`() {
        val query = "https://box.example/?x"
        val record = "{\"user_id\":\"ada\",\"username\":\"ada\",\"token\":\"tok-out\",\"role\":\"user\"," +
            "\"scopes\":[\"transcribe\"],\"server\":\"" + query + "\"}"
        val rig = Rig()
        rig.vault.stored = record
        rig.base = query
        assertNull(rig.service().devices())
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `the base address is read once when a request is sent`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(200, rowsBody)
        rig.service().devices()
        assertEquals(1, rig.baseReads)
    }
}
