package dev.breaker.dictation.wiring

/**
 * Holds the value that [build] makes and makes a new one each time [again] is called.
 *
 * The first value is made when the holder is constructed, and [current] stays that value until [again]
 * runs. Nothing is cached: every call of [again] runs [build] once more and keeps the new value.
 *
 * @param build makes a new value on each call.
 */
internal class Rebuilt<T : Any>(private val build: () -> T) {

    /** The value made last: by the constructor, or by the latest call of [again]. */
    var current: T = build()
        private set

    /** Makes a new value, keeps it as [current] and returns it. */
    fun again(): T {
        val next: T = build()
        current = next
        return next
    }
}
