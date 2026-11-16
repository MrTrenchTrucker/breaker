package dev.breaker.dictation.auth.client

import dev.breaker.dictation.auth.client.DeviceListFixtures.listText
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A stored session record is valid for ANY address in the current configured list, and never for
 * one outside it. The list seam defaults to the single current address, so every other test in
 * this module keeps the old single-address behaviour; these tests pass a real list.
 *
 * Red-first, argued in NOTES_S2b.md: on the old check (`ServerAddressMatch.same(record.server,
 * base)`) `switching home to road keeps the session` fails because a record stored under home is
 * dropped the moment the effective address becomes road, and `a record under the current address
 * is refused when that address is not in the list` fails the other way because the old check
 * accepted a stored text equal to the current text even when that text was no longer configured.
 */
class ServerAddressMembershipTest {
    private val ada = AuthSession("ada", "ada", "tok-out", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
    private val home = "https://box.example"
    private val road = "https://road.example"

    /** A stored record for [server], in the shape [SessionRecord] encodes. */
    private fun record(server: String): String =
        "{\"user_id\":\"ada\",\"username\":\"ada\",\"token\":\"tok-out\",\"role\":\"user\"," +
            "\"scopes\":[\"transcribe\"],\"server\":\"" + server + "\"}"

    /** A service over a Rig's fakes, with the list seam passed explicitly. */
    private fun service(rig: Rig, base: String, entries: List<String>): ServerAuthService =
        ServerAuthService(
            baseUrl = { base },
            http = rig.http,
            keyDerivation = rig.keys,
            vault = rig.vault,
            serverAddresses = { entries },
        )

    private fun rigWith(record: String?): Rig {
        val rig = Rig()
        rig.vault.stored = record
        return rig
    }

    @Test
    fun `switching home to road keeps the session`() {
        val rig = rigWith(record(home))
        val pair = service(rig, base = road, entries = listOf(home, road)).currentAddressedSession()
        assertNotNull("a record stored under home must survive the effective address moving to road", pair)
        assertEquals("the pair carries the address read now, not the stored text", road, pair!!.base)
        assertEquals(ada, pair.session)
    }

    @Test
    fun `a record saved under a later entry is kept at that entry`() {
        val rig = rigWith(record(road))
        val pair = service(rig, base = road, entries = listOf(home, road)).currentAddressedSession()
        assertNotNull("a record stored under entry 2 is valid at entry 2", pair)
        assertEquals(ada, pair!!.session)
    }

    @Test
    fun `an address not in the list is refused`() {
        val rig = rigWith(record("https://other.example"))
        val service = service(rig, base = road, entries = listOf(home, road))
        assertNull("a record outside the list must give no session", service.currentAddressedSession())
        assertNull("devices() must send nothing for a record outside the list", service.devices())
        service.logout()
        assertEquals("no request may be sent for a record outside the list", 0, rig.http.requests.size)
        assertNull("logout still clears the vault", rig.vault.stored)
    }

    @Test
    fun `a record under the current address is refused when that address is not in the list`() {
        val rig = rigWith(record(road))
        val pair = service(rig, base = road, entries = listOf(home)).currentAddressedSession()
        assertNull("the current text is not enough; it must be one of the configured entries", pair)
    }

    @Test
    fun `a stored trailing slash still matches the entry without one`() {
        val rig = rigWith(record("$home/"))
        val pair = service(rig, base = home, entries = listOf(home)).currentAddressedSession()
        assertNotNull("one trailing slash is ignored by the same-server rule", pair)
        assertEquals(ada, pair!!.session)
    }

    @Test
    fun `membership keeps the path and slash exactness of the same-server rule`() {
        val cases = mapOf(
            "two trailing slashes against one" to (record("$home//") to "$home/"),
            "a path in another case" to (record("$home/Path") to "$home/path"),
        )
        for ((name, case) in cases) {
            val (stored, entry) = case
            val rig = rigWith(stored)
            assertNull("case " + name, service(rig, base = entry, entries = listOf(entry)).currentAddressedSession())
        }
    }

    @Test
    fun `logout at a switched address revokes at the address read now`() {
        val rig = rigWith(record(home))
        rig.http.answer(200)
        service(rig, base = road, entries = listOf(home, road)).logout()
        val request = rig.http.requests.single()
        assertEquals("POST", request.method)
        assertEquals("the logout goes to the address read now", road + "/v1/auth/logout", request.url)
        assertEquals(listOf("Authorization" to "Bearer tok-out"), request.headers)
        assertNull(rig.vault.stored)
    }

    @Test
    fun `devices at a switched address reads the device list at the address read now`() {
        val rig = rigWith(record(home))
        rig.http.answer(200, listText(emptyList()))
        val rows = service(rig, base = road, entries = listOf(home, road)).devices()
        assertEquals(emptyList<DeviceRow>(), rows)
        assertEquals(
            "the device list is read at the address read now",
            road + "/v1/auth/devices",
            rig.http.requests.single().url,
        )
    }
}
