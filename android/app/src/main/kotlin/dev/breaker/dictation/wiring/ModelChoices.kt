package dev.breaker.dictation.wiring

import dev.breaker.dictation.settings.ModelCatalog
import dev.breaker.dictation.ui.SpeechModelChoice

/** The offered speech models the screens list, each with its label and rating. A model with neither is left out. */
fun offeredSpeechModels(): List<SpeechModelChoice> =
    ModelCatalog.offered().mapNotNull { model ->
        val label = model.label
        val rating = model.rating
        if (label == null || rating == null) null else SpeechModelChoice(model.id, label, rating, ModelSentences.mibToMb(model.sizeMb))
    }
