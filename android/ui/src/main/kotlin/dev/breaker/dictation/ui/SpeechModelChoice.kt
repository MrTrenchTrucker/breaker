package dev.breaker.dictation.ui

/**
 * One speech model, as the app hands it in: the offered-model row the app passes in to the first-run step and
 * to the settings section. It is not a settings type; this module does not depend on the settings module, and
 * the app builds each row from the registry.
 *
 * [id] is the model's registry id, [label] the short name it is offered under, [rating] its count of
 * trucks from 0 to 3, and [sizeMb] its download size in decimal megabytes. Only offered models are
 * passed, so the label and the rating are always present.
 */
data class SpeechModelChoice(val id: String, val label: String, val rating: Int, val sizeMb: Int)
