package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** The one call that returns the address read now and the stored session together, or null. */
class ServerAddressedSessionTest {
    private val ada = AuthSession("ada", "ada", "tok-out", UserRole.USER, setOf(TokenScope.TRANSCRIBE))

    private fun rigOf(record: String?, address: String = BASE_URL): Rig {
        val rig = Rig()
        rig.vault.stored = record
        rig.base = address
        return rig
    }

    @Test
    fun `the same address returns the base and the session together`() {
        val rig = rigOf(ADA_RECORD)
        val pair = rig.service().currentAddressedSession()
        assertNotNull(pair)
        assertEquals(BASE_URL, pair!!.base)
        assertEquals(ada, pair.session)
    }

    @Test
    fun `the returned base is the address read now and not the stored text`() {
        val now = "HTTPS://Box.Example/"
        val rig = rigOf(ADA_RECORD, now)
        val pair = rig.service().currentAddressedSession()
        assertNotNull(pair)
        assertEquals(now, pair!!.base)
        assertEquals(ada, pair.session)
    }

    @Test
    fun `every address that is another server gives null and the case is named`() {
        val cases = mapOf(
            "other host" to "https://other.example",
            "other port" to "https://box.example:8443",
            "other scheme" to "http://box.example",
            "an address that does not parse" to "box.example",
            "the empty address" to "",
        )
        for ((name, address) in cases) {
            val rig = rigOf(ADA_RECORD, address)
            assertNull("case " + name, rig.service().currentAddressedSession())
        }
    }

    @Test
    fun `no record gives null and a corrupt record gives null`() {
        assertNull("no record", rigOf(null).service().currentAddressedSession())
        for (text in listOf("", "{}", "not a record")) {
            val rig = rigOf(text)
            assertNull("record " + text, rig.service().currentAddressedSession())
        }
    }

    @Test
    fun `an unreadable vault gives null`() {
        val rig = rigOf(ADA_RECORD)
        val vault = FailingVault(rig.vault)
        vault.failLoad = IllegalStateException("fake read refused")
        assertNull(rig.service(store = vault).currentAddressedSession())
    }

    @Test
    fun `the address provider is read exactly once when a record exists and never otherwise`() {
        val withRecord = mapOf(
            "same address" to rigOf(ADA_RECORD),
            "other host" to rigOf(ADA_RECORD, "https://other.example"),
            "an address that does not parse" to rigOf(ADA_RECORD, "box.example"),
            "the empty address" to rigOf(ADA_RECORD, ""),
        )
        for ((name, rig) in withRecord) {
            rig.service().currentAddressedSession()
            assertEquals("case " + name, 1, rig.baseReads)
        }
        val withoutRecord = mapOf(
            "no record" to rigOf(null),
            "corrupt record" to rigOf("not a record"),
        )
        for ((name, rig) in withoutRecord) {
            assertNull("case " + name, rig.service().currentAddressedSession())
            assertEquals("case " + name, 0, rig.baseReads)
        }
        val unreadable = rigOf(ADA_RECORD)
        val vault = FailingVault(unreadable.vault)
        vault.failLoad = IllegalStateException("fake read refused")
        assertNull(unreadable.service(store = vault).currentAddressedSession())
        assertEquals("case unreadable vault", 0, unreadable.baseReads)
        val twice = rigOf(ADA_RECORD)
        val service = twice.service()
        service.currentAddressedSession()
        service.currentAddressedSession()
        assertEquals("two calls", 2, twice.baseReads)
    }

    @Test
    fun `the call writes nothing to the vault and sends no request`() {
        for (address in listOf(BASE_URL, "https://other.example")) {
            val rig = rigOf(ADA_RECORD, address)
            rig.service().currentAddressedSession()
            assertFalse("address " + address, rig.vault.calls.contains("save"))
            assertFalse("address " + address, rig.vault.calls.contains("clear"))
            assertEquals("address " + address, ADA_RECORD, rig.vault.stored)
            assertEquals("address " + address, 0, rig.http.requests.size)
        }
    }

    @Test
    fun `the current session is the session of the pair`() {
        val rig = rigOf(ADA_RECORD)
        val service = rig.service()
        assertEquals(service.currentAddressedSession()!!.session, service.currentSession())
        val other = rigOf(ADA_RECORD, "https://other.example").service()
        assertNull(other.currentAddressedSession())
        assertNull(other.currentSession())
    }

    @Test
    fun `the text of the pair is fixed`() {
        val pair = rigOf(ADA_RECORD).service().currentAddressedSession()
        assertNotNull(pair)
        assertEquals("AddressedSession", pair.toString())
    }

    @Test
    fun `a throw from the address provider propagates unchanged and the vault is untouched`() {
        val rig = rigOf(ADA_RECORD)
        val refusal = IllegalStateException("fake address refused")
        val service = ServerAuthService(
            baseUrl = { throw refusal },
            http = rig.http,
            keyDerivation = rig.keys,
            vault = rig.vault,
        )
        val thrown = assertThrows(IllegalStateException::class.java) { service.currentAddressedSession() }
        assertSame(refusal, thrown)
        assertFalse(rig.vault.calls.contains("save"))
        assertFalse(rig.vault.calls.contains("clear"))
        assertEquals(ADA_RECORD, rig.vault.stored)
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `a throwing address provider is not called when there is no record`() {
        val refusal = IllegalStateException("fake address refused")
        for (record in listOf<String?>(null, "not a record")) {
            val rig = rigOf(record)
            val service = ServerAuthService(
                baseUrl = { throw refusal },
                http = rig.http,
                keyDerivation = rig.keys,
                vault = rig.vault,
            )
            assertNull("record " + record, service.currentAddressedSession())
        }
    }
}
