package dev.breaker.server.whisper.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class DownstreamConfigToStringTest {

    private val sampleValue = "sampleValue-downstream-abc"

    @Test
    fun toStringHidesApiKey() {
        val config = DownstreamConfig(baseUrl = "http://127.0.0.1:1", apiKey = sampleValue)
        assertFalse("toString must not reveal the api key", config.toString().contains(sampleValue))
    }

    @Test
    fun toStringNamesClassAndRedacts() {
        val config = DownstreamConfig(baseUrl = "http://127.0.0.1:1", apiKey = sampleValue)
        val text = config.toString()
        assertTrue("toString must name the class", text.contains("DownstreamConfig"))
        assertTrue("toString must redact the api key", text.contains("redacted"))
    }

    @Test
    fun toStringShowsBaseUrlAndModel() {
        val config = DownstreamConfig(baseUrl = "http://127.0.0.1:1", apiKey = sampleValue)
        assertTrue("toString must show the baseUrl", config.toString().contains("http://127.0.0.1:1"))
        assertTrue("toString must show the model", config.toString().contains("whisper-1"))
    }

    @Test
    fun toStringShowsNullWhenApiKeyUnset() {
        val config = DownstreamConfig(baseUrl = "http://127.0.0.1:1", apiKey = null)
        assertTrue("toString must show apiKey=null", config.toString().contains("apiKey=null"))
        assertFalse("toString must not contain redacted when unset", config.toString().contains("redacted"))
    }

    @Test
    fun equalConfigsAreEqual() {
        val a = DownstreamConfig(baseUrl = "http://127.0.0.1:1", apiKey = sampleValue)
        val b = DownstreamConfig(baseUrl = "http://127.0.0.1:1", apiKey = sampleValue)
        assertTrue("equal configs must be equal", a == b)
    }
}
