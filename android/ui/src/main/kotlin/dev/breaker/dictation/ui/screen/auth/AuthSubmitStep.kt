package dev.breaker.dictation.ui.screen.auth

/**
 * Hands [typed] to [submit] and always runs [wipeBox] afterwards, also when [submit] throws.
 *
 * This function never reads, copies or changes [typed] itself: the handler zeroes the array it is
 * given, and its callback owns its own copy. The failure of [submit], if any, reaches the caller
 * unchanged after the box has been wiped.
 */
internal fun submitThenWipe(typed: CharArray, submit: (CharArray) -> Unit, wipeBox: () -> Unit) {
    try {
        submit(typed)
    } finally {
        wipeBox()
    }
}
