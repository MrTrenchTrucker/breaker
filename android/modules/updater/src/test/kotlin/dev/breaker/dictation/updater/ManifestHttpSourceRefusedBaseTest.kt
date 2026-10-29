package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the manifest source for server bases: a base that the origin rule refuses is never requested,
 * and a usable base is requested exactly once, at the manifest address.
 */
internal class ManifestHttpSourceRefusedBaseTest {
    private val validText = "{\"version\":\"1.2.0\"}"
    private val usableBase = "https://breaker.example.test:8443"
    private val manifestAddress = "https://breaker.example.test:8443/v1/updates/latest.json"

    private fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** Loads the manifest under [serverBase] through a scripted opener, and returns the result with that opener. */
    private fun loadFrom(serverBase: String): Pair<ManifestLoad, ScriptedOpener> {
        val opener = ScriptedOpener(status = 200, body = TrackedStream(bytesOf(validText)))
        val result = ManifestHttpSource(serverBase, opener).load()
        return Pair(result, opener)
    }

    /** Asserts that each base gives an unavailable manifest, and that the opener received no call for it. */
    private fun assertRefusedWithoutRequest(bases: List<String>) {
        for (base in bases) {
            val (result, opener) = loadFrom(base)
            assertEquals("a refused base must give no manifest: $base", ManifestLoad.Unavailable, result)
            assertTrue("a refused base must not reach the opener: $base", opener.calls.isEmpty())
        }
    }

    @Test
    fun `an IPv6 literal base is refused before any request`() {
        assertRefusedWithoutRequest(listOf("https://[::1]:8443"))
    }

    @Test
    fun `a base with user information is refused before any request`() {
        assertRefusedWithoutRequest(
            listOf("https://user@breaker.example.test:8443", "https://user:secret@breaker.example.test:8443"),
        )
    }

    @Test
    fun `a base with an empty label or an illegal host character is refused before any request`() {
        assertRefusedWithoutRequest(
            listOf("https://breaker..example.test:8443", "https://breaker_example.test:8443"),
        )
    }

    @Test
    fun `a base with port zero is refused before any request`() {
        assertRefusedWithoutRequest(listOf("https://breaker.example.test:0"))
    }

    @Test
    fun `a base with a port out of range or a non-digit port is refused before any request`() {
        assertRefusedWithoutRequest(
            listOf("https://breaker.example.test:65536", "https://breaker.example.test:84x3"),
        )
    }

    @Test
    fun `a base with a fragment is refused before any request`() {
        assertRefusedWithoutRequest(listOf("https://breaker.example.test:8443#x"))
    }

    @Test
    fun `an http base is refused before any request`() {
        assertRefusedWithoutRequest(listOf("http://breaker.example.test:8443"))
    }

    @Test
    fun `a base with a space or a control character is refused before any request`() {
        assertRefusedWithoutRequest(
            listOf("https://breaker example.test:8443", "https://breaker.example.test:8443\u0001"),
        )
    }

    @Test
    fun `a usable base makes exactly one request to the manifest address`() {
        val (result, opener) = loadFrom(usableBase)
        assertEquals(ManifestLoad.Loaded(validText, usableBase), result)
        assertEquals(
            listOf(OpenCall(manifestAddress, HttpRules.CONNECT_TIMEOUT_MS, HttpRules.READ_TIMEOUT_MS)),
            opener.calls,
        )
    }
}
