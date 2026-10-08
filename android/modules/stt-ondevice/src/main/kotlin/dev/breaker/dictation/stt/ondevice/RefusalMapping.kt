package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttResult

    internal fun mapRefusal(modelId: String, result: ModelLoader.LoadResult.Refused): SttResult.Failure {
        return when (result.refusal) {
            ModelLoader.Refusal.UNKNOWN_MODEL -> ErrorMapping.unknownModel(modelId)
            ModelLoader.Refusal.WRONG_FAMILY -> ErrorMapping.wrongFamily(modelId, result.family?.name ?: "unknown")
            ModelLoader.Refusal.NOT_INSTALLED -> ErrorMapping.noModelInstalled(modelId)
            ModelLoader.Refusal.CHECKSUMS_UNREADABLE -> ErrorMapping.checksumsUnreadable(modelId)
            ModelLoader.Refusal.VERIFICATION_REFUSED -> ErrorMapping.tampered(modelId, result.leftOnDisk)
            ModelLoader.Refusal.ENGINE_UNUSABLE -> ErrorMapping.decodeFailed()
        }
    }
