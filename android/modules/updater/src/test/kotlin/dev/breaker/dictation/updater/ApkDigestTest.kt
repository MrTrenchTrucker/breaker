package dev.breaker.dictation.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkDigestTest {

    private companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun `empty bytes match empty-hex digest`() {
        assertTrue("android_updater: empty bytes match empty-hex digest",
            ApkDigest.matches(byteArrayOf(), EMPTY_SHA256))
    }

    @Test
    fun `abc matches its digest`() {
        assertTrue("android_updater: abc matches its digest",
            ApkDigest.matches("abc".toByteArray(), ABC_SHA256))
    }

    @Test
    fun `upper-case hex matches case-insensitive`() {
        assertTrue("android_updater: upper-case hex matches",
            ApkDigest.matches("abc".toByteArray(), ABC_SHA256.uppercase()))
    }

    @Test
    fun `wrong digest is false`() {
        assertFalse("android_updater: wrong digest is false",
            ApkDigest.matches("abc".toByteArray(), ABC_SHA256.substring(0, 63) + 'e'))
    }

    @Test
    fun `empty expected is false`() {
        assertFalse("android_updater: empty expected is false",
            ApkDigest.matches(byteArrayOf(), ""))
    }

    @Test
    fun `short expected is false`() {
        assertFalse("android_updater: short expected is false",
            ApkDigest.matches(byteArrayOf(), EMPTY_SHA256.substring(0, 63)))
    }

    @Test
    fun `long expected is false`() {
        assertFalse("android_updater: long expected is false",
            ApkDigest.matches(byteArrayOf(), EMPTY_SHA256 + 'f'))
    }

    @Test
    fun `non-hex expected is false`() {
        assertFalse("android_updater: non-hex expected is false",
            ApkDigest.matches("abc".toByteArray(), ABC_SHA256.substring(0, 63) + 'g'))
    }
}
