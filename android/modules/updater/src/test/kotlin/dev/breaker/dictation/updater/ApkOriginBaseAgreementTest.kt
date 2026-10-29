package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins that the base judge and the origin check give the same answer for a base. The table is a list of
 * literal rows: the base, an apk address, the expected verdict of the base alone, and the expected verdict
 * of the base with the apk address.
 */
internal class ApkOriginBaseAgreementTest {

    private data class Row(
        val description: String,
        val base: String,
        val apk: String,
        val baseVerdict: OriginRefusal?,
        val pairVerdict: OriginRefusal?,
    )

    private val rows: List<Row> = listOf(
        Row("usable base with an explicit port", "https://breaker.example.test:8443", "https://breaker.example.test:8443/v1/apk/breaker.apk", null, null),
        Row("usable base without a port", "https://breaker.example.test", "https://breaker.example.test/a.apk", null, null),
        Row("usable base with the default port written out", "https://breaker.example.test:443", "https://breaker.example.test/a.apk", null, null),
        Row("usable base with upper case letters", "https://Breaker.Example.Test", "https://breaker.example.test/a.apk", null, null),
        Row("usable base with the highest port", "https://breaker.example.test:65535", "https://breaker.example.test:65535/a.apk", null, null),
        Row("usable base with a trailing slash", "https://breaker.example.test/", "https://breaker.example.test/a.apk", null, null),
        Row("IPv6 literal base", "https://[::1]:8443", "https://breaker.example.test:8443/a.apk", OriginRefusal.NOT_USABLE, OriginRefusal.NOT_USABLE),
        Row("base with user information", "https://user@breaker.example.test:8443", "https://breaker.example.test:8443/a.apk", OriginRefusal.USER_INFO, OriginRefusal.USER_INFO),
        Row("base with port zero", "https://breaker.example.test:0", "https://breaker.example.test:8443/a.apk", OriginRefusal.NOT_USABLE, OriginRefusal.NOT_USABLE),
        Row("base with a port above the range", "https://breaker.example.test:65536", "https://breaker.example.test:8443/a.apk", OriginRefusal.NOT_USABLE, OriginRefusal.NOT_USABLE),
        Row("base with a non-digit port", "https://breaker.example.test:84x3", "https://breaker.example.test:8443/a.apk", OriginRefusal.NOT_USABLE, OriginRefusal.NOT_USABLE),
        Row("base with an empty label", "https://breaker..example.test:8443", "https://breaker.example.test:8443/a.apk", OriginRefusal.NOT_USABLE, OriginRefusal.NOT_USABLE),
        Row("base with an illegal host character", "https://breaker_example.test:8443", "https://breaker.example.test:8443/a.apk", OriginRefusal.NOT_USABLE, OriginRefusal.NOT_USABLE),
        Row("base with a fragment", "https://breaker.example.test:8443#x", "https://breaker.example.test:8443/a.apk", OriginRefusal.FRAGMENT, OriginRefusal.FRAGMENT),
        Row("plain http base", "http://breaker.example.test:8443", "https://breaker.example.test:8443/a.apk", OriginRefusal.NOT_HTTPS, OriginRefusal.NOT_HTTPS),
        Row("base with a space", "https://breaker example.test:8443", "https://breaker.example.test:8443/a.apk", OriginRefusal.NOT_USABLE, OriginRefusal.NOT_USABLE),
        Row("base with a control character", "https://breaker.example.test:8443\u0001", "https://breaker.example.test:8443/a.apk", OriginRefusal.NOT_USABLE, OriginRefusal.NOT_USABLE),
        Row("usable base with a different apk host", "https://breaker.example.test:8443", "https://evil.example.test:8443/a.apk", null, OriginRefusal.HOST_MISMATCH),
        Row("usable base with a different apk port", "https://breaker.example.test:8443", "https://breaker.example.test/a.apk", null, OriginRefusal.PORT_MISMATCH),
    )

    @Test
    fun `the base judge agrees with refusalFor on every row of the table`() {
        for (row in rows) {
            assertEquals("base verdict for ${row.description}", row.baseVerdict, ApkOrigin.refusalForBase(row.base))
            assertEquals("pair verdict for ${row.description}", row.pairVerdict, ApkOrigin.refusalFor(row.base, row.apk))
        }
    }

    @Test
    fun `refusalFor still reports the apk host and port mismatch for a usable base`() {
        val base = "https://breaker.example.test:8443"
        assertEquals(OriginRefusal.HOST_MISMATCH, ApkOrigin.refusalFor(base, "https://evil.example.test:8443/a.apk"))
        assertEquals(OriginRefusal.PORT_MISMATCH, ApkOrigin.refusalFor(base, "https://breaker.example.test/a.apk"))
        assertNull(ApkOrigin.refusalFor(base, "https://breaker.example.test:8443/a.apk"))
    }
}
