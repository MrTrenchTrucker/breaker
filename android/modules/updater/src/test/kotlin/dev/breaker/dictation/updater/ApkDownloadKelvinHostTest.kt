package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Origin check: a host spelled with a letter that is not ASCII is refused as written.
 *
 * The Kelvin sign (U+212A) is not an ASCII letter, so the host rule refuses it, but lower-casing
 * turns it into the ASCII letter k. If the host were lower-cased before it is judged, the APK
 * address "https://<Kelvin sign>breaker.test" would be read as "kbreaker.test" and pass the
 * same-origin comparison against a server at "https://kbreaker.test". Only the host's own text
 * may be judged, so this address must be refused before any connection.
 */
class ApkDownloadKelvinHostTest {

    private val tmpDir: File = Files.createTempDirectory("apkkelvin").toFile()
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `an APK host spelled with the Kelvin sign is refused as written, not read as ascii k (origin check)`() {
        val transport = FakeOpenTransport { _ -> streamOf("abc".toByteArray()) }
        val dest = File(tmpDir, "app.apk")
        val result = ApkDownload(transport, { "https://kbreaker.test" }) { dest }.download("https://Kbreaker.test/app.apk", abcSha256)
        assertTrue("android_updater: refused, got $result", result is ApkDownloadResult.Refused)
        assertEquals("android_updater: fixed reason", ApkDownloadReasons.NOT_SAME_ORIGIN, (result as ApkDownloadResult.Refused).reason)
        assertEquals("android_updater: no open for a host that is not the server's", 0, transport.opened.size)
        assertFalse("android_updater: no destination on refusal", dest.exists())
    }
}
