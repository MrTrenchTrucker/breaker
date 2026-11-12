package dev.breaker.dictation.wiring

/**
 * The tile's take port with its spoken send. A spoken send runs [sendEntry], the tile's own send, on
 * main; the tile host gives that entry. Every take call goes through the phrase take port to [owned].
 */
internal fun tileSpokenTake(owned: TakePort, handler: PhraseSendHandler, sendEntry: () -> Unit): TakePort =
    PhraseTakePort(owned, handler, sendEntry)
