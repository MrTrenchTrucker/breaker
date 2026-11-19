package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.controls.ControlTexts
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.TextAlign
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.model.ModelRatingText
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * The speech model section of the settings screen, drawn from one row per offered model.
 *
 * Each model is a block of its own: its name with its trucks and its download size, then the controls its
 * state allows. Download shows when the model is not on the phone. Delete and Use this one show when it is on
 * the phone and is not the model in use. The model in use shows the READY word, the existing word for a ready
 * model, and it has no Delete. The progress sentence is drawn only under the model that is downloading.
 *
 * Every control reports a Setup intent whose action is one of ModelActions and whose id is the model's id. The
 * host answers those intents; this file only describes them. The section is drawn only when the host hands in
 * rows, so with no rows the model row keeps its one Download button.
 */

/** The stable id of one control, or of one block, of the model [id]: settings.model.<id>.<control>. */
internal fun modelControlId(id: String, control: String): String = "settings.model.$id.$control"

/** The section: the existing heading for the speech model, then one block per model in the order the host gives. */
internal fun modelRowsBlock(rows: List<ModelRowItem>): Box = Box(
    id = SettingsIds.MODEL_ROW,
    background = PaletteSlot.BACKGROUND,
    gap = Space.S,
    children = buildList<Node> {
        add(modelWordsLabel(SettingsIds.MODEL_LABEL, SetupTexts.HEADING_MODEL, PaletteSlot.TEXT))
        rows.forEach { add(modelItemBlock(it)) }
    },
)

/** One model: its name line, its trucks, its size, its controls, and its progress line when it is downloading. */
private fun modelItemBlock(item: ModelRowItem): Box = Box(
    id = modelControlId(item.id, "block"),
    background = PaletteSlot.BACKGROUND,
    gap = Space.XS,
    children = buildList<Node> {
        add(modelNameRow(item))
        ModelRatingText.trucks(item.rating)?.let { add(modelWordsLabel(modelControlId(item.id, "trucks"), it, PaletteSlot.TEXT)) }
        add(modelWordsLabel(modelControlId(item.id, "size"), ModelRatingText.sizeText(item.sizeMb), PaletteSlot.TEXT_MUTED))
        if (!item.installed) add(modelActionButton(item, "download", SettingsTexts.MODEL_DOWNLOAD, ModelActions.MODEL_DOWNLOAD, Emphasis.PRIMARY))
        if (item.installed && !item.inUse) add(modelActionButton(item, "delete", SettingsTexts.MODEL_DELETE, ModelActions.MODEL_DELETE, Emphasis.DESTRUCTIVE))
        if (item.installed && !item.inUse) add(modelActionButton(item, "use", SettingsTexts.MODEL_USE, ModelActions.MODEL_USE, Emphasis.PRIMARY))
        item.line?.let { add(modelWordsLabel(modelControlId(item.id, "line"), it, PaletteSlot.TEXT_MUTED)) }
    },
)

/** The model's name as a screen reader says it, `Good, 1 of 3`; the READY word at the right edge when it is in use and on the phone. */
private fun modelNameRow(item: ModelRowItem): Box = Box(
    id = modelControlId(item.id, "name"),
    background = PaletteSlot.BACKGROUND,
    horizontal = true,
    spread = true,
    children = buildList<Node> {
        add(modelWordsLabel(modelControlId(item.id, "label"), ModelRatingText.spoken(item.label, item.rating) ?: item.label, PaletteSlot.TEXT))
        if (item.inUse && item.installed) add(modelInUseBadge(item.id))
    },
)

/** The READY word in capitals on a primary fill: the in-use marker of a model that is on the phone. */
private fun modelInUseBadge(id: String): Box = Box(
    id = modelControlId(id, "ready"),
    background = PaletteSlot.PRIMARY,
    pad = Space.XS,
    children = listOf(
        Label(
            id = modelControlId(id, "ready.label"),
            text = ControlTexts.BADGE_READY,
            role = TypeRole.BODY,
            color = PaletteSlot.ON,
            align = TextAlign.CENTER,
            caps = true,
        ),
    ),
)

/** One control of a model row: the same raised kit as the existing Download, reporting the prefixed action and the model's id. */
private fun modelActionButton(item: ModelRowItem, control: String, text: String, action: String, emphasis: Emphasis): Action = Action(
    id = modelControlId(item.id, control),
    text = text,
    emphasis = emphasis,
    intent = ScreenIntent.Setup(action, item.id),
    kit = KitLook.RAISED,
)

private fun modelWordsLabel(id: String, text: String, color: PaletteSlot): Label =
    Label(id = id, text = text, role = TypeRole.BODY, color = color)
