package dev.breaker.dictation.wiring

/**
 * The two fixed refusal sentences the app can compare a finish against.
 *
 * They must stay equal, character for character, to the stt-server FailureSentences of the same names:
 * KEY_REFUSED = "The server refused the key." and NO_KEY = "The server key is not set.". These are local
 * constants because FailureSentences is internal to the stt-server module and cannot be imported here; a test
 * pins the text. The comparison against these two strings never reaches the engine's object.
 */
internal object SessionRefusalSentences {
    const val KEY_REFUSED_TEXT = "The server refused the key."
    const val NO_KEY_TEXT = "The server key is not set."
}
