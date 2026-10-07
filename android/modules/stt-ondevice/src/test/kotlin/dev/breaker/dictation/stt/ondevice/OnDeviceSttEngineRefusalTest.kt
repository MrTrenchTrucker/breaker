package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.shared.models.ModelFamily
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * What the engine tells the user for each way the loader can refuse a model.
 *
 * Every arm is checked through both entry points that load a model, transcribe
 * and preload. The failure must be exactly the matching ErrorMapping value built
 * from the model id that was asked for, and the loader's own text must never
 * reach the user.
 */
class OnDeviceSttEngineRefusalTest {

    // A net for the failure arm only: no test relies on it, and the expected red
    // is always an assertion that names the arm.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    private val engines = ArrayList<OnDeviceSttEngine>()

    @After
    fun closeEngines() {
        engines.forEach { it.close() }
    }

    private val modelId = "model-x"

    private val loaderDetail = "detail-must-not-appear"

    private fun request(): SttRequest = SttRequest(
        pcm = floatArrayOf(0.1f, 0.2f, 0.3f),
        wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
        model = modelId,
        language = "en",
    )

    private fun refused(
        refusal: ModelLoader.Refusal,
        family: ModelFamily? = null,
        leftOnDisk: Boolean = false,
    ): ModelLoader.LoadResult.Refused =
        ModelLoader.LoadResult.Refused(refusal, loaderDetail, family, leftOnDisk)

    /** Runs [call]; an exception that escapes the engine becomes an assertion that names [claim]. */
    private fun <T> callOrFail(claim: String, call: () -> T): T =
        try {
            call()
        } catch (e: Throwable) {
            throw AssertionError("$claim: the call threw $e instead of returning a result", e)
        }

    private fun assertFailureFor(path: String, expected: SttResult.Failure, actual: SttResult?) {
        val detail = (actual as? SttResult.Failure)?.detail.orEmpty()
        assertFalse("$path: the loader's own text reached the user", detail.contains(loaderDetail))
        assertEquals("$path: wrong failure for the model id that was asked for", expected, actual)
    }

    private fun assertArm(arm: String, load: ModelLoader.LoadResult.Refused, expected: SttResult.Failure) {
        val engine = OnDeviceSttEngine(FakeLoader(load))
        engines.add(engine)

        val transcribed = callOrFail("$arm through transcribe") { engine.transcribe(request()) }
        assertFailureFor("$arm through transcribe", expected, transcribed)

        val preloaded = callOrFail("$arm through preload") { engine.preload(modelId) }
        assertFailureFor("$arm through preload", expected, preloaded)
    }

    @Test
    fun `an unknown model is reported as unknown for the requested id`() {
        assertArm("UNKNOWN_MODEL", refused(ModelLoader.Refusal.UNKNOWN_MODEL), ErrorMapping.unknownModel(modelId))
    }

    @Test
    fun `a model of another family is reported with that family and the requested id`() {
        assertArm(
            "WRONG_FAMILY with a family",
            refused(ModelLoader.Refusal.WRONG_FAMILY, family = ModelFamily.WHISPER),
            ErrorMapping.wrongFamily(modelId, ModelFamily.WHISPER.name),
        )
    }

    @Test
    fun `a model of another family without a family name is reported as an unknown family`() {
        assertArm(
            "WRONG_FAMILY without a family",
            refused(ModelLoader.Refusal.WRONG_FAMILY),
            ErrorMapping.wrongFamily(modelId, "unknown"),
        )
    }

    @Test
    fun `a model that is not installed is reported as not installed for the requested id`() {
        assertArm("NOT_INSTALLED", refused(ModelLoader.Refusal.NOT_INSTALLED), ErrorMapping.noModelInstalled(modelId))
    }

    @Test
    fun `unreadable checksums are reported for the requested id`() {
        assertArm(
            "CHECKSUMS_UNREADABLE",
            refused(ModelLoader.Refusal.CHECKSUMS_UNREADABLE),
            ErrorMapping.checksumsUnreadable(modelId),
        )
    }

    @Test
    fun `a model that failed verification and was deleted is reported as deleted for the requested id`() {
        assertArm(
            "VERIFICATION_REFUSED with the model deleted",
            refused(ModelLoader.Refusal.VERIFICATION_REFUSED, leftOnDisk = false),
            ErrorMapping.tampered(modelId, leftOnDisk = false),
        )
    }

    @Test
    fun `a model that failed verification and is still on disk is reported as not deleted for the requested id`() {
        assertArm(
            "VERIFICATION_REFUSED with the model left on disk",
            refused(ModelLoader.Refusal.VERIFICATION_REFUSED, leftOnDisk = true),
            ErrorMapping.tampered(modelId, leftOnDisk = true),
        )
    }

    @Test
    fun `an unusable engine is reported as a decode failure`() {
        assertArm("ENGINE_UNUSABLE", refused(ModelLoader.Refusal.ENGINE_UNUSABLE), ErrorMapping.decodeFailed())
    }
}
