package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the manifest address that the fixed rules build: null for a base the origin rule refuses,
 * and the address for a usable base.
 */
internal class HttpRulesOriginTest {
    private val refusedBases: List<String> = listOf(
        "https://[::1]:8443",
        "https://user@breaker.example.test:8443",
        "https://breaker.example.test:0",
        "https://breaker.example.test:65536",
        "https://breaker.example.test:84x3",
        "https://breaker..example.test:8443",
        "https://breaker_example.test:8443",
        "https://breaker.example.test:8443#x",
        "http://breaker.example.test:8443",
        "https://breaker example.test:8443",
        "https://breaker.example.test:8443\u0001",
    )

    private val usableBases: List<Pair<String, String>> = listOf(
        Pair("https://breaker.example.test:8443", "https://breaker.example.test:8443/v1/updates/latest.json"),
        Pair("https://breaker.example.test", "https://breaker.example.test/v1/updates/latest.json"),
        Pair("https://breaker.example.test:443", "https://breaker.example.test:443/v1/updates/latest.json"),
        Pair("https://Breaker.Example.Test", "https://Breaker.Example.Test/v1/updates/latest.json"),
        Pair("https://breaker.example.test:65535", "https://breaker.example.test:65535/v1/updates/latest.json"),
        Pair("https://breaker.example.test/", "https://breaker.example.test/v1/updates/latest.json"),
    )

    @Test
    fun `manifestUrl is null for every refused base row and an address for every usable row`() {
        for (base in refusedBases) {
            assertEquals("no manifest address for $base", null, HttpRules.manifestUrl(base))
        }
        for ((base, address) in usableBases) {
            assertEquals("manifest address for $base", address, HttpRules.manifestUrl(base))
        }
    }
}
