package dev.breaker.dictation.stt.ondevice

/**
 * The keyword lines for the two spoken phrases, as constants.
 *
 * The tokens are the ones the keyword model's own tokeniser gives for the phrase
 * (wake: "breaker breaker"; send: "and I'm gone", with and without the
 * apostrophe, because both spellings are heard). They are the same for every user.
 * They are pinned only against a made-up tokens fixture in the tests: the real
 * tokens file is not in this repository, so a match with the real model is not
 * checked here.
 *
 * No line carries a boost or a threshold, so the engine's own defaults apply;
 * whether those defaults work for these phrases is not measured.
 */
object FixedPhrases {
    /** The word marker of the tokeniser, U+2581, written here as the literal character. */
    private const val MARK = "▁"

    /** The wake phrase, "breaker breaker". */
    val WAKE: KeywordLine = KeywordLine(
        listOf("${MARK}B", "RE", "A", "K", "ER", "${MARK}B", "RE", "A", "K", "ER"),
    )

    /** The send phrase, "and I'm gone", with the apostrophe as its own token. */
    val SEND: KeywordLine = KeywordLine(
        listOf("${MARK}AND", "${MARK}I", "'", "M", "${MARK}GO", "NE"),
    )

    /** The send phrase spelled without the apostrophe. */
    val SEND_NO_APOSTROPHE: KeywordLine = KeywordLine(
        listOf("${MARK}AND", "${MARK}I", "M", "${MARK}GO", "NE"),
    )

    /** Every fixed line, in this order: wake, send, send without the apostrophe. */
    fun all(): List<KeywordLine> = listOf(WAKE, SEND, SEND_NO_APOSTROPHE)
}
