package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The install trusts the registry entry's own pin (sha256 and byte size) and nothing from upstream's
 * list. The real fetcher runs over a scripted opener that records every request, so the list of
 * requested addresses is asserted in full.
 */
class ModelInstallerPinTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore

    private val archive = TarFixtures.tinyArchive()
    private val digest = httpTestSha256Hex(archive)
    private val modelUrl = HttpFixtures.ASSET_URL
    private val staleListUrl = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/761425283"

    @Before
    fun setUp() {
        store = LocalModelStore(tmp.newFolder("models"))
    }

    private fun installWith(opener: FakeOpener, sha256: String, sizeBytes: Long): ModelInstaller.InstallResult {
        val entry = testEntry(sizeMb = 1, sha256 = sha256, sizeBytes = sizeBytes)
        return ModelInstaller(store, fetcherOf(opener), usableSpace = { Long.MAX_VALUE }).install(entry)
    }

    private fun refused(result: ModelInstaller.InstallResult, claim: String): ModelInstaller.InstallResult.Refused =
        result as? ModelInstaller.InstallResult.Refused ?: throw AssertionError("$claim: expected a refusal but got $result")

    @Test
    fun `the install requests exactly the model address and no checksum address`() {
        // The second step stands for upstream's list; it must never be asked for.
        val opener = FakeOpener(serve(okBytes(archive)), serve(okBytes("model.archive\t$digest\n".toByteArray())))
        val result = installWith(opener, digest, archive.size.toLong())
        assertTrue("the install succeeds: $result", result.isInstalled)
        assertEquals("the full list of requested addresses", listOf(modelUrl), opener.requests.map { it.url })
        assertFalse("the list address was not requested", opener.requests.any { it.url == staleListUrl })
    }

    @Test
    fun `an unreachable upstream list does not matter`() {
        val opener = FakeOpener(serve(okBytes(archive)), failWith(java.io.IOException("list host down")))
        val result = installWith(opener, digest, archive.size.toLong())
        assertTrue("the install succeeds: $result", result.isInstalled)
        assertEquals(listOf(modelUrl), opener.requests.map { it.url })
    }

    @Test
    fun `an upstream list that now names a different digest does not matter`() {
        val changed = "model.archive\t${Fixtures.OTHER_PIN}\n".toByteArray()
        val opener = FakeOpener(serve(okBytes(archive)), serve(okBytes(changed)))
        val result = installWith(opener, digest, archive.size.toLong())
        assertTrue("the install succeeds: $result", result.isInstalled)
        assertEquals(listOf(modelUrl), opener.requests.map { it.url })
    }

    @Test
    fun `a wrong archive sha256 is refused and the file is deleted`() {
        val opener = FakeOpener(serve(okBytes(archive)))
        val result = refused(installWith(opener, Fixtures.OTHER_PIN, archive.size.toLong()), "wrong sha256")
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, result.refusal)
        assertFalse(store.isInstalled("tiny"))
        assertEquals("nothing is left in staging", emptyList<String>(), stagedNames(store.stagingDirectory()))
    }

    @Test
    fun `a wrong byte size is refused even when the sha256 is right`() {
        val opener = FakeOpener(serve(okBytes(archive)))
        val result = refused(installWith(opener, digest, archive.size.toLong() + 1L), "wrong size")
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, result.refusal)
        assertFalse(store.isInstalled("tiny"))
    }

    @Test
    fun `an entry built without a size is refused`() {
        val opener = FakeOpener(serve(okBytes(archive)))
        val result = refused(installWith(opener, digest, 0L), "size 0")
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, result.refusal)
        assertFalse(store.isInstalled("tiny"))
    }
}
