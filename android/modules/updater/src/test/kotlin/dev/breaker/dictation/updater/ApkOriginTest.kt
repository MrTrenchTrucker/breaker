package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the origin rules that an apk address must meet under the configured server address. */
internal class ApkOriginTest {
    private val base = "https://breaker.example.test:8443"

    @Test
    fun `the same origin with the same explicit port is accepted`() {
        assertNull(ApkOrigin.refusalFor(base, "https://breaker.example.test:8443/v1/apk/breaker.apk"))
    }

    @Test
    fun `the default port 443 is the same as an explicit 443`() {
        assertNull(ApkOrigin.refusalFor("https://breaker.example.test", "https://breaker.example.test:443/a.apk"))
        assertNull(ApkOrigin.refusalFor("https://breaker.example.test:443", "https://breaker.example.test/a.apk"))
    }

    @Test
    fun `the host is compared without regard to case`() {
        assertNull(ApkOrigin.refusalFor("https://Breaker.Example.Test", "https://breaker.EXAMPLE.test/a.apk"))
    }

    @Test
    fun `the scheme is read without regard to case`() {
        assertNull(ApkOrigin.refusalFor("https://breaker.example.test", "HTTPS://breaker.example.test/a.apk"))
    }

    @Test
    fun `a path and a query on the apk are allowed`() {
        assertNull(ApkOrigin.refusalFor(base, "https://breaker.example.test:8443/a.apk?v=1"))
        assertNull(ApkOrigin.refusalFor(base, "https://breaker.example.test:8443?v=1"))
    }

    @Test
    fun `a plain http address is refused`() {
        assertEquals(OriginRefusal.NOT_HTTPS, ApkOrigin.refusalFor(base, "http://breaker.example.test:8443/a.apk"))
        assertEquals(
            OriginRefusal.NOT_HTTPS,
            ApkOrigin.refusalFor("http://breaker.example.test:8443", "https://breaker.example.test:8443/a.apk"),
        )
    }

    @Test
    fun `a different host is refused including a longer name that starts with the base host`() {
        assertEquals(OriginRefusal.HOST_MISMATCH, ApkOrigin.refusalFor(base, "https://evil.example.test:8443/a.apk"))
        assertEquals(
            OriginRefusal.HOST_MISMATCH,
            ApkOrigin.refusalFor(base, "https://breaker.example.test.evil.test:8443/a.apk"),
        )
        assertEquals(
            OriginRefusal.HOST_MISMATCH,
            ApkOrigin.refusalFor(base, "https://breaker.example.test-evil.test:8443/a.apk"),
        )
    }

    @Test
    fun `a different port is refused`() {
        assertEquals(OriginRefusal.PORT_MISMATCH, ApkOrigin.refusalFor(base, "https://breaker.example.test/a.apk"))
        assertEquals(OriginRefusal.PORT_MISMATCH, ApkOrigin.refusalFor(base, "https://breaker.example.test:8444/a.apk"))
    }

    @Test
    fun `user info is refused`() {
        assertEquals(OriginRefusal.USER_INFO, ApkOrigin.refusalFor(base, "https://breaker.example.test@evil.test/a.apk"))
        assertEquals(
            OriginRefusal.USER_INFO,
            ApkOrigin.refusalFor(base, "https://user:secret@breaker.example.test:8443/a.apk"),
        )
    }

    @Test
    fun `a fragment is refused`() {
        assertEquals(OriginRefusal.FRAGMENT, ApkOrigin.refusalFor(base, "https://breaker.example.test:8443/a.apk#frag"))
        assertEquals(
            OriginRefusal.FRAGMENT,
            ApkOrigin.refusalFor("https://breaker.example.test:8443#frag", "https://breaker.example.test:8443/a.apk"),
        )
    }

    @Test
    fun `a backslash a space or a control character is refused as not usable`() {
        assertEquals(
            OriginRefusal.NOT_USABLE,
            ApkOrigin.refusalFor(base, "https://breaker.example.test:8443\\@evil.test/a.apk"),
        )
        assertEquals(OriginRefusal.NOT_USABLE, ApkOrigin.refusalFor(base, "https://breaker.example.test:8443/a b.apk"))
        assertEquals(OriginRefusal.NOT_USABLE, ApkOrigin.refusalFor(base, "https://breaker.example.test:8443/a\u0001.apk"))
    }

    @Test
    fun `a percent-encoded or non-ASCII host is refused`() {
        assertEquals(OriginRefusal.NOT_USABLE, ApkOrigin.refusalFor(base, "https://breaker%2Eexample.test:8443/a.apk"))
        assertEquals(OriginRefusal.NOT_USABLE, ApkOrigin.refusalFor(base, "https://breaker.ex\u0430mple.test:8443/a.apk"))
    }

    @Test
    fun `an empty label a trailing dot or a bracketed address is refused`() {
        assertEquals(OriginRefusal.NOT_USABLE, ApkOrigin.refusalFor(base, "https://breaker..example.test:8443/a.apk"))
        assertEquals(OriginRefusal.NOT_USABLE, ApkOrigin.refusalFor(base, "https://breaker.example.test.:8443/a.apk"))
        assertEquals(OriginRefusal.NOT_USABLE, ApkOrigin.refusalFor(base, "https://[::1]:8443/a.apk"))
    }

    @Test
    fun `a malformed port is refused and 65535 is accepted`() {
        for (bad in listOf(":", ":0443", ":0", ":65536", ":44x")) {
            assertEquals("port $bad", OriginRefusal.NOT_USABLE, ApkOrigin.refusalFor(base, "https://breaker.example.test$bad/a.apk"))
        }
        assertNull(ApkOrigin.refusalFor("https://breaker.example.test:65535", "https://breaker.example.test:65535/a.apk"))
    }

    @Test
    fun `an empty host is refused`() {
        assertEquals(OriginRefusal.NOT_USABLE, ApkOrigin.refusalFor(base, "https:///a.apk"))
        assertEquals(OriginRefusal.NOT_USABLE, ApkOrigin.refusalFor(base, "https://"))
    }

    @Test
    fun `the base address is checked with the same rules`() {
        assertEquals(
            OriginRefusal.NOT_HTTPS,
            ApkOrigin.refusalFor("http://breaker.example.test", "https://breaker.example.test/a.apk"),
        )
        assertEquals(
            OriginRefusal.USER_INFO,
            ApkOrigin.refusalFor("https://user@breaker.example.test", "https://breaker.example.test/a.apk"),
        )
        assertEquals(
            OriginRefusal.FRAGMENT,
            ApkOrigin.refusalFor("https://breaker.example.test#x", "https://breaker.example.test/a.apk"),
        )
    }
}
