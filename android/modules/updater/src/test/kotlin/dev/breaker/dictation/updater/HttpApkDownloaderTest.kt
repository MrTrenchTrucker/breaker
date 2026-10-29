package dev.breaker.dictation.updater

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the download into a staging slot: the cap, the status rules, the failure paths, the timeouts and the close. */
internal class HttpApkDownloaderTest {
    private val cap = 4L
    private val apkUrl = "https://breaker.example.test/releases/update.apk"
    private val mainRoot = File("src/main/kotlin")

    /** A slot that keeps the bytes it is given and counts how often it was opened and cleared. */
    private class RecordingSlot(
        private val refuseOutput: Boolean = false,
        private val failWrites: Boolean = false,
        private val failClose: Boolean = false,
    ) : StagingSlot {
        val written = ByteArrayOutputStream()

        var opened: Int = 0
            private set

        var removed: Int = 0
            private set

        override fun openForWrite(): OutputStream? {
            opened += 1
            if (refuseOutput) return null
            if (failWrites) return FailingOutput()
            if (failClose) return FailingCloseOutput(written)
            return written
        }

        override fun stagedApk(): StagedApk = object : StagedApk {
            override fun open(): InputStream? = null

            override fun discard() {
            }
        }

        override fun removeQuietly() {
            removed += 1
        }
    }

    private fun downloader(opener: UpdateHttpOpener, slot: StagingSlot): HttpApkDownloader =
        HttpApkDownloader(opener, slot, cap)

    private fun bytes(count: Int): ByteArray = ByteArray(count) { (it + 1).toByte() }

    /** The text of the one main source named [name]; fails when the main folder does not hold exactly one such file. */
    private fun mainSourceNamed(name: String): String {
        val found = mainRoot.walkTopDown().filter { it.isFile && it.name == name }.toList()
        assertTrue("the main source $name was not found under the main folder", found.size == 1)
        return found.single().readText()
    }

    @Test
    fun `a 200 stages a file one byte past the cap when the body is longer`() {
        val slot = RecordingSlot()
        val opener = ScriptedOpener(status = 200, body = ByteArrayInputStream(bytes(6)))
        val result = downloader(opener, slot).download(apkUrl)
        assertTrue(result is ApkDownload.Staged)
        assertArrayEquals(bytes(5), slot.written.toByteArray())
    }

    @Test
    fun `a body of exactly the cap is staged whole`() {
        val slot = RecordingSlot()
        val opener = ScriptedOpener(status = 200, body = ByteArrayInputStream(bytes(4)))
        assertTrue(downloader(opener, slot).download(apkUrl) is ApkDownload.Staged)
        assertArrayEquals(bytes(4), slot.written.toByteArray())
    }

    @Test
    fun `a body shorter than its declared length is unavailable and removes the file`() {
        val slot = RecordingSlot()
        val opener = ScriptedOpener(status = 200, contentLength = 10L, body = ByteArrayInputStream(bytes(4)))
        assertEquals(ApkDownload.Unavailable, downloader(opener, slot).download(apkUrl))
        assertEquals(1, slot.removed)
    }

    @Test
    fun `a 404 is unavailable and opens no output`() {
        val slot = RecordingSlot()
        val opener = ScriptedOpener(status = 404, body = ByteArrayInputStream(bytes(4)))
        assertEquals(ApkDownload.Unavailable, downloader(opener, slot).download(apkUrl))
        assertEquals(0, slot.opened)
    }

    @Test
    fun `a 302 is unavailable and opens no output`() {
        val body = TrackedStream(bytes(4))
        val slot = RecordingSlot()
        val opener = ScriptedOpener(status = 302, body = body)
        assertEquals(ApkDownload.Unavailable, downloader(opener, slot).download(apkUrl))
        assertEquals(0, slot.opened)
        assertEquals(0, body.readCalls)
    }

    @Test
    fun `an opener failure is unavailable and opens no output`() {
        val slot = RecordingSlot()
        val opener = ScriptedOpener(failure = IOException("the test connection failed"))
        assertEquals(ApkDownload.Unavailable, downloader(opener, slot).download(apkUrl))
        assertEquals(0, slot.opened)
    }

    @Test
    fun `a slot that gives no output stream is unavailable`() {
        val body = TrackedStream(bytes(4))
        val slot = RecordingSlot(refuseOutput = true)
        val opener = ScriptedOpener(status = 200, body = body)
        assertEquals(ApkDownload.Unavailable, downloader(opener, slot).download(apkUrl))
        assertTrue("the reply must be closed", body.closed)
    }

    @Test
    fun `a write failure is unavailable and removes the partial file`() {
        val slot = RecordingSlot(failWrites = true)
        val opener = ScriptedOpener(status = 200, body = ByteArrayInputStream(bytes(4)))
        assertEquals(ApkDownload.Unavailable, downloader(opener, slot).download(apkUrl))
        assertEquals(1, slot.removed)
    }

    @Test
    fun `a failure while closing the output is unavailable and removes the partial file`() {
        val slot = RecordingSlot(failClose = true)
        val opener = ScriptedOpener(status = 200, body = ByteArrayInputStream(bytes(4)))
        assertEquals(ApkDownload.Unavailable, downloader(opener, slot).download(apkUrl))
        assertEquals(1, slot.removed)
    }

    @Test
    fun `the opener gets the apk address and the apk read timeout`() {
        val opener = ScriptedOpener(status = 200, body = ByteArrayInputStream(bytes(4)))
        downloader(opener, RecordingSlot()).download(apkUrl)
        assertEquals(
            listOf(OpenCall(apkUrl, HttpRules.CONNECT_TIMEOUT_MS, HttpRules.APK_READ_TIMEOUT_MS)),
            opener.calls,
        )
    }

    @Test
    fun `the reply is closed on every download path`() {
        for (status in listOf(200, 404, 302)) {
            val body = TrackedStream(bytes(4))
            downloader(ScriptedOpener(status = status, body = body), RecordingSlot()).download(apkUrl)
            assertTrue("the reply for status $status must be closed", body.closed)
        }
    }

    @Test
    fun `the download takes the byte cap as a constructor default of the named constant`() {
        val text = mainSourceNamed("HttpApkDownloader.kt")
        assertTrue(text.contains("private val capBytes: Long = MAX_APK_BYTES,"))
    }
}

/** An output that refuses every byte, as a full disk does. */
private class FailingOutput : OutputStream() {
    override fun write(b: Int) {
        throw IOException("the test output cannot take bytes")
    }
}

/** An output that keeps the bytes it is given and fails when it is closed. */
private class FailingCloseOutput(private val sink: OutputStream) : OutputStream() {
    override fun write(b: Int) {
        sink.write(b)
    }

    override fun close() {
        throw IOException("the test output cannot be closed")
    }
}
