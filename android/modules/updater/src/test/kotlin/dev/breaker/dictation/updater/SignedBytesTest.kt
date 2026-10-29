package dev.breaker.dictation.updater

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the exact bytes that a manifest signature covers, and the version code rule. */
internal class SignedBytesTest {
    @Test
    fun signedBytesAreExact() {
        val bytes = manifestSignedBytes(ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST)!!
        assertEquals(85, bytes.size)
        assertArrayEquals(ManifestVectors.MESSAGE.toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun upperCaseDigestIsRefusedInBuilder() {
        assertNull(manifestSignedBytes(ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST.uppercase()))
    }

    @Test
    fun nonCanonicalVersionCodesAreRefused() {
        val refused = listOf("0", "-1", "+1", "01", " 1", "1 ", "", "1a", "2100000001", "99999999999999999999")
        for (code in refused) {
            assertNull("version code [$code] must be refused", manifestSignedBytes(code, ManifestVectors.DIGEST))
        }
    }

    @Test
    fun boundaryVersionCodesAreAccepted() {
        assertNotNull(manifestSignedBytes("1", ManifestVectors.DIGEST))
        assertNotNull(manifestSignedBytes("2100000000", ManifestVectors.DIGEST))
    }
}
