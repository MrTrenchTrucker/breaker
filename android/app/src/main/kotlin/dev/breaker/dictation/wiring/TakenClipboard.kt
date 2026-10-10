package dev.breaker.dictation.wiring

/** The clipboard as the coordinator sees it: one call, copying text. */
interface TakenClipboard {
    /** Copies [text] to the system clipboard; called on the main thread only. */
    fun copy(text: String)
}
