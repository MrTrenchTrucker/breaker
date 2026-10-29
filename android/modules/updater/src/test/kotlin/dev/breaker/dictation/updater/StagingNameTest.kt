package dev.breaker.dictation.updater

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pins the staged file on disk: its fixed name, its replacement, its discard, its open and the failure cleanup. */
internal class StagingNameTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val apkUrl = "https://breaker.example.test/releases/update.apk"

    private fun downloadInto(slot: StagingSlot, url: String, body: ByteArray): ApkDownload {
        val opener = ScriptedOpener(status = 200, body = ByteArrayInputStream(body))
        return HttpApkDownloader(opener, slot).download(url)
    }

    @Test
    fun `the staged file has the fixed name and the URL does not name it`() {
        assertEquals("update-candidate.apk", STAGED_FILE_NAME)
        downloadInto(ApkStaging(tmp.root), "https://breaker.example.test/releases/evil.apk", ByteArray(3) { 7.toByte() })
        assertTrue(Files.exists(File(tmp.root, STAGED_FILE_NAME).toPath()))
        assertFalse(Files.exists(File(tmp.root, "evil.apk").toPath()))
    }

    @Test
    fun `a second download replaces the earlier bytes`() {
        val slot = ApkStaging(tmp.root)
        downloadInto(slot, apkUrl, ByteArray(8) { 1.toByte() })
        downloadInto(slot, apkUrl, ByteArray(3) { 2.toByte() })
        assertArrayEquals(ByteArray(3) { 2.toByte() }, Files.readAllBytes(File(tmp.root, STAGED_FILE_NAME).toPath()))
    }

    @Test
    fun `discard removes the staged file`() {
        val slot = ApkStaging(tmp.root)
        downloadInto(slot, apkUrl, ByteArray(3) { 2.toByte() })
        slot.stagedApk().discard()
        assertFalse(Files.exists(File(tmp.root, STAGED_FILE_NAME).toPath()))
    }

    @Test
    fun `discard of a missing file does not throw and leaves no leftover`() {
        val staged = FileStagedApk(File(tmp.root, "missing.apk"))
        staged.discard()
        assertFalse(staged.leftover)
    }

    @Test
    fun `discard of a file that cannot be removed does not throw and sets leftover`() {
        val target = File(tmp.root, STAGED_FILE_NAME)
        Files.createDirectory(target.toPath())
        Files.write(File(target, "child.bin").toPath(), ByteArray(1))
        val staged = FileStagedApk(target)
        staged.discard()
        assertTrue(staged.leftover)
    }

    @Test
    fun `open of a missing file is null`() {
        assertNull(FileStagedApk(File(tmp.root, "missing.apk")).open())
    }

    @Test
    fun `open of a staged file gives its bytes`() {
        val slot = ApkStaging(tmp.root)
        downloadInto(slot, apkUrl, ByteArray(9) { 9.toByte() })
        val stream = slot.stagedApk().open() ?: throw AssertionError("the staged file must open")
        stream.use { assertEquals(9, it.read()) }
    }

    @Test
    fun `a missing folder gives no output stream`() {
        assertNull(ApkStaging(File(tmp.root, "no-such-folder")).openForWrite())
    }

    @Test
    fun `a short body leaves no staged file behind`() {
        val slot = ApkStaging(tmp.root)
        val opener = ScriptedOpener(status = 200, contentLength = 10L, body = ByteArrayInputStream(ByteArray(4) { 1.toByte() }))
        assertEquals(ApkDownload.Unavailable, HttpApkDownloader(opener, slot).download(apkUrl))
        assertFalse(Files.exists(File(tmp.root, STAGED_FILE_NAME).toPath()))
    }
}
