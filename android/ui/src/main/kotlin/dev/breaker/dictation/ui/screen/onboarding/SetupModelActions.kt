package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.firstrun.FirstRunWords
import dev.breaker.dictation.ui.theme.PaletteSlot

/** The id of the one line the model step shows when every offered model is already on the phone. */
private const val MODEL_READY_ID = "setup.step.model.ready"

/*
 * The speech-model buttons of the set-up screen, and the hand-over of a model download to the app.
 *
 * With no model offered the step draws its one button, exactly as before. With models offered it draws one
 * button per model not on the phone, in the order they are offered: the first keeps the step's own id and
 * position, and each button reports the model-download action with that model's id. When every offered model
 * is on the phone it draws the one done line instead. A blank id is refused, since there is no model to download.
 */

/**
 * The buttons of the model step. [firstId] is the id the first button keeps. With no model offered this is the
 * one existing button, with the download action and no id.
 */
internal fun modelStepButtons(state: SetupState, firstId: String): List<Node> {
    if (state.models.isEmpty()) {
        return listOf(
            Action(
                id = firstId,
                text = SetupTexts.BUTTON_DOWNLOAD_MODEL,
                emphasis = Emphasis.PRIMARY,
                intent = ScreenIntent.Setup(SetupActions.DOWNLOAD_MODEL),
            ),
        )
    }
    val toDownload = FirstRunWords.modelsToDownload(state.models)
    if (toDownload.isEmpty()) {
        return listOf(Label(MODEL_READY_ID, SetupTexts.STATUS_GRANTED, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
    }
    return toDownload.mapIndexed { index, model ->
        Action(
            id = if (index == 0) firstId else "$firstId.${model.id}",
            text = FirstRunWords.modelButtonText(model),
            emphasis = Emphasis.PRIMARY,
            intent = ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, model.id),
        )
    }
}

/**
 * Hands a model-download tap to the app through [download]. A blank id is refused and calls nothing. A call that
 * throws gives the download sentence, and its text is never shown. Returns the notice to draw, or null when the
 * app took the request.
 */
internal fun handOverModelDownload(id: String, download: (String) -> Unit): SetupNotice? {
    if (id.isBlank()) return SetupNotice.NOT_ACCEPTED
    return try {
        download(id)
        null
    } catch (failure: Exception) {
        SetupNotice.COULD_NOT_DOWNLOAD
    }
}
