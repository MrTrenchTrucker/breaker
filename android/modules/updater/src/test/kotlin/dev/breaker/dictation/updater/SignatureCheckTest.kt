package dev.breaker.dictation.updater

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class SignatureCheckTest {

    private fun makeKeyPair(): Pair<Ed25519PrivateKeyParameters, ByteArray> {
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        return priv to priv.generatePublicKey().encoded
    }

    private fun sign(priv: Ed25519PrivateKeyParameters, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun makeChecker(vararg keys: ByteArray): SignatureCheck =
        SignatureCheck(keys.toList())

    private val pinnedMessageBytes: ByteArray =
        ("breaker-update-v1\n" + VERSION + "\n" + SHA256.lowercase() + "\n").toByteArray(
            Charsets.UTF_8
        )

    @Test
    fun `a valid signature under the pinned key verifies`() {
        val (priv, pub) = makeKeyPair()
        val message = pinnedMessageBytes
        val signature = sign(priv, message)
        val result = makeChecker(pub).verify(VERSION, SHA256, base64(signature))
        assertTrue("android_updater: valid signature verifies", result is SignatureResult.Verified)
    }

    @Test
    fun `two pinned keys both verify their own signatures`() {
        val (priv1, pub1) = makeKeyPair()
        val (priv2, pub2) = makeKeyPair()
        val checker = makeChecker(pub1, pub2)
        val message = pinnedMessageBytes
        val sig1 = sign(priv1, message)
        val sig2 = sign(priv2, message)
        assertTrue("android_updater: key1 verifies",
            checker.verify(VERSION, SHA256, base64(sig1)) is SignatureResult.Verified)
        assertTrue("android_updater: key2 verifies",
            checker.verify(VERSION, SHA256, base64(sig2)) is SignatureResult.Verified)
    }

    @Test
    fun `bad signature is refused`() {
        val (_, pub) = makeKeyPair()
        val result = makeChecker(pub).verify(VERSION, SHA256, base64(ByteArray(64) { 42 }))
        assertFalse("android_updater: bad signature refused", result is SignatureResult.Verified)
        val refused = result as SignatureResult.Refused
        assertFalse("android_updater: reason holds no signature bytes",
            refused.reason.contains(base64(ByteArray(64) { 42 })))
    }

    @Test
    fun `signature for a different version is refused`() {
        val (priv, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        val otherVersion = "0.0.0.2"
        val message = ("breaker-update-v1\n" + otherVersion + "\n" + SHA256.lowercase() + "\n")
            .toByteArray(Charsets.UTF_8)
        val signature = sign(priv, message)
        val result = checker.verify(VERSION, SHA256, base64(signature))
        assertFalse("android_updater: different version refused", result is SignatureResult.Verified)
    }

    @Test
    fun `signature for a different sha256 is refused`() {
        val (priv, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        val otherDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val message = ("breaker-update-v1\n" + VERSION + "\n" + otherDigest + "\n")
            .toByteArray(Charsets.UTF_8)
        val signature = sign(priv, message)
        val result = checker.verify(VERSION, SHA256, base64(signature))
        assertFalse("android_updater: different digest refused", result is SignatureResult.Verified)
    }

    @Test
    fun `wrong-length base64 signature is refused`() {
        val (_, pub) = makeKeyPair()
        val result = makeChecker(pub).verify(VERSION, SHA256, base64(ByteArray(63)))
        assertFalse("android_updater: wrong-length signature refused", result is SignatureResult.Verified)
    }

    @Test
    fun `non-canonical base64 signature is refused`() {
        val (_, pub) = makeKeyPair()
        val result = makeChecker(pub).verify(VERSION, SHA256, "not-valid-base64!!!")
        assertFalse("android_updater: non-canonical base64 refused", result is SignatureResult.Verified)
    }

    @Test
    fun `empty signature is refused`() {
        val (_, pub) = makeKeyPair()
        val result = makeChecker(pub).verify(VERSION, SHA256, "")
        assertFalse("android_updater: empty signature refused", result is SignatureResult.Verified)
    }

    @Test
    fun `a valid signature by a key that is not in the list is refused`() {
        val (_, pub) = makeKeyPair()
        val (otherPriv, _) = makeKeyPair()
        val signature = sign(otherPriv, pinnedMessageBytes)
        val result = makeChecker(pub).verify(VERSION, SHA256, base64(signature))
        assertEquals("android_updater: key not in list refused", SignatureResult.Refused(SignatureCheckReasons.SIGNATURE_NOT_ACCEPTED), result)
    }

    @Test
    fun `a key of the wrong length is ignored and a good key beside it still verifies`() {
        val (priv, pub) = makeKeyPair()
        val checker = makeChecker(ByteArray(31) { 7 }, pub, ByteArray(33) { 7 })
        val result = checker.verify(VERSION, SHA256, base64(sign(priv, pinnedMessageBytes)))
        assertTrue("android_updater: good key verifies beside bad keys", result is SignatureResult.Verified)
    }

    @Test
    fun `a list of only wrong length keys is the same as an empty list`() {
        val result = makeChecker(ByteArray(31), ByteArray(0)).verify(VERSION, SHA256, base64(ByteArray(64)))
        assertEquals("android_updater: only bad keys", SignatureResult.Refused(SignatureCheckReasons.NO_PINNED_KEY), result)
    }

    @Test
    fun `base64 that is valid but not the canonical text of the signature is refused`() {
        val (priv, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        val good = base64(sign(priv, pinnedMessageBytes))
        assertTrue("android_updater: the canonical text verifies", checker.verify(VERSION, SHA256, good) is SignatureResult.Verified)
        val variants = mapOf(
            "trailing newline" to good + "\n",
            "leading space" to " " + good,
            "inner newline" to good.substring(0, 40) + "\n" + good.substring(40),
            "padding removed" to good.trimEnd('='),
            "url safe alphabet" to good.replace('+', '-').replace('/', '_'),
            "mime wrapped" to good.chunked(76).joinToString("\r\n"),
        )
        for ((label, text) in variants) {
            if (text == good) continue // a signature whose text has no + / or = cannot show this variant
            val result = checker.verify(VERSION, SHA256, text)
            assertFalse("android_updater: $label refused", result is SignatureResult.Verified)
        }
    }

    @Test
    fun `empty pinned key list is refused`() {
        val result = makeChecker().verify(VERSION, SHA256, base64(ByteArray(64)))
        assertFalse("android_updater: empty key list refused", result is SignatureResult.Verified)
        val refused = result as SignatureResult.Refused
        assertEquals("android_updater: empty list reason",
            SignatureCheckReasons.NO_PINNED_KEY, refused.reason)
    }

    @Test
    fun `blank version is refused`() {
        val (_, pub) = makeKeyPair()
        val result = makeChecker(pub).verify("", SHA256, base64(ByteArray(64)))
        assertFalse("android_updater: blank version refused", result is SignatureResult.Verified)
    }

    @Test
    fun `version with control character is refused`() {
        val (_, pub) = makeKeyPair()
        val result = makeChecker(pub).verify(VERSION + "\n", SHA256, base64(ByteArray(64)))
        assertFalse("android_updater: control-char version refused", result is SignatureResult.Verified)
    }

    @Test
    fun `version with trailing space is refused for the plain version`() {
        val (priv, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        // Sign a version with a trailing space; verify the plain version.
        val spaced = VERSION + " "
        val message = ("breaker-update-v1\n" + spaced + "\n" + SHA256.lowercase() + "\n")
            .toByteArray(Charsets.UTF_8)
        val signature = sign(priv, message)
        val result = checker.verify(VERSION, SHA256, base64(signature))
        assertFalse("android_updater: trailing-space version refused for plain version",
            result is SignatureResult.Verified)
    }

    @Test
    fun `sha256 that is not 64 hex digits is refused`() {
        val (_, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        assertFalse("android_updater: short digest refused",
            checker.verify(VERSION, "abc", base64(ByteArray(64))) is SignatureResult.Verified)
        assertFalse("android_updater: non-hex digest refused",
            checker.verify(VERSION, SHA256.substring(0, 63) + 'g', base64(ByteArray(64))) is SignatureResult.Verified)
    }

    @Test
    fun `uppercase manifest digest verifies over lowercase message`() {
        val (priv, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        val message = pinnedMessageBytes
        val signature = sign(priv, message)
        val result = checker.verify(VERSION, SHA256.uppercase(), base64(signature))
        assertTrue("android_updater: uppercase digest verifies", result is SignatureResult.Verified)
    }

    @Test
    fun `the signed message is built byte for byte as the ruling says`() {
        val expected = "breaker-update-v1\n0.0.0.1\nba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\n"
        // A signature over the literal text verifies, so the code builds exactly these bytes.
        val (priv, pub) = makeKeyPair()
        val signature = sign(priv, expected.toByteArray(Charsets.US_ASCII))
        assertTrue(
            "android_updater: the literal message verifies",
            makeChecker(pub).verify("0.0.0.1", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", base64(signature)) is SignatureResult.Verified,
        )
        // And none of these near misses does: CRLF, no final line feed, a space before the digest.
        for (nearMiss in listOf(expected.replace("\n", "\r\n"), expected.trimEnd('\n'), expected.replace("\nba78", "\n ba78"))) {
            val bad = sign(priv, nearMiss.toByteArray(Charsets.US_ASCII))
            assertFalse(
                "android_updater: a near miss message does not verify",
                makeChecker(pub).verify("0.0.0.1", SHA256, base64(bad)) is SignatureResult.Verified,
            )
        }
    }

    @Test
    fun `no key bytes or signature bytes ever reach a reason`() {
        val (priv, pub) = makeKeyPair()
        val message = pinnedMessageBytes
        val signature = sign(priv, message)
        val signatureText = base64(signature)
        val badResult = makeChecker(pub).verify(VERSION, "wrongdigest!" + SHA256.substring(54),
            signatureText)
        val badReason = (badResult as SignatureResult.Refused).reason
        assertFalse("android_updater: reason holds no signature text",
            badReason.contains(signatureText))
        assertFalse("android_updater: reason holds no key bytes",
            badReason.contains(base64(pub)))
    }

    /** Signs the exact message text the ruling describes for [version] and [digest] (digest lower-cased). */
    private fun signFor(priv: Ed25519PrivateKeyParameters, version: String, digest: String): String =
        base64(sign(priv, ("breaker-update-v1\n" + version + "\n" + digest.lowercase() + "\n").toByteArray(Charsets.UTF_8)))

    private fun refusedWith(result: SignatureResult): String {
        assertTrue("android_updater: expected a refusal, got $result", result is SignatureResult.Refused)
        return (result as SignatureResult.Refused).reason
    }

    @Test
    fun `a digest of 63 or 65 hex digits with a valid signature is refused as a bad digest`() {
        val (priv, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        for (digest in listOf(SHA256.substring(0, 63), SHA256 + "a")) {
            val signature = signFor(priv, VERSION, digest)
            assertEquals(
                "android_updater: digest of ${digest.length} characters",
                SignatureCheckReasons.BAD_DIGEST,
                refusedWith(checker.verify(VERSION, digest, signature)),
            )
        }
    }

    @Test
    fun `a digest with a character that is not hex and a valid signature is refused as a bad digest`() {
        val (priv, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        for (digest in listOf("g".repeat(64), SHA256.substring(0, 10) + "g" + SHA256.substring(11))) {
            val signature = signFor(priv, VERSION, digest)
            assertEquals(
                "android_updater: non-hex digest",
                SignatureCheckReasons.BAD_DIGEST,
                refusedWith(checker.verify(VERSION, digest, signature)),
            )
        }
    }

    @Test
    fun `the checks run in the order version, digest, signature text, pinned keys`() {
        val (_, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        assertEquals(
            "android_updater: a bad version and a bad digest answer the version",
            SignatureCheckReasons.BAD_VERSION,
            refusedWith(checker.verify("", "abc", "not-valid-base64!!!")),
        )
        assertEquals(
            "android_updater: a bad digest and a bad signature answer the digest",
            SignatureCheckReasons.BAD_DIGEST,
            refusedWith(checker.verify(VERSION, "abc", "not-valid-base64!!!")),
        )
        assertEquals(
            "android_updater: a bad signature text and no pinned key answer the signature",
            SignatureCheckReasons.BAD_SIGNATURE,
            refusedWith(makeChecker().verify(VERSION, SHA256, "not-valid-base64!!!")),
        )
    }

    @Test
    fun `a version of only spaces or only a tab is refused as a bad version`() {
        val (priv, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        for (version in listOf("   ", "\t")) {
            val signature = signFor(priv, version, SHA256)
            assertEquals(
                "android_updater: blank version",
                SignatureCheckReasons.BAD_VERSION,
                refusedWith(checker.verify(version, SHA256, signature)),
            )
        }
    }

    @Test
    fun `a version with a carriage return, a tab or a NUL is refused as a bad version`() {
        val (priv, pub) = makeKeyPair()
        val checker = makeChecker(pub)
        for (version in listOf("0.0.0.1\r", "0.0.\t0.1", "0.0.0\u0000.1", "\n0.0.0.1")) {
            val signature = signFor(priv, version, SHA256)
            assertEquals(
                "android_updater: control character in the version",
                SignatureCheckReasons.BAD_VERSION,
                refusedWith(checker.verify(version, SHA256, signature)),
            )
        }
    }

    @Test
    fun `a signature the base64 decoder rejects gets the fixed sentence and no decoder text`() {
        val (_, pub) = makeKeyPair()
        val reason = refusedWith(makeChecker(pub).verify(VERSION, SHA256, "not-valid-base64!!!"))
        assertEquals("android_updater: the fixed sentence", "the update signature is not usable", reason)
        assertFalse("android_updater: no decoder text", reason.contains("Illegal") || reason.contains("base64") || reason.contains("!"))
    }

    @Test
    fun `a key of 33 bytes that starts with the real key and a key of 31 bytes are ignored`() {
        val (priv, pub) = makeKeyPair()
        val signature = signFor(priv, VERSION, SHA256)
        assertEquals(
            "android_updater: 33 byte key beside the real key bytes",
            SignatureCheckReasons.NO_PINNED_KEY,
            refusedWith(makeChecker(pub + byteArrayOf(9)).verify(VERSION, SHA256, signature)),
        )
        assertEquals(
            "android_updater: 31 byte key",
            SignatureCheckReasons.NO_PINNED_KEY,
            refusedWith(makeChecker(pub.copyOfRange(0, 31)).verify(VERSION, SHA256, signature)),
        )
    }

    @Test
    fun `every reason is exactly its fixed sentence and no two are the same`() {
        val expected = listOf(
            "the update version is not usable" to SignatureCheckReasons.BAD_VERSION,
            "the release digest is not usable" to SignatureCheckReasons.BAD_DIGEST,
            "the update signature is not usable" to SignatureCheckReasons.BAD_SIGNATURE,
            "no pinned update key is configured" to SignatureCheckReasons.NO_PINNED_KEY,
            "no pinned key accepts the update signature" to SignatureCheckReasons.SIGNATURE_NOT_ACCEPTED,
        )
        for ((text, constant) in expected) {
            assertEquals("android_updater: the text of a reason", text, constant)
        }
        assertEquals("android_updater: no two reasons share a text", expected.size, expected.map { it.second }.toSet().size)
    }

    @Test
    fun `a key array changed after the check is built does not change the result`() {
        val (priv, pub) = makeKeyPair()
        val kept = pub.copyOf()
        val checker = makeChecker(kept)
        kept[0] = (kept[0] + 1).toByte()
        val result = checker.verify(VERSION, SHA256, signFor(priv, VERSION, SHA256))
        assertTrue("android_updater: the check kept its own copy of the key", result is SignatureResult.Verified)
    }

    @Test
    fun `a third keys signature against a list of two keys is refused`() {
        val (_, pub1) = makeKeyPair()
        val (_, pub2) = makeKeyPair()
        val (priv3, _) = makeKeyPair()
        val result = makeChecker(pub1, pub2).verify(VERSION, SHA256, signFor(priv3, VERSION, SHA256))
        assertEquals(
            "android_updater: a signature by a key outside the list",
            SignatureCheckReasons.SIGNATURE_NOT_ACCEPTED,
            refusedWith(result),
        )
    }

    @Test
    fun `the url safe alphabet is refused for a signature whose canonical text has a plus or a slash`() {
        // Ed25519 signing is deterministic, so a fixed seed always gives the same signature text; search the seeds for one.
        var found = false
        for (seed in 0..255) {
            val priv = Ed25519PrivateKeyParameters(ByteArray(32) { (it + seed).toByte() })
            val pub = priv.generatePublicKey().encoded
            val good = signFor(priv, VERSION, SHA256)
            if (!good.contains('+') && !good.contains('/')) continue
            found = true
            val checker = makeChecker(pub)
            assertTrue("android_updater: the canonical text verifies", checker.verify(VERSION, SHA256, good) is SignatureResult.Verified)
            val urlSafe = good.replace('+', '-').replace('/', '_')
            assertFalse("android_updater: the url safe text differs", urlSafe == good)
            assertFalse("android_updater: the url safe text is refused", checker.verify(VERSION, SHA256, urlSafe) is SignatureResult.Verified)
            break
        }
        assertTrue("android_updater: a fixed seed with a plus or a slash exists", found)
    }

    companion object {
        private const val VERSION = "0.0.0.1"
        private const val SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
