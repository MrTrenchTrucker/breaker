package dev.breaker.dictation.ui.screen.onboarding

/*
 * One speech model as the first-run step offers it: plain values, read from the settings catalog by the app.
 *
 * [label] is the short name the model is offered under and [rating] its count of trucks from 0 to 3; both are
 * null for a model that is not offered with a name. [sizeMb] is the download size in decimal megabytes and
 * [ready] says whether the model is on the phone at this drawing. No registry type is named here.
 */

/** A speech model the first-run step offers, as the step draws it. */
internal data class FirstRunModel(
    val id: String,
    val label: String?,
    val rating: Int?,
    val sizeMb: Int,
    val ready: Boolean,
)
