package dev.breaker.dictation.ui.screen.model

import dev.breaker.dictation.ui.screen.firstrun.FirstRunWords

/*
 * The words a speech model's rating and size carry on the first-run and settings screens.
 *
 * A rating is drawn as trucks. A star system is not built. A rating outside 0..3, or no
 * rating at all, gives null: nothing is drawn and no count is spoken, so the screen never
 * shows a count the registry does not hold.
 */
internal object ModelRatingText {
    /** The truck emoji, U+1F69A, as its two UTF-16 units, so the source stays plain ASCII. */
    private const val TRUCK = "\uD83D\uDE9A"

    /** The highest rating, in trucks. */
    const val MAX_RATING = 3

    /** One truck per rating point: empty for 0, three trucks for 3, null when [rating] is null or outside 0..3. */
    fun trucks(rating: Int?): String? {
        if (rating == null || rating < 0 || rating > MAX_RATING) return null
        return TRUCK.repeat(rating)
    }

    /** The spoken rating, `label, rating of 3`, or null when [rating] is null or outside 0..3. */
    fun spoken(label: String, rating: Int?): String? {
        if (rating == null || rating < 0 || rating > MAX_RATING) return null
        return "$label, $rating of $MAX_RATING"
    }

    /** The download size sentence, the existing model step sentence with the size in decimal megabytes. */
    fun sizeText(sizeMb: Int): String = FirstRunWords.modelSource(sizeMb)
}
