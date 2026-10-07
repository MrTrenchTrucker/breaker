package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry
import java.net.URI
import java.net.URISyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download policy against the model registry as it stands.
 *
 * The fetcher tests use made-up entries, so they cannot notice that a real
 * registry address or the default limits drifted apart: a new entry on a host
 * the policy refuses, or a size the default cap would cut off. These tests read
 * the real registry and the default limits, so that drift fails here by name
 * (with the entry named) and not on a user's phone halfway through a download.
 */
class DownloadPolicyContractTest {

    private val limits = DownloadLimits()

    private fun parse(entry: ModelEntry): URI = parse(entry.url, "registry entry '${entry.id}'")

    private fun parse(text: String, what: String): URI = try {
        URI(text)
    } catch (e: URISyntaxException) {
        throw AssertionError("$what: the address does not parse as a URI: ${e.reason}", e)
    }

    @Test
    fun `every registry address is https on a first-hop allowed host`() {
        assertTrue("the registry lists no models, so this check would prove nothing", ModelRegistry.ALL.isNotEmpty())
        for (entry in ModelRegistry.ALL) {
            val uri = parse(entry)
            assertEquals("registry entry '${entry.id}': scheme", "https", uri.scheme?.lowercase())
            assertTrue(
                "registry entry '${entry.id}': host '${uri.host}' is not in the first-hop hosts ${limits.firstHopHosts}",
                (uri.host ?: "").lowercase() in limits.firstHopHosts,
            )
        }
    }

    @Test
    fun `the default cap covers every registry entry`() {
        for (entry in ModelRegistry.ALL) {
            val cap = limits.modelCapBytes(entry)
            // sizeMb is the size to the nearest MiB, so the real size is below
            // sizeMb plus half a MiB; the cap must be at least that.
            val mostItCanBe = entry.sizeMb.toLong() * 1_048_576L + 524_288L
            assertTrue(
                "registry entry '${entry.id}': cap $cap bytes is below the largest size its sizeMb ${entry.sizeMb} allows ($mostItCanBe)",
                cap >= mostItCanBe,
            )
        }
        // The small entry's real archive size was measured on the upstream
        // release; it is written here as a plain number on purpose, so this does
        // not depend on the same formula the cap uses.
        val smallBytes = 365_748_162L
        val smallCap = limits.modelCapBytes(ModelRegistry.SMALL)
        assertTrue(
            "registry entry '${ModelRegistry.SMALL.id}': its real size $smallBytes bytes is above its cap $smallCap bytes",
            smallBytes <= smallCap,
        )
    }

    @Test
    fun `the checksum list address is https on a first-hop allowed host`() {
        val uri = parse(limits.checksumsUrl, "checksum list address")
        assertEquals("checksum list address: scheme", "https", uri.scheme?.lowercase())
        assertTrue(
            "checksum list address: host '${uri.host}' is not in the first-hop hosts ${limits.firstHopHosts}",
            (uri.host ?: "").lowercase() in limits.firstHopHosts,
        )
        assertTrue("checksum list address: the policy refuses it as a first request", limits.isAllowed(uri, 0))
    }

    @Test
    fun `a model address is accepted as the first request and refused as a redirect target`() {
        for (entry in ModelRegistry.ALL) {
            val uri = parse(entry)
            assertTrue("registry entry '${entry.id}': refused as the first request", limits.isAllowed(uri, 0))
            // A first-hop host must not also be a redirect host: a redirect back
            // to it would be accepted as a later hop.
            assertFalse("registry entry '${entry.id}': accepted at hop 1, but the first-hop host is not a redirect host", limits.isAllowed(uri, 1))
        }
    }

    @Test
    fun `a redirect host is accepted after the first request and refused as the first one`() {
        assertTrue("the default limits list no redirect host, so redirects could never be followed", limits.redirectHosts.isNotEmpty())
        for (host in limits.redirectHosts) {
            val uri = parse("https://$host/some/path?token=abc", "redirect host '$host'")
            assertTrue("redirect host '$host': refused at hop 1", limits.isAllowed(uri, 1))
            assertFalse("redirect host '$host': accepted as the first request", limits.isAllowed(uri, 0))
        }
    }
}
