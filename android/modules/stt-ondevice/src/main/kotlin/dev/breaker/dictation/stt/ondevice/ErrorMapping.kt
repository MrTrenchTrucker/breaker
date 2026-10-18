package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttResult

/**
 * Maps every failure the on-device engine can produce to a [SttResult.Failure]
 * with a short, safe, user-facing detail string.
 *
 * This engine has no server path at all, so it never returns
 * [SttError.SERVER_UNREACHABLE] or [SttError.TIMEOUT]. A network-flavoured error
 * would send the user to fix the wrong thing: there is nothing to fix on the
 * network side when the transcription never leaves the phone.
 *
 * Every detail is a short, safe, user-facing sentence. No audio content,
 * transcript text, digest or file path is ever placed in a detail.
 */
object ErrorMapping {

    /**
     * The model [modelId] is not installed on this device.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun noModelInstalled(modelId: String): SttResult.Failure =
        SttResult.Failure(SttError.LOCAL_MODEL_MISSING, "No model installed for '$modelId'.")

    /**
     * The model [modelId] is not a model this app knows.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun unknownModel(modelId: String): SttResult.Failure =
        SttResult.Failure(
            SttError.LOCAL_MODEL_MISSING,
            "No model installed: '$modelId' is not a model this app knows.",
        )

    /**
     * The model for [modelId] did not match its published checksum.
     *
     * With [leftOnDisk] false the model was deleted and the detail says so. With
     * [leftOnDisk] true the delete failed and the detail says that instead, so
     * the user is never told a file is gone while it is still on the phone. The
     * flag is [ModelLoader.LoadResult.Refused.leftOnDisk].
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun tampered(modelId: String, leftOnDisk: Boolean = false): SttResult.Failure =
        if (leftOnDisk) {
            SttResult.Failure(
                SttError.LOCAL_MODEL_MISSING,
                "The model for '$modelId' did not match its published checksum and could not be deleted. " +
                    "It will not be used.",
            )
        } else {
            SttResult.Failure(
                SttError.LOCAL_MODEL_MISSING,
                "The model for '$modelId' did not match its published checksum and was deleted. Download it again.",
            )
        }

    /**
     * The model [modelId] is a [family] model; this app can only run the streaming Zipformer models today.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun wrongFamily(modelId: String, family: String): SttResult.Failure =
        SttResult.Failure(
            SttError.LOCAL_MODEL_MISSING,
            "'$modelId' is a $family model; this app can only run the streaming Zipformer models today.",
        )

    /**
     * Upstream's checksum list could not be read, so [modelId] could not be verified.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun checksumsUnreadable(modelId: String): SttResult.Failure =
        SttResult.Failure(
            SttError.LOCAL_MODEL_MISSING,
            "Upstream's checksum list could not be read, so '$modelId' could not be verified.",
        )

    /**
     * Audio at [rate] Hz cannot be transcribed locally; the on-device engine reads 16000 Hz mono.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun audioWrongRate(rate: Int): SttResult.Failure =
        SttResult.Failure(
            SttError.OTHER,
            "Audio at $rate Hz cannot be transcribed locally; the on-device engine reads 16000 Hz mono.",
        )

    /**
     * The on-device engine could not transcribe the audio.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun decodeFailed(): SttResult.Failure =
        SttResult.Failure(SttError.OTHER, "The on-device engine could not transcribe the audio.")

    /**
     * The on-device engine took too long to transcribe the audio.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun decodeTimedOut(): SttResult.Failure =
        SttResult.Failure(SttError.OTHER, "The on-device engine took too long to transcribe the audio.")

    /**
     * The on-device engine is still working on an earlier recording; the call was refused.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun decodeBusy(): SttResult.Failure =
        SttResult.Failure(
            SttError.OTHER,
            "The on-device engine is still working on an earlier recording; try again in a moment.",
        )

    /**
     * The on-device engine is shutting down.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun engineClosed(): SttResult.Failure =
        SttResult.Failure(SttError.OTHER, "The on-device engine is shutting down.")

    /**
     * The on-device engine is already transcribing on this thread; the call was refused rather than left to wait.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun reentrantDecode(): SttResult.Failure =
        SttResult.Failure(
            SttError.OTHER,
            "The on-device engine is already transcribing on this thread; the call was refused rather than left to wait.",
        )

    /**
     * The on-device model could not be read. Used when the model store fails
     * while the engine loads a model.
     *
     * The detail is a short, safe, user-facing sentence. No audio content,
     * transcript text, or file path is ever placed in a detail.
     */
    fun modelUnreadable(): SttResult.Failure =
        SttResult.Failure(SttError.OTHER, "The on-device model could not be read.")
}
