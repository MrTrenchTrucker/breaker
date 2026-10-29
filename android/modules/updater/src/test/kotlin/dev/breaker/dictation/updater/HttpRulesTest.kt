package dev.breaker.dictation.updater

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the fixed HTTP rules: the manifest address, the success statuses, the empty header list and the timeouts. */
internal class HttpRulesTest {
    private val mainRoot = File("src/main/kotlin")

    /** The text of the one main source named [name]; fails when the main folder does not hold exactly one such file. */
    private fun mainSourceNamed(name: String): String {
        val found = mainRoot.walkTopDown().filter { it.isFile && it.name == name }.toList()
        assertTrue("the main source $name was not found under the main folder", found.size == 1)
        return found.single().readText()
    }

    @Test
    fun `manifest address is built from an https base with the path added`() {
        assertEquals(
            "https://breaker.example.test:8443/v1/updates/latest.json",
            HttpRules.manifestUrl("https://breaker.example.test:8443"),
        )
        assertEquals(
            "HTTPS://breaker.example.test/v1/updates/latest.json",
            HttpRules.manifestUrl("HTTPS://breaker.example.test"),
        )
    }

    @Test
    fun `an http base gives no manifest address`() {
        assertNull(HttpRules.manifestUrl("http://breaker.example.test"))
    }

    @Test
    fun `a trailing slash on the base is removed once before the path`() {
        assertEquals(
            "https://breaker.example.test/v1/updates/latest.json",
            HttpRules.manifestUrl("https://breaker.example.test/"),
        )
        assertEquals(
            "https://breaker.example.test//v1/updates/latest.json",
            HttpRules.manifestUrl("https://breaker.example.test//"),
        )
    }

    @Test
    fun `only 200 to 299 are success statuses`() {
        for (status in listOf(200, 299)) {
            assertTrue("$status is a success", HttpRules.isSuccessStatus(status))
        }
        for (status in listOf(301, 302, 304, 404, 500)) {
            assertFalse("$status is not a success", HttpRules.isSuccessStatus(status))
        }
    }

    @Test
    fun `the request carries no headers`() {
        assertTrue(HttpRules.requestHeaders().isEmpty())
    }

    @Test
    fun `the connect and read timeouts are the named values in milliseconds`() {
        assertEquals(10_000, HttpRules.CONNECT_TIMEOUT_MS)
        assertEquals(15_000, HttpRules.READ_TIMEOUT_MS)
        assertEquals(30_000, HttpRules.APK_READ_TIMEOUT_MS)
    }

    @Test
    fun `the platform opener applies only the request header list`() {
        val text = mainSourceNamed("UpdateHttpOpener.kt")
        assertTrue("the opener must apply the request header list", text.contains("HttpRules.requestHeaders()"))
        assertEquals("the opener sets request headers in one place only", 1, text.split("setRequestProperty(").size - 1)
        assertFalse("the opener has no headers parameter", text.contains("headers:"))
    }

    @Test
    fun `the platform opener follows no redirect and sends only GET`() {
        val text = mainSourceNamed("UpdateHttpOpener.kt")
        assertTrue("redirects must not be followed", text.contains("instanceFollowRedirects = false"))
        assertTrue("the method must be GET", text.contains("requestMethod = \"GET\""))
    }
}
