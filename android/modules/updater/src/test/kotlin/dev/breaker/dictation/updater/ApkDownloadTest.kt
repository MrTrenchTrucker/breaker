package dev.breaker.dictation.updater

import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpRefusalReason
import dev.breaker.dictation.http.StreamReply
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class ApkDownloadTest {

    private val tmpDir: File = Files.createTempDirectory("apkdownload").toFile()

    private fun destFile(name: String = "app.apk"): File = File(tmpDir, name)

    private fun download(
        transport: FakeOpenTransport,
        apkUrl: String,
        sha256: String,
        server: String = "https://breaker.test",
        dest: File = destFile(),
    ): ApkDownloadResult =
        ApkDownload(transport, { server }) { dest }.download(apkUrl, sha256)

    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `same-origin https download succeeds and moves the temp file`() {
        val bytes = "abc".toByteArray()
        val transport = FakeOpenTransport { _ -> streamOf(bytes) }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
        assertTrue("android_updater: downloaded", result is ApkDownloadResult.Downloaded)
        assertEquals("android_updater: destination is the file", dest, (result as ApkDownloadResult.Downloaded).file)
        assertTrue("android_updater: destination exists", dest.exists())
        assertArrayEquals("android_updater: bytes arrived", bytes, Files.readAllBytes(dest.toPath()))
        assertFalse("android_updater: no .part file left", File(tmpDir, "app.apk.part").exists())
    }

    @Test
    fun `same-origin plain http on default port succeeds`() {
        val bytes = "abc".toByteArray()
        val transport = FakeOpenTransport { _ -> streamOf(bytes) }
        val dest = destFile()
        val result = download(transport, "http://breaker.test/app.apk", abcSha256, server = "http://breaker.test", dest = dest)
        assertTrue("android_updater: http default port downloaded", result is ApkDownloadResult.Downloaded)
    }

    @Test
    fun `http with explicit port 80 equals default port`() {
        val bytes = "abc".toByteArray()
        val transport = FakeOpenTransport { _ -> streamOf(bytes) }
        val dest = destFile()
        val result = download(transport, "http://breaker.test:80/app.apk", abcSha256, server = "http://breaker.test", dest = dest)
        assertTrue("android_updater: http :80 equals default port", result is ApkDownloadResult.Downloaded)
    }

    @Test
    fun `host case is ignored`() {
        val bytes = "abc".toByteArray()
        val transport = FakeOpenTransport { _ -> streamOf(bytes) }
        val dest = destFile()
        val result = download(transport, "https://BREAKER.TEST/app.apk", abcSha256, server = "https://breaker.test", dest = dest)
        assertTrue("android_updater: host case ignored", result is ApkDownloadResult.Downloaded)
    }

    @Test
    fun `digest with uppercase hex matches`() {
        val bytes = "abc".toByteArray()
        val transport = FakeOpenTransport { _ -> streamOf(bytes) }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", dest = dest)
        assertTrue("android_updater: uppercase digest accepted", result is ApkDownloadResult.Downloaded)
    }

    @Test
    fun `https on another host is refused before any connection`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val dest = destFile()
        val result = download(transport, "https://other.test/app.apk", abcSha256, server = "https://breaker.test", dest = dest)
        assertRefused(result, ApkDownloadReasons.NOT_SAME_ORIGIN)
        assertEquals("android_updater: no open for another host", 0, transport.opened.size)
        assertFalse("android_updater: no destination on refusal", dest.exists())
    }

    @Test
    fun `scheme mismatch is refused`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val dest = destFile()
        val result = download(transport, "http://breaker.test/app.apk", abcSha256, server = "https://breaker.test", dest = dest)
        assertRefused(result, ApkDownloadReasons.NOT_SAME_ORIGIN)
        assertEquals("android_updater: no open on scheme mismatch", 0, transport.opened.size)
    }

    @Test
    fun `port mismatch is refused`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val dest = destFile()
        val result = download(transport, "https://breaker.test:8443/app.apk", abcSha256, server = "https://breaker.test", dest = dest)
        assertRefused(result, ApkDownloadReasons.NOT_SAME_ORIGIN)
        assertEquals("android_updater: no open on port mismatch", 0, transport.opened.size)
    }

    @Test
    fun `userinfo in apk_url is refused`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val dest = destFile()
        val result = download(transport, "https://user:pass@breaker.test/app.apk", abcSha256, server = "https://breaker.test", dest = dest)
        assertRefused(result, ApkDownloadReasons.NOT_SAME_ORIGIN)
    }

    @Test
    fun `blank apk_url is refused`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val dest = destFile()
        val result = download(transport, "", abcSha256, server = "https://breaker.test", dest = dest)
        assertRefused(result, ApkDownloadReasons.NOT_SAME_ORIGIN)
        assertEquals("android_updater: no open for blank url", 0, transport.opened.size)
    }

    @Test
    fun `relative apk_url is refused`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val dest = destFile()
        val result = download(transport, "/app.apk", abcSha256, server = "https://breaker.test", dest = dest)
        assertRefused(result, ApkDownloadReasons.NOT_SAME_ORIGIN)
    }

    @Test
    fun `trailing dot host is refused`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val dest = destFile()
        val result = download(transport, "https://breaker.test./app.apk", abcSha256, server = "https://breaker.test", dest = dest)
        assertRefused(result, ApkDownloadReasons.NOT_SAME_ORIGIN)
    }

    @Test
    fun `bad digest is refused before downloading`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", "not-hex", dest = dest)
        assertRefused(result, ApkDownloadReasons.BAD_DIGEST)
        assertEquals("android_updater: no open for bad digest", 0, transport.opened.size)
    }

    @Test
    fun `wrong-length digest is refused`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", "abc", dest = dest)
        assertRefused(result, ApkDownloadReasons.BAD_DIGEST)
    }

    @Test
    fun `status other than 200 is refused`() {
        for (status in listOf(206, 204, 301, 302, 400, 404, 500, 503)) {
            val transport = FakeOpenTransport { _ ->
                StreamReply(status, null, null, -1L, java.io.ByteArrayInputStream(byteArrayOf()))
            }
            val dest = destFile()
            val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
            assertRefused(result, ApkDownloadReasons.BAD_STATUS, "status $status")
        }
    }

    @Test
    fun `content length above cap is refused before downloading`() {
        val transport = FakeOpenTransport { _ ->
            StreamReply(200, null, null, ApkDownloadTest.APK_CAP_BYTES + 1, java.io.ByteArrayInputStream(byteArrayOf()))
        }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
        assertRefused(result, ApkDownloadReasons.REPLY_TOO_LARGE)
    }

    @Test
    fun `digest mismatch deletes the temp file and keeps no destination`() {
        val bytes = "abc".toByteArray()
        val transport = FakeOpenTransport { _ -> streamOf(bytes) }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", "0000000000000000000000000000000000000000000000000000000000000000", dest = dest)
        assertRefused(result, ApkDownloadReasons.BAD_DIGEST)
        assertFalse("android_updater: no .part left on mismatch", File(tmpDir, "app.apk.part").exists())
        assertFalse("android_updater: no destination on mismatch", dest.exists())
    }

    @Test
    fun `short read refuses with length mismatch`() {
        // Announced 10 bytes, the stream ends after 2 -> short read -> LENGTH_MISMATCH.
        val transport = FakeOpenTransport { _ ->
            StreamReply(200, null, null, 10L, CountingStream("ab".toByteArray()))
        }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
        assertRefused(result, ApkDownloadReasons.LENGTH_MISMATCH)
        assertFalse("android_updater: no .part left on short read", File(tmpDir, "app.apk.part").exists())
        assertFalse("android_updater: no destination on short read", dest.exists())
    }

    @Test
    fun `stream read throws HttpRefusal reply too large maps to refused`() {
        // The stream refuses with REPLY_TOO_LARGE mid-read.
        val transport = FakeOpenTransport { _ ->
            StreamReply(200, null, null, -1L, RefusalStream(HttpRefusalReason.REPLY_TOO_LARGE))
        }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
        assertRefused(result, ApkDownloadReasons.REPLY_TOO_LARGE)
        assertFalse("android_updater: no .part left on refusal stream", File(tmpDir, "app.apk.part").exists())
    }

    @Test
    fun `stream read throws HttpRefusal length mismatch maps to refused`() {
        val transport = FakeOpenTransport { _ ->
            StreamReply(200, null, null, -1L, RefusalStream(HttpRefusalReason.LENGTH_MISMATCH))
        }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
        assertRefused(result, ApkDownloadReasons.LENGTH_MISMATCH)
        assertFalse("android_updater: no .part left on refusal stream 2", File(tmpDir, "app.apk.part").exists())
    }

    @Test
    fun `cap exceeded refuses with reply too large without holding the bytes`() {
        // A stream that keeps going past the cap without allocating 256 MiB in the test.
        val transport = FakeOpenTransport { _ ->
            StreamReply(200, null, null, -1L, countingStream(ApkDownloadTest.APK_CAP_BYTES + 1))
        }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
        assertRefused(result, ApkDownloadReasons.REPLY_TOO_LARGE)
        assertFalse("android_updater: no .part left on cap", File(tmpDir, "app.apk.part").exists())
    }

    @Test
    fun `a runtime exception mid-read deletes the temp file, keeps the old destination and says unfinished`() {
        val dest = destFile()
        Files.write(dest.toPath(), byteArrayOf(1, 2, 3))
        val transport = FakeOpenTransport { _ ->
            StreamReply(200, null, null, -1L, ThrowingStream("abcde".toByteArray(), IllegalStateException("LEAK-MARKER")))
        }
        val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
        assertRefused(result, ApkDownloadReasons.UNFINISHED)
        assertFalse("android_updater: reason holds no exception text", (result as ApkDownloadResult.Refused).reason.contains("LEAK-MARKER"))
        assertFalse("android_updater: no .part left on mid-read exception", File(tmpDir, "app.apk.part").exists())
        assertArrayEquals("android_updater: old destination kept", byteArrayOf(1, 2, 3), Files.readAllBytes(dest.toPath()))
    }

    @Test
    fun `a dropped connection mid-read deletes the temp file, keeps the old destination and says unreachable`() {
        val dest = destFile()
        Files.write(dest.toPath(), byteArrayOf(1, 2, 3))
        val transport = FakeOpenTransport { _ ->
            StreamReply(200, null, null, -1L, ThrowingStream("abcde".toByteArray(), IOException("LEAK-MARKER")))
        }
        val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
        assertRefused(result, ApkDownloadReasons.UNREACHABLE)
        assertFalse("android_updater: no .part left on a dropped connection", File(tmpDir, "app.apk.part").exists())
        assertArrayEquals("android_updater: old destination kept", byteArrayOf(1, 2, 3), Files.readAllBytes(dest.toPath()))
    }

    @Test
    fun `existing destination is untouched on mismatch`() {
        val dest = destFile()
        Files.write(dest.toPath(), byteArrayOf(1, 2, 3))
        val transport = FakeOpenTransport { _ -> streamOf("abc".toByteArray()) }
        val result = download(transport, "https://breaker.test/app.apk", "0000000000000000000000000000000000000000000000000000000000000000", dest = dest)
        assertRefused(result, ApkDownloadReasons.BAD_DIGEST)
        assertArrayEquals("android_updater: old destination kept", byteArrayOf(1, 2, 3), Files.readAllBytes(dest.toPath()))
    }

    @Test
    fun `transport IOException becomes unreachable`() {
        val transport = FakeOpenTransport { _ -> throw IOException("BOOM") }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
        assertRefused(result, ApkDownloadReasons.UNREACHABLE)
    }

    @Test
    fun `HttpRefusal becomes not allowed`() {
        val transport = FakeOpenTransport { _ -> throw HttpRefusal(HttpRefusalReason.NOT_HTTPS) }
        val dest = destFile()
        val result = download(transport, "https://breaker.test/app.apk", abcSha256, dest = dest)
        assertRefused(result, ApkDownloadReasons.NOT_ALLOWED)
    }

    @Test
    fun `destination provider that throws is refused`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val result = ApkDownload(transport, { "https://breaker.test" }) { throw IOException("no dest") }.download("https://breaker.test/app.apk", abcSha256)
        assertRefused(result, ApkDownloadReasons.BAD_DESTINATION)
    }

    @Test
    fun `destination directory missing is refused`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val missing = File(File(tmpDir, "does-not-exist"), "app.apk")
        val result = ApkDownload(transport, { "https://breaker.test" }) { missing }.download("https://breaker.test/app.apk", abcSha256)
        assertRefused(result, ApkDownloadReasons.BAD_DESTINATION)
    }

    @Test
    fun `no URL text ever reaches a reason`() {
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val dest = destFile()
        val result = download(transport, "https://secret-leak.test/app.apk", abcSha256, server = "https://breaker.test", dest = dest)
        val reason = (result as ApkDownloadResult.Refused).reason
        assertFalse("android_updater: reason holds no URL", reason.contains("secret-leak.test") || reason.contains("app.apk"))
    }


    private val apkUrl = "https://breaker.test/app.apk"

    private fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }

    private fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun sha256HexOfCountingStream(count: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        val source = countingStream(count)
        while (true) {
            val taken = source.read(buffer)
            if (taken < 0) break
            digest.update(buffer, 0, taken)
        }
        return hex(digest.digest())
    }

    @Test
    fun `an empty body is refused when the offered digest is not the digest of nothing`() {
        val dest = destFile()
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        assertRefused(download(transport, apkUrl, abcSha256, dest = dest), ApkDownloadReasons.BAD_DIGEST)
        assertFalse("android_updater: no destination for an empty body with the wrong digest", dest.exists())
        assertFalse("android_updater: no .part left", File(tmpDir, "app.apk.part").exists())
    }

    @Test
    fun `an empty body is accepted when the offered digest is the digest of nothing`() {
        // The rule: the digest decides. An empty file with the digest of nothing passes here; the install step refuses it later.
        val dest = destFile()
        val transport = FakeOpenTransport { _ -> streamOf(byteArrayOf()) }
        val result = download(transport, apkUrl, sha256Hex(byteArrayOf()), dest = dest)
        assertTrue("android_updater: empty body with its own digest downloaded", result is ApkDownloadResult.Downloaded)
        assertEquals("android_updater: the destination is empty", 0L, dest.length())
    }

    @Test
    fun `a body of exactly the cap is accepted`() {
        val dest = destFile("exact.apk")
        val transport = FakeOpenTransport { _ -> StreamReply(200, null, null, -1L, countingStream(APK_CAP_BYTES)) }
        val result = download(transport, apkUrl, sha256HexOfCountingStream(APK_CAP_BYTES), dest = dest)
        assertTrue("android_updater: exactly the cap downloaded", result is ApkDownloadResult.Downloaded)
        assertEquals("android_updater: every byte landed", APK_CAP_BYTES, dest.length())
        dest.delete()
    }

    @Test
    fun `an announced length of exactly the cap is not refused as too large`() {
        // The body ends short, so the answer is the length mismatch; a check that refuses the cap itself would answer too large.
        val transport = FakeOpenTransport { _ -> StreamReply(200, null, null, APK_CAP_BYTES, CountingStream("ab".toByteArray())) }
        assertRefused(download(transport, apkUrl, abcSha256), ApkDownloadReasons.LENGTH_MISMATCH)
    }

    @Test
    fun `the reader stops at the cap and does not drain the rest of a longer stream`() {
        val stream = MeteredStream(APK_CAP_BYTES + 1_000_000L)
        val transport = FakeOpenTransport { _ -> StreamReply(200, null, null, -1L, stream) }
        assertRefused(download(transport, apkUrl, abcSha256), ApkDownloadReasons.REPLY_TOO_LARGE)
        assertTrue(
            "android_updater: read ${stream.emitted} bytes, more than the cap and one buffer",
            stream.emitted <= APK_CAP_BYTES + 8192L,
        )
        assertFalse("android_updater: no .part left after the cap", File(tmpDir, "app.apk.part").exists())
    }

    @Test
    fun `the stream is opened with the APK limits`() {
        val transport = FakeOpenTransport { _ -> streamOf("abc".toByteArray()) }
        download(transport, apkUrl, abcSha256)
        assertEquals("android_updater: opened once", 1, transport.opened.size)
        val request = transport.opened[0]
        assertEquals("android_updater: method", CallMethod.GET, request.method)
        assertEquals("android_updater: url", apkUrl, request.url)
        assertEquals("android_updater: connect timeout", 10_000, request.limits.connectTimeoutMs)
        assertEquals("android_updater: read timeout", 30_000, request.limits.readTimeoutMs)
        assertEquals("android_updater: reply cap", 268_435_456L, request.limits.maxReplyBytes)
    }

    @Test
    fun `https with an explicit 443 and without a port are the same origin both ways`() {
        val pairs = listOf(
            "https://breaker.test:443/app.apk" to "https://breaker.test",
            "https://breaker.test/app.apk" to "https://breaker.test:443",
        )
        for ((url, server) in pairs) {
            val transport = FakeOpenTransport { _ -> streamOf("abc".toByteArray()) }
            val result = download(transport, url, abcSha256, server = server)
            assertTrue("android_updater: $url against $server", result is ApkDownloadResult.Downloaded)
        }
    }

    @Test
    fun `a redirect that carries a location is refused and nothing is written`() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val transport = FakeOpenTransport { _ ->
                StreamReply(status, "https://breaker.test/other.apk", null, -1L, java.io.ByteArrayInputStream("abc".toByteArray()))
            }
            val dest = destFile()
            assertRefused(download(transport, apkUrl, abcSha256, dest = dest), ApkDownloadReasons.BAD_STATUS, "status $status")
            assertFalse("android_updater: no destination for $status", dest.exists())
            assertFalse("android_updater: no .part for $status", File(tmpDir, "app.apk.part").exists())
        }
    }

    @Test
    fun `a failed move deletes the temp file and says the destination is not usable`() {
        val occupied = File(tmpDir, "occupied.apk")
        assertTrue("android_updater: the test directory", occupied.mkdir())
        File(occupied, "inner.txt").writeText("x")
        val transport = FakeOpenTransport { _ -> streamOf("abc".toByteArray()) }
        val result = download(transport, apkUrl, abcSha256, dest = occupied)
        assertRefused(result, ApkDownloadReasons.BAD_DESTINATION)
        assertFalse("android_updater: no .part left after a failed move", File(tmpDir, "occupied.apk.part").exists())
        assertTrue("android_updater: the occupied directory is untouched", File(occupied, "inner.txt").exists())
    }

    @Test
    fun `the move is an atomic move`() {
        // A unit test cannot see the move kind, so the option is pinned in the source.
        // Gradle runs tests with the module folder as the working directory.
        val source = File("src/main/kotlin/dev/breaker/dictation/updater/ApkDownload.kt")
        assertTrue("android_updater: the source file must exist: ${source.absolutePath}", source.isFile)
        assertTrue("android_updater: the move names ATOMIC_MOVE", source.readText().contains("StandardCopyOption.ATOMIC_MOVE"))
    }

    @Test
    fun `a stale temp file does not reach the finished file`() {
        val stale = File(tmpDir, "app.apk.part")
        Files.write(stale.toPath(), ByteArray(100) { 7 })
        val dest = destFile()
        val transport = FakeOpenTransport { _ -> streamOf("abc".toByteArray()) }
        val result = download(transport, apkUrl, abcSha256, dest = dest)
        assertTrue("android_updater: downloaded over a stale .part", result is ApkDownloadResult.Downloaded)
        assertArrayEquals("android_updater: the new bytes only", "abc".toByteArray(), Files.readAllBytes(dest.toPath()))
        assertFalse("android_updater: the .part is gone", stale.exists())
    }

    @Test
    fun `the reply is closed on every path`() {
        val cases = ArrayList<Pair<String, TrackedStream>>()

        val success = TrackedStream(java.io.ByteArrayInputStream("abc".toByteArray()))
        download(FakeOpenTransport { _ -> StreamReply(200, null, null, -1L, success) }, apkUrl, abcSha256)
        cases.add("success" to success)

        val wrongDigest = TrackedStream(java.io.ByteArrayInputStream("abc".toByteArray()))
        download(FakeOpenTransport { _ -> StreamReply(200, null, null, -1L, wrongDigest) }, apkUrl, sha256Hex("x".toByteArray()))
        cases.add("wrong digest" to wrongDigest)

        val errorStatus = TrackedStream(java.io.ByteArrayInputStream("abc".toByteArray()))
        download(FakeOpenTransport { _ -> StreamReply(500, null, null, -1L, errorStatus) }, apkUrl, abcSha256)
        cases.add("error status" to errorStatus)

        val tooLarge = TrackedStream(java.io.ByteArrayInputStream("abc".toByteArray()))
        download(FakeOpenTransport { _ -> StreamReply(200, null, null, APK_CAP_BYTES + 1, tooLarge) }, apkUrl, abcSha256)
        cases.add("announced too large" to tooLarge)

        val failing = TrackedStream(ThrowingStream("abcde".toByteArray(), IllegalStateException("x")))
        download(FakeOpenTransport { _ -> StreamReply(200, null, null, -1L, failing) }, apkUrl, abcSha256)
        cases.add("exception mid-read" to failing)

        for ((label, stream) in cases) {
            assertTrue("android_updater: the reply is closed after $label", stream.closed)
        }
    }

    @Test
    fun `every reason sentence is exactly its fixed text and holds no address or number`() {
        val expected = listOf(
            "the offered digest is not usable" to ApkDownloadReasons.BAD_DIGEST,
            "the update address is not the configured server" to ApkDownloadReasons.NOT_SAME_ORIGIN,
            "the update address is not allowed" to ApkDownloadReasons.NOT_ALLOWED,
            "the server answered with an error" to ApkDownloadReasons.BAD_STATUS,
            "the reply is larger than its cap" to ApkDownloadReasons.REPLY_TOO_LARGE,
            "the body ended before its announced length" to ApkDownloadReasons.LENGTH_MISMATCH,
            "the server could not be reached" to ApkDownloadReasons.UNREACHABLE,
            "the download could not finish" to ApkDownloadReasons.UNFINISHED,
            "the destination is not usable" to ApkDownloadReasons.BAD_DESTINATION,
        )
        for ((text, constant) in expected) {
            assertEquals("android_updater: the text of a reason", text, constant)
            assertFalse("android_updater: no host in $text", constant.contains("breaker.test") || constant.contains("://"))
            assertFalse("android_updater: no digit in $text", constant.any { it in '0'..'9' })
        }
        assertEquals("android_updater: no two reasons share a text", expected.size, expected.map { it.second }.toSet().size)
    }

    @Test
    fun `a refusal in the stream with another reason maps to not allowed`() {
        val transport = FakeOpenTransport { _ ->
            StreamReply(200, null, null, -1L, RefusalStream(HttpRefusalReason.NOT_HTTPS))
        }
        val dest = destFile()
        assertRefused(download(transport, apkUrl, abcSha256, dest = dest), ApkDownloadReasons.NOT_ALLOWED)
        assertFalse("android_updater: no .part left", File(tmpDir, "app.apk.part").exists())
    }

    @Test
    fun `an interrupted read stops the download, deletes the temp file and keeps the old file`() {
        // InterruptedIOException is an IOException, so the answer is the dropped-connection sentence, and the read is not retried.
        val dest = destFile()
        Files.write(dest.toPath(), byteArrayOf(1, 2, 3))
        val transport = FakeOpenTransport { _ -> StreamReply(200, null, null, -1L, InterruptedOnceStream()) }
        assertRefused(download(transport, apkUrl, abcSha256, dest = dest), ApkDownloadReasons.UNREACHABLE)
        assertFalse("android_updater: no .part left after an interrupt", File(tmpDir, "app.apk.part").exists())
        assertArrayEquals("android_updater: old destination kept", byteArrayOf(1, 2, 3), Files.readAllBytes(dest.toPath()))
    }

    @Test
    fun `a digest of 63 or 65 hex characters or with one non-hex character is refused before any connection`() {
        val digests = listOf(
            "a".repeat(63),
            "a".repeat(65),
            "a".repeat(64) + "a",
            "a".repeat(63) + "g",
        )
        for (digest in digests) {
            val transport = FakeOpenTransport { _ -> streamOf("abc".toByteArray()) }
            assertRefused(download(transport, apkUrl, digest), ApkDownloadReasons.BAD_DIGEST, "digest of ${digest.length} characters")
            assertEquals("android_updater: no open for a bad digest of ${digest.length}", 0, transport.opened.size)
        }
    }

    private fun assertRefused(result: ApkDownloadResult, expected: String, label: String = "") {
        assertTrue("android_updater: refused $label", result is ApkDownloadResult.Refused)
        assertEquals("android_updater: fixed reason $label", expected, (result as ApkDownloadResult.Refused).reason)
    }

    companion object {
        const val APK_CAP_BYTES: Long = 268_435_456L
    }
}
