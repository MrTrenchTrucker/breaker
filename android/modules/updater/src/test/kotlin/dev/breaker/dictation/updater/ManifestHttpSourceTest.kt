package dev.breaker.dictation.updater

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the manifest read over https: the status, the cap, the declared length, the text, the base, the arguments and the close. */
internal class ManifestHttpSourceTest {
    private val base = "https://breaker.example.test:8443"
    private val manifestAddress = "https://breaker.example.test:8443/v1/updates/latest.json"
    private val validText = "{\"version\":\"1.2.0\"}"

    private fun sourceFor(opener: UpdateHttpOpener, serverBase: String = base): ManifestSource =
        ManifestHttpSource(serverBase, opener)

    private fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `a 200 with a valid body gives the text and the base unchanged`() {
        val opener = ScriptedOpener(status = 200, body = TrackedStream(bytesOf(validText)))
        assertEquals(ManifestLoad.Loaded(validText, base), sourceFor(opener).load())
    }

    @Test
    fun `a redirect is unavailable and its body is never read`() {
        val body = TrackedStream(bytesOf(validText))
        val opener = ScriptedOpener(status = 302, body = body)
        assertEquals(ManifestLoad.Unavailable, sourceFor(opener).load())
        assertEquals(0, body.readCalls)
    }

    @Test
    fun `a 500 is unavailable`() {
        val opener = ScriptedOpener(status = 500, body = TrackedStream(bytesOf(validText)))
        assertEquals(ManifestLoad.Unavailable, sourceFor(opener).load())
    }

    @Test
    fun `an IOException from the opener is unavailable`() {
        val opener = ScriptedOpener(failure = IOException("the test connection failed"))
        assertEquals(ManifestLoad.Unavailable, sourceFor(opener).load())
    }

    @Test
    fun `a body of exactly 65536 bytes is read`() {
        val body = ByteArray(ManifestReader.MAX_BYTES) { 0x61.toByte() }
        val opener = ScriptedOpener(status = 200, contentLength = ManifestReader.MAX_BYTES.toLong(), body = ByteArrayInputStream(body))
        val loaded = sourceFor(opener).load() as ManifestLoad.Loaded
        assertEquals(ManifestReader.MAX_BYTES, loaded.manifestText.length)
    }

    @Test
    fun `a body of 65537 bytes is unavailable`() {
        val body = ByteArray(ManifestReader.MAX_BYTES + 1) { 0x61.toByte() }
        val opener = ScriptedOpener(status = 200, contentLength = -1L, body = ByteArrayInputStream(body))
        assertEquals(ManifestLoad.Unavailable, sourceFor(opener).load())
    }

    @Test
    fun `a malformed body is unavailable`() {
        val opener = ScriptedOpener(status = 200, body = ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0x41.toByte())))
        assertEquals(ManifestLoad.Unavailable, sourceFor(opener).load())
    }

    @Test
    fun `a declared length longer than the body is unavailable`() {
        val opener = ScriptedOpener(status = 200, contentLength = 100L, body = ByteArrayInputStream(ByteArray(50) { 0x61.toByte() }))
        assertEquals(ManifestLoad.Unavailable, sourceFor(opener).load())
    }

    @Test
    fun `a declared length over the cap is refused before any body byte is read`() {
        val body = TrackedStream(ByteArray(10) { 0x61.toByte() })
        val opener = ScriptedOpener(status = 200, contentLength = 131_072L, body = body)
        assertEquals(ManifestLoad.Unavailable, sourceFor(opener).load())
        assertEquals(0, body.readCalls)
    }

    @Test
    fun `an http base gives unavailable and never calls the opener`() {
        val opener = ScriptedOpener(status = 200, body = TrackedStream(bytesOf(validText)))
        assertEquals(ManifestLoad.Unavailable, sourceFor(opener, "http://breaker.example.test").load())
        assertEquals(0, opener.calls.size)
    }

    @Test
    fun `the opener gets the manifest address and the named timeouts`() {
        val opener = ScriptedOpener(status = 200, body = TrackedStream(bytesOf(validText)))
        sourceFor(opener).load()
        assertEquals(listOf(OpenCall(manifestAddress, HttpRules.CONNECT_TIMEOUT_MS, HttpRules.READ_TIMEOUT_MS)), opener.calls)
    }

    @Test
    fun `the reply is closed on every path`() {
        for (status in listOf(200, 302, 500)) {
            val body = TrackedStream(bytesOf(validText))
            sourceFor(ScriptedOpener(status = status, body = body)).load()
            assertTrue("the reply for status $status must be closed", body.closed)
        }
    }
}
