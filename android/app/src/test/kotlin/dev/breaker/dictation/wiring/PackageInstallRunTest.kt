package dev.breaker.dictation.wiring

import dev.breaker.dictation.updater.InstallReasons
import dev.breaker.dictation.updater.InstallResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageInstallRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val apkBytes: ByteArray = "apk bytes for the install run".toByteArray(Charsets.UTF_8)

    /** A session that records each call in [log]; a failure flag makes that call throw. */
    private class RecordingSession(
        private val log: MutableList<String>,
        private val failWrite: Boolean = false,
        private val failCommit: Boolean = false,
        private val failAbandon: Boolean = false,
        private val failClose: Boolean = false,
    ) : PackageSession {
        var written: ByteArray? = null
        var writtenLength: Long = -1L
        var stream: InputStream? = null

        override fun writeApk(input: InputStream, length: Long) {
            log.add("write")
            stream = input
            writtenLength = length
            if (failWrite) throw IOException("write refused")
            written = input.readBytes()
        }

        override fun commit() {
            log.add("commit")
            if (failCommit) throw IOException("commit refused")
        }

        override fun abandon() {
            log.add("abandon")
            if (failAbandon) throw IOException("abandon refused")
        }

        override fun close() {
            log.add("close")
            if (failClose) throw IOException("close refused")
        }
    }

    private fun apkFile(): File = File(tmp.root, "update.apk").also { it.writeBytes(apkBytes) }

    private fun runWith(session: PackageSession, apk: File): InstallResult =
        PackageInstallRun(PackageSessionOpener { session }).run(apk)

    private fun refused(): InstallResult = InstallResult.Refused(InstallReasons.NOT_STARTED)

    private fun isClosed(input: InputStream): Boolean =
        try {
            input.read()
            false
        } catch (e: IOException) {
            true
        }

    @Test
    fun `a successful run writes commits and closes and never abandons`() {
        val log = ArrayList<String>()
        val result = runWith(RecordingSession(log), apkFile())
        assertEquals("app: a good run must answer Started", InstallResult.Started, result)
        assertEquals("app: a good run must write, commit, then close", listOf("write", "commit", "close"), log)
    }

    @Test
    fun `a thrown write abandons then closes and refuses`() {
        val log = ArrayList<String>()
        val result = runWith(RecordingSession(log, failWrite = true), apkFile())
        assertEquals("app: a failed write must refuse", refused(), result)
        assertEquals("app: a failed write must abandon, then close", listOf("write", "abandon", "close"), log)
    }

    @Test
    fun `a thrown commit abandons then closes and refuses`() {
        val log = ArrayList<String>()
        val result = runWith(RecordingSession(log, failCommit = true), apkFile())
        assertEquals("app: a failed commit must refuse", refused(), result)
        assertEquals(
            "app: a failed commit must abandon, then close",
            listOf("write", "commit", "abandon", "close"),
            log,
        )
    }

    @Test
    fun `an open that throws refuses and closes nothing`() {
        var opened = 0
        val opener = PackageSessionOpener {
            opened += 1
            throw IOException("no session")
        }
        val result = PackageInstallRun(opener).run(apkFile())
        assertEquals("app: an open that throws must refuse", refused(), result)
        assertEquals("app: the opener must be asked once", 1, opened)
    }

    @Test
    fun `a throwing abandon and a throwing close change no answer`() {
        val log = ArrayList<String>()
        val session = RecordingSession(log, failWrite = true, failAbandon = true, failClose = true)
        val result = runWith(session, apkFile())
        assertEquals("app: a throwing abandon and close must not change the refusal", refused(), result)
        assertEquals("app: abandon and close must still be called", listOf("write", "abandon", "close"), log)
    }

    @Test
    fun `a throwing close alone changes no answer on a good run`() {
        val log = ArrayList<String>()
        val result = runWith(RecordingSession(log, failClose = true), apkFile())
        assertEquals("app: a throwing close must not change Started", InstallResult.Started, result)
    }

    @Test
    fun `a missing file abandons then closes and refuses`() {
        val log = ArrayList<String>()
        val missing = File(tmp.root, "missing.apk")
        val result = runWith(RecordingSession(log), missing)
        assertEquals("app: a missing file must refuse", refused(), result)
        assertEquals("app: a missing file must abandon, then close, with no write", listOf("abandon", "close"), log)
    }

    @Test
    fun `the session receives the file bytes and the file length`() {
        val session = RecordingSession(ArrayList())
        val apk = apkFile()
        runWith(session, apk)
        assertTrue("app: the session must receive the file bytes", apkBytes.contentEquals(session.written ?: ByteArray(0)))
        assertEquals("app: the length passed must be the file length", apk.length(), session.writtenLength)
    }

    @Test
    fun `the stream given to the session is closed after the run`() {
        val session = RecordingSession(ArrayList())
        runWith(session, apkFile())
        val input = checkNotNull(session.stream) { "app: the session must have been given a stream" }
        assertTrue("app: the stream must be closed after the run", isClosed(input))
    }
}
