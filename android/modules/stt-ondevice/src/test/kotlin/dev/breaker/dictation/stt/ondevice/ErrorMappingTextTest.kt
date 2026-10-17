package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact failure each [ErrorMapping] function returns: the error kind and
 * the whole user-facing sentence, compared as one value. The sentences are
 * literals here on purpose, so a reworded or reversed sentence fails a test
 * that names the function. Nothing in this class waits on anything.
 */
class ErrorMappingTextTest {

    @Test
    fun `noModelInstalled returns exactly its sentence`() {
        assertEquals(
            "noModelInstalled must return its exact failure",
            SttResult.Failure(SttError.LOCAL_MODEL_MISSING, "No model installed for 'tiny'."),
            ErrorMapping.noModelInstalled("tiny"),
        )
    }

    @Test
    fun `unknownModel returns exactly its sentence`() {
        assertEquals(
            "unknownModel must return its exact failure",
            SttResult.Failure(
                SttError.LOCAL_MODEL_MISSING,
                "No model installed: 'tiny' is not a model this app knows.",
            ),
            ErrorMapping.unknownModel("tiny"),
        )
    }

    @Test
    fun `tampered with the model deleted returns exactly its sentence`() {
        assertEquals(
            "tampered (deleted form) must return its exact failure",
            SttResult.Failure(
                SttError.LOCAL_MODEL_MISSING,
                "The model for 'tiny' did not match its published checksum and was deleted. Download it again.",
            ),
            ErrorMapping.tampered("tiny"),
        )
    }

    @Test
    fun `tampered with the delete failed returns exactly its sentence`() {
        assertEquals(
            "tampered (left on disk form) must return its exact failure",
            SttResult.Failure(
                SttError.LOCAL_MODEL_MISSING,
                "The model for 'tiny' did not match its published checksum and could not be deleted. " +
                    "It will not be used.",
            ),
            ErrorMapping.tampered("tiny", true),
        )
    }

    @Test
    fun `wrongFamily returns exactly its sentence`() {
        assertEquals(
            "wrongFamily must return its exact failure",
            SttResult.Failure(
                SttError.LOCAL_MODEL_MISSING,
                "'tiny' is a WHISPER model; this app can only run the streaming Zipformer models today.",
            ),
            ErrorMapping.wrongFamily("tiny", "WHISPER"),
        )
    }

    @Test
    fun `checksumsUnreadable returns exactly its sentence`() {
        assertEquals(
            "checksumsUnreadable must return its exact failure",
            SttResult.Failure(
                SttError.LOCAL_MODEL_MISSING,
                "Upstream's checksum list could not be read, so 'tiny' could not be verified.",
            ),
            ErrorMapping.checksumsUnreadable("tiny"),
        )
    }

    @Test
    fun `audioWrongRate returns exactly its sentence`() {
        assertEquals(
            "audioWrongRate must return its exact failure",
            SttResult.Failure(
                SttError.OTHER,
                "Audio at 44100 Hz cannot be transcribed locally; the on-device engine reads 16000 Hz mono.",
            ),
            ErrorMapping.audioWrongRate(44100),
        )
    }

    @Test
    fun `decodeFailed returns exactly its sentence`() {
        assertEquals(
            "decodeFailed must return its exact failure",
            SttResult.Failure(SttError.OTHER, "The on-device engine could not transcribe the audio."),
            ErrorMapping.decodeFailed(),
        )
    }

    @Test
    fun `engineClosed returns exactly its sentence`() {
        assertEquals(
            "engineClosed must return its exact failure",
            SttResult.Failure(SttError.OTHER, "The on-device engine is shutting down."),
            ErrorMapping.engineClosed(),
        )
    }

    @Test
    fun `reentrantDecode returns exactly its sentence`() {
        assertEquals(
            "reentrantDecode must return its exact failure",
            SttResult.Failure(
                SttError.OTHER,
                "The on-device engine is already transcribing on this thread; " +
                    "the call was refused rather than left to wait.",
            ),
            ErrorMapping.reentrantDecode(),
        )
    }

    @Test
    fun `modelUnreadable returns exactly its sentence`() {
        assertEquals(
            "modelUnreadable must return its exact failure",
            SttResult.Failure(SttError.OTHER, "The on-device model could not be read."),
            ErrorMapping.modelUnreadable(),
        )
    }
}
