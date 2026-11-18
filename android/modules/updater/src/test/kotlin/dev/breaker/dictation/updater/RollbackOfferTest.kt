package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.shared.api.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rollback offer: a higher code with a lower four-part name gives the release, anything else gives null. */
class RollbackOfferTest {

    private val installedCode = 10L
    private val installedName = "1.2.3.4"

    private val rolledBack = ReleaseInfo(
        "1.2.3.3",
        "https://example.com/files/rollback.apk",
        "digest-rollback",
        "signature-rollback",
        "Rolled back",
    )

    private fun offer(
        version: String = "1.2.3.3",
        versionCode: Long = 11L,
        apkUrl: String = "https://example.com/files/rollback.apk",
        sha256: String = "digest-rollback",
        signature: String = "signature-rollback",
        notes: String = "Rolled back",
    ): UpdateManifest = UpdateManifest(version, versionCode, apkUrl, sha256, signature, notes)

    @Test
    fun `a higher code with a lower name is a rollback to the release`() {
        assertEquals(
            "android_updater: the release with its five fields",
            rolledBack,
            RollbackOffer.recognise(installedCode, installedName, offer()),
        )
    }

    @Test
    fun `a higher code with a higher name is not a rollback`() {
        assertNull(
            "android_updater: a higher name",
            RollbackOffer.recognise(installedCode, installedName, offer(version = "1.2.3.5")),
        )
    }

    @Test
    fun `an equal code with a lower name is not a rollback`() {
        assertNull(
            "android_updater: an equal code",
            RollbackOffer.recognise(installedCode, installedName, offer(versionCode = 10L)),
        )
    }

    @Test
    fun `a lower code with a lower name is not a rollback`() {
        assertNull(
            "android_updater: a lower code",
            RollbackOffer.recognise(installedCode, installedName, offer(versionCode = 9L)),
        )
    }

    @Test
    fun `an installed name that is not comparable gives no rollback`() {
        assertNull(
            "android_updater: installed name not comparable",
            RollbackOffer.recognise(installedCode, "0.1.0-debug", offer()),
        )
    }

    @Test
    fun `an offered name that is not comparable gives no rollback`() {
        assertNull(
            "android_updater: offered name not comparable",
            RollbackOffer.recognise(installedCode, installedName, offer(version = "1.2.3")),
        )
    }

    @Test
    fun `an offered name equal to the installed name is not a rollback`() {
        assertNull(
            "android_updater: equal name",
            RollbackOffer.recognise(installedCode, installedName, offer(version = installedName)),
        )
    }

    @Test
    fun `a blank field in the offer gives no rollback`() {
        assertNull(
            "android_updater: blank apk address",
            RollbackOffer.recognise(installedCode, installedName, offer(apkUrl = "   ")),
        )
        assertNull(
            "android_updater: blank digest",
            RollbackOffer.recognise(installedCode, installedName, offer(sha256 = "   ")),
        )
        assertNull(
            "android_updater: blank signature",
            RollbackOffer.recognise(installedCode, installedName, offer(signature = "   ")),
        )
    }

    @Test
    fun `a later part of ten is not a rollback below a part of nine`() {
        assertNull(
            "android_updater: numeric order, not text order",
            RollbackOffer.recognise(installedCode, "1.2.9.0", offer(version = "1.2.10.0")),
        )
    }

    @Test
    fun `an installed code at the largest value gives no rollback`() {
        assertNull(
            "android_updater: largest installed code",
            RollbackOffer.recognise(Long.MAX_VALUE, installedName, offer()),
        )
    }
}
