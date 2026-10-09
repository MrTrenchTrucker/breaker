package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.stt.ondevice.SherpaModel
import dev.breaker.dictation.stt.ondevice.SherpaOnnxRecognizerFactory
import dev.breaker.dictation.stt.ondevice.SherpaTranscriptionException
import dev.breaker.dictation.stt.ondevice.UnavailableRecognizerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins what each swap point holds today: a placeholder that fails or does nothing in plain sight and
 * never reports a success, so a swap is one change and a forgotten one cannot look like a working part.
 */
internal class SwapsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the recognizer factory is the real one and is not the refusing placeholder`() {
        assertTrue("app: the swap point holds the real factory", RECOGNIZER_FACTORY is SherpaOnnxRecognizerFactory)
        assertNotSame("app: the swap point must not hold the refusing placeholder", UnavailableRecognizerFactory, RECOGNIZER_FACTORY)
    }

    @Test
    fun `the real factory refuses a model with no streaming profile with its own fixed sentence`() {
        val thrown = assertThrows("app: the real factory must refuse an unknown model", SherpaTranscriptionException::class.java) {
            RECOGNIZER_FACTORY.create(SherpaModel("no-such-model", tmp.newFolder(), "digest"))
        }
        assertEquals("app: the refusal is the engine adapter's", "no streaming profile for this model", thrown.message)
    }

    @Test
    fun `the real factory refuses an empty model folder before it touches the engine`() {
        val thrown = assertThrows("app: the real factory must refuse a model without files", SherpaTranscriptionException::class.java) {
            RECOGNIZER_FACTORY.create(SherpaModel("small", tmp.newFolder(), "digest"))
        }
        assertEquals("app: the refusal says the files are missing", "the model files are missing or empty", thrown.message)
    }

    @Test
    fun `the text committer fails every commit with its sentence`() {
        val result = appTextCommitter().commit(CommitRequest("hello"))
        assertEquals("app: the placeholder committer must fail", CommitOutcome.FAILED, result.outcome)
        assertEquals("app: and say so", COMMIT_UNAVAILABLE_DETAIL, result.detail)
    }

    @Test
    fun `the microphone cannot be opened and reads nothing`() {
        val mic = appMicSource()
        val thrown = assertThrows("app: opening the placeholder must fail", MicSourceException::class.java) { mic.open() }
        assertEquals("app: and say so", MIC_UNAVAILABLE_MESSAGE, thrown.message)
        assertEquals("app: a read reports the error code", -1, mic.read(ShortArray(4), 0, 4))
    }

    @Test
    fun `the gesture never triggers`() {
        val gesture = appGesture()
        assertTrue("app: the placeholder gesture is the no-gesture one", gesture is NoGesture)
        var triggers = 0
        gesture.start { triggers += 1 }
        gesture.stop()
        assertEquals("app: the placeholder must not trigger a dictation", 0, triggers)
    }

    @Test
    fun `the onboarding is never due`() {
        val onboarding = appOnboarding()
        assertTrue("app: the placeholder onboarding is the never-due one", onboarding is NoOnboarding)
        assertEquals("app: the placeholder must not claim a first run", false, onboarding.isDue())
    }

    @Test
    fun `the accessibility component is the one full string`() {
        assertEquals(
            "app: the component name the onboarding reads",
            "dev.breaker.dictation/dev.breaker.dictation.commit.accessibility.adapter.BreakerAccessibilityService",
            ACCESSIBILITY_SERVICE_COMPONENT,
        )
        val parts = ACCESSIBILITY_SERVICE_COMPONENT.split("/")
        assertEquals("app: package, a slash, class", 2, parts.size)
        assertTrue("app: the class name is fully qualified", parts[1].startsWith(parts[0] + "."))
    }
}
