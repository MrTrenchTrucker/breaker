package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.StreamReply
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The install chain from the built updater down to the signature check, with fakes only at the edges:
 * the transport, the installer, the clock and the timer. A release that the pinned key list accepts is
 * started. An empty key list refuses at verify time, after the download and before any launch, with the
 * no pinned key reason. A key list that does not hold the signing key refuses with the signature reason.
 */
class NoPinnedKeyChainTest {

    private val dir: File = Files.createTempDirectory("nopinnedkeychain").toFile()
    private val version = "1.4.2"
    private val apkUrl = "https://example.test/files/app-1.4.2.apk"
    private val apkBytes = byteArrayOf(10, 20, 30, 40, 50)
    private val sha256: String = hexOf(MessageDigest.getInstance("SHA-256").digest(apkBytes))
    private val signingKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKey: ByteArray = signingKey.generatePublicKey().encoded

    private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun signedBy(key: Ed25519PrivateKeyParameters): String {
        val message = ("breaker-update-v1\n" + version + "\n" + sha256 + "\n").toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(message, 0, message.size)
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }

    private fun release(signature: String): ReleaseInfo = ReleaseInfo(version, apkUrl, sha256, signature)

    /** What the installer was asked to launch, and what the caller was told. */
    private class Launches {
        val files = ArrayList<File>()
        val reports = ArrayList<InstallResult>()
    }

    /** Serves the release file as a streamed reply with status 200 and counts the opens. */
    private class Transport(private val bytes: ByteArray) : HttpTransport {
        var opened: Int = 0

        override fun send(request: CallRequest): BytesReply = throw IllegalStateException("no send in this test")

        override fun open(request: CallRequest): StreamReply {
            opened += 1
            return StreamReply(200, null, null, bytes.size.toLong(), ByteArrayInputStream(bytes))
        }
    }

    private class Times : UpdateTimes {
        var installAt: Long? = null

        override fun lastCheckAtMs(): Long? = null

        override fun recordCheckAtMs(atMs: Long) {
        }

        override fun recordUpdateAtMs(atMs: Long) {
            installAt = atMs
        }
    }

    private class NoWait : TimerScheduler {
        override fun scheduleOnce(afterMs: Long, task: Runnable) {
        }

        override fun cancel() {
        }
    }

    private fun build(pinned: List<ByteArray>, launches: Launches, times: Times, transport: Transport): Updater =
        buildUpdater(
            UpdaterConfig(
                transport = transport,
                serverUrl = { "https://example.test/" },
                apkFolder = { dir },
                pinnedKeys = pinned,
                isAdmin = { false },
                installedVersionCode = { 1L },
                installedVersionName = { "1.0.0" },
                installer = ApkInstaller { file ->
                    launches.files.add(file)
                    InstallResult.Started
                },
                times = times,
                nowMs = { 1000L },
                scheduler = NoWait(),
                onInstallResult = { launches.reports.add(it) },
            ),
        )

    @Test
    fun `a verified release under the pinned key is started and recorded once`() {
        val launches = Launches()
        val times = Times()
        build(listOf(publicKey), launches, times, Transport(apkBytes)).checker.install(release(signedBy(signingKey)))
        assertEquals("app: the install is started", listOf<InstallResult>(InstallResult.Started), launches.reports)
        assertEquals("app: the one file is launched", 1, launches.files.size)
        assertEquals("app: the launch time is recorded", 1000L, times.installAt)
    }

    @Test
    fun `no pinned key refuses at verify time and launches nothing and deletes the download`() {
        val launches = Launches()
        val times = Times()
        val transport = Transport(apkBytes)
        build(emptyList(), launches, times, transport).checker.install(release(signedBy(signingKey)))
        assertEquals("app: the download ran before the refusal", 1, transport.opened)
        assertEquals(
            "app: the refusal is the no pinned key reason",
            listOf<InstallResult>(InstallResult.Refused(SignatureCheckReasons.NO_PINNED_KEY)),
            launches.reports,
        )
        assertTrue("app: no install was launched", launches.files.isEmpty())
        assertNull("app: no launch time is recorded", times.installAt)
        assertFalse("app: the downloaded file is deleted", File(dir, "update.apk").exists())
    }

    @Test
    fun `a key list without the signing key refuses with the signature reason and launches nothing`() {
        val launches = Launches()
        val otherKey = Ed25519PrivateKeyParameters(SecureRandom()).generatePublicKey().encoded
        build(listOf(otherKey), launches, Times(), Transport(apkBytes)).checker.install(release(signedBy(signingKey)))
        assertEquals(
            "app: the signature reason",
            listOf<InstallResult>(InstallResult.Refused(SignatureCheckReasons.SIGNATURE_NOT_ACCEPTED)),
            launches.reports,
        )
        assertTrue("app: no install was launched", launches.files.isEmpty())
    }

    @Test
    fun `a malformed signature text answers its own reason before the no pinned key reason`() {
        val launches = Launches()
        build(emptyList(), launches, Times(), Transport(apkBytes)).checker.install(release("not-valid-base64!!!"))
        assertEquals(
            "app: the signature text reason comes first",
            listOf<InstallResult>(InstallResult.Refused(SignatureCheckReasons.BAD_SIGNATURE)),
            launches.reports,
        )
        assertTrue("app: no install was launched", launches.files.isEmpty())
    }
}
