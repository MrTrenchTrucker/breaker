package dev.breaker.dictation.stt.ondevice

import java.io.File

/**
 * One line of a keywords file for the keyword spotter: the encoded tokens of one
 * phrase, and optionally a boost and a trigger threshold.
 *
 * A null [boost] or [threshold] means the line carries no number and the engine
 * uses its own default. This type holds the values and checks nothing; the range
 * checks are in [KeywordsFileText]. Nothing in the app passes a number yet.
 *
 * @property tokens the encoded tokens, in order, one list entry per token.
 * @property boost the boosting score, written after a colon, or null.
 * @property threshold the trigger threshold, written after a hash sign, or null.
 */
class KeywordLine(
    val tokens: List<String>,
    val boost: Float? = null,
    val threshold: Float? = null,
)

/**
 * The seam that puts the text of a keywords file on disk and returns the file.
 *
 * This module only declares it: no implementation exists here, so nothing in
 * this module writes a file, and where the file lives is the implementer's choice.
 */
fun interface KeywordsFileWriter {
    /** Writes [text] as the keywords file and returns that file. */
    fun write(text: String): File
}
