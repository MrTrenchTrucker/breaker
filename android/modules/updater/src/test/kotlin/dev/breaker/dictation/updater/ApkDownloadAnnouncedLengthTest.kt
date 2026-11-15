package dev.breaker.dictation.updater

import dev.breaker.dictation.http.StreamReply
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A complete body whose announced length equals the bytes served is a finished download.
 *
 * The short-read check refuses a body that ended before its announced length. The boundary is
 * "served is less than announced": a body that served exactly what it announced is not short.
 * Every other test announces nothing (length unknown), a longer length than served, or a length
 * over the cap, so a check that also refused the equal case broke none of them.
 */
class ApkDownloadAnnouncedLengthTest {

    private val tmpDir: File = Files.createTempDirectory("apkannounced").toFile()
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `a complete body whose announced length equals its bytes is accepted, not read as a short read`() {
        val bytes = "abc".toByteArray()
        val transport = FakeOpenTransport { _ -> StreamReply(200, null, null, bytes.size.toLong(), CountingStream(bytes)) }
        val dest = File(tmpDir, "app.apk")
        val result = ApkDownload(transport, { "https://breaker.test" }) { dest }.download("https://breaker.test/app.apk", abcSha256)
        assertTrue("android_updater: an announced length equal to the bytes served is downloaded, got $result", result is ApkDownloadResult.Downloaded)
        assertEquals("android_updater: destination is the file", dest, (result as ApkDownloadResult.Downloaded).file)
        assertArrayEquals("android_updater: bytes arrived", bytes, Files.readAllBytes(dest.toPath()))
    }
}
