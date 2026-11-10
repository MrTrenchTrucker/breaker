package dev.breaker.dictation.updater

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CancellationException
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVerificationTest {

    private val tmpDir: File = Files.createTempDirectory("updateverification").toFile()

    private val version = "1.4.2"
    private val apkUrl = "https://breaker.test/files/app-1.4.2.apk"
    private val sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val signature = "c2lnbmF0dXJlLXRleHQ="

    /** Records what each step was asked and answers as scripted. */
    private class Steps(
        val downloadAnswer: () -> ApkDownloadResult,
        val signatureAnswer: () -> SignatureResult,
    ) {
        val downloadCalls = ArrayList<Pair<String, String>>()
        val signatureCalls = ArrayList<Triple<String, String, String>>()

        fun verification() = UpdateVerification(
            download = { url, digest ->
                downloadCalls.add(url to digest)
                downloadAnswer()
            },
            checkSignature = { v, digest, sig ->
                signatureCalls.add(Triple(v, digest, sig))
                signatureAnswer()
            },
        )
    }

    private fun apkFile(name: String = "app.apk"): File {
        val file = File(tmpDir, name)
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file
    }

    private fun run(steps: Steps): UpdateVerificationResult =
        steps.verification().verify(version, apkUrl, sha256, signature)

    @Test
    fun `a download and a signature that both pass give ready and keep the file`() {
        val file = apkFile("ready.apk")
        val steps = Steps({ ApkDownloadResult.Downloaded(file) }, { SignatureResult.Verified })
        val result = run(steps)
        assertEquals("android_updater: ready", UpdateVerificationResult.Ready(file), result)
        assertTrue("android_updater: the verified file is kept", file.exists())
    }

    @Test
    fun `a refused download answers its own reason and never reaches the signature check`() {
        val steps = Steps({ ApkDownloadResult.Refused(ApkDownloadReasons.BAD_STATUS) }, { SignatureResult.Verified })
        val result = run(steps)
        assertEquals(
            "android_updater: the download reason",
            UpdateVerificationResult.Refused(ApkDownloadReasons.BAD_STATUS),
            result,
        )
        assertEquals("android_updater: the signature check was never called", 0, steps.signatureCalls.size)
    }

    @Test
    fun `a refused signature answers the signature reason and deletes the downloaded file`() {
        val file = apkFile("refused.apk")
        val steps = Steps(
            { ApkDownloadResult.Downloaded(file) },
            { SignatureResult.Refused(SignatureCheckReasons.SIGNATURE_NOT_ACCEPTED) },
        )
        val result = run(steps)
        assertEquals(
            "android_updater: the signature reason",
            UpdateVerificationResult.Refused(SignatureCheckReasons.SIGNATURE_NOT_ACCEPTED),
            result,
        )
        assertFalse("android_updater: the unverified file is gone", file.exists())
    }

    @Test
    fun `the signature check is given the manifest version, digest and signature exactly`() {
        val steps = Steps({ ApkDownloadResult.Downloaded(apkFile("exact.apk")) }, { SignatureResult.Verified })
        run(steps)
        assertEquals("android_updater: one signature call", 1, steps.signatureCalls.size)
        assertEquals("android_updater: the values it got", Triple(version, sha256, signature), steps.signatureCalls[0])
    }

    @Test
    fun `the download is given the apk address and the manifest digest exactly`() {
        val steps = Steps({ ApkDownloadResult.Refused(ApkDownloadReasons.UNFINISHED) }, { SignatureResult.Verified })
        run(steps)
        assertEquals("android_updater: one download call", 1, steps.downloadCalls.size)
        assertEquals("android_updater: the values it got", apkUrl to sha256, steps.downloadCalls[0])
    }

    @Test
    fun `an exception from the download becomes the fixed refusal and the signature check is not called`() {
        val steps = Steps({ throw IllegalStateException("SECRET-LEAK-1") }, { SignatureResult.Verified })
        val result = run(steps)
        assertEquals(
            "android_updater: the fixed refusal",
            UpdateVerificationResult.Refused(UpdateVerificationReasons.UNEXPECTED),
            result,
        )
        assertEquals("android_updater: no signature call", 0, steps.signatureCalls.size)
    }

    @Test
    fun `an exception from the signature check becomes the fixed refusal and deletes the file`() {
        val file = apkFile("thrown.apk")
        val steps = Steps({ ApkDownloadResult.Downloaded(file) }, { throw IllegalStateException("SECRET-LEAK-2") })
        val result = run(steps)
        assertEquals(
            "android_updater: the fixed refusal",
            UpdateVerificationResult.Refused(UpdateVerificationReasons.UNEXPECTED),
            result,
        )
        assertFalse("android_updater: the file is gone", file.exists())
    }

    @Test
    fun `an interrupt from either step is turned into the fixed refusal too`() {
        val fromDownload = run(Steps({ throw CancellationException("stop") }, { SignatureResult.Verified }))
        assertEquals(
            "android_updater: interrupt in the download",
            UpdateVerificationResult.Refused(UpdateVerificationReasons.UNEXPECTED),
            fromDownload,
        )
        val file = apkFile("interrupted.apk")
        val fromSignature = run(Steps({ ApkDownloadResult.Downloaded(file) }, { throw CancellationException("stop") }))
        assertEquals(
            "android_updater: interrupt in the signature check",
            UpdateVerificationResult.Refused(UpdateVerificationReasons.UNEXPECTED),
            fromSignature,
        )
    }

    @Test
    fun `a thread interrupt in either step is answered with the fixed refusal and the interrupt flag stays set`() {
        try {
            val fromDownload = run(
                Steps(
                    { Thread.currentThread().interrupt(); throw InterruptedException("stop") },
                    { SignatureResult.Verified },
                ),
            )
            assertEquals(
                "android_updater: thread interrupt in the download",
                UpdateVerificationResult.Refused(UpdateVerificationReasons.UNEXPECTED),
                fromDownload,
            )
            assertTrue("android_updater: flag kept after the download", Thread.currentThread().isInterrupted)
            Thread.interrupted()

            val file = apkFile("thread-interrupted.apk")
            val fromSignature = run(
                Steps(
                    { ApkDownloadResult.Downloaded(file) },
                    { Thread.currentThread().interrupt(); throw InterruptedException("stop") },
                ),
            )
            assertEquals(
                "android_updater: thread interrupt in the signature check",
                UpdateVerificationResult.Refused(UpdateVerificationReasons.UNEXPECTED),
                fromSignature,
            )
            assertTrue("android_updater: flag kept after the signature check", Thread.currentThread().isInterrupted)
            assertFalse("android_updater: the file is gone", file.exists())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a file that cannot be deleted still answers the signature reason`() {
        val occupied = File(tmpDir, "occupied.apk")
        assertTrue("android_updater: the test directory", occupied.mkdir())
        File(occupied, "inner.txt").writeText("x")
        val steps = Steps(
            { ApkDownloadResult.Downloaded(occupied) },
            { SignatureResult.Refused(SignatureCheckReasons.BAD_SIGNATURE) },
        )
        val result = run(steps)
        assertEquals(
            "android_updater: still the signature reason",
            UpdateVerificationResult.Refused(SignatureCheckReasons.BAD_SIGNATURE),
            result,
        )
        assertTrue("android_updater: the occupied directory is untouched", File(occupied, "inner.txt").exists())
    }

    @Test
    fun `the fixed refusal sentence is exactly its text and holds no address, key or exception text`() {
        assertEquals(
            "android_updater: the sentence",
            "the update verification could not finish",
            UpdateVerificationReasons.UNEXPECTED,
        )
        val steps = Steps({ throw IllegalStateException("SECRET-LEAK-3 $apkUrl") }, { SignatureResult.Verified })
        val reason = (run(steps) as UpdateVerificationResult.Refused).reason
        assertFalse("android_updater: no exception text", reason.contains("SECRET-LEAK-3"))
        assertFalse("android_updater: no address", reason.contains("breaker.test"))
        assertFalse("android_updater: no digit", reason.any { it in '0'..'9' })
    }

    @Test
    fun `the real signature check decides with a real key`() {
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val check = SignatureCheck(listOf(priv.generatePublicKey().encoded))
        val message = ("breaker-update-v1\n" + version + "\n" + sha256 + "\n").toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        val goodSignature = Base64.getEncoder().encodeToString(signer.generateSignature())

        val goodFile = apkFile("real-good.apk")
        val good = UpdateVerification({ _, _ -> ApkDownloadResult.Downloaded(goodFile) }, check::verify)
            .verify(version, apkUrl, sha256, goodSignature)
        assertEquals("android_updater: a real good signature", UpdateVerificationResult.Ready(goodFile), good)
        assertTrue("android_updater: kept", goodFile.exists())

        val badFile = apkFile("real-bad.apk")
        val bad = UpdateVerification({ _, _ -> ApkDownloadResult.Downloaded(badFile) }, check::verify)
            .verify("9.9.9", apkUrl, sha256, goodSignature)
        assertEquals(
            "android_updater: a signature for another version",
            UpdateVerificationResult.Refused(SignatureCheckReasons.SIGNATURE_NOT_ACCEPTED),
            bad,
        )
        assertFalse("android_updater: deleted", badFile.exists())
    }
}
