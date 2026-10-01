package dev.breaker.dictation.core.usecase

/**
 * The rule that keeps a phone transcript away from cloud formatting.
 *
 * On-device transcription is a boundary, not a preference. When a dictation ran
 * on the phone, its transcript text is never handed to a cloud formatter,
 * however the formatting setting stands. That setting is stored independently of
 * the engine choice, so a user who enabled formatting while on the server and
 * later moved to the phone for privacy is the ordinary case, not an edge case:
 * the old switch staying on must not keep sending their text.
 *
 * The question is answered here, once, so the rule is checked with plain unit
 * tests. The answer follows the path the dictation was actually routed down, so
 * it holds the same whether the user chose on-device mode or automatic routing
 * fell back to the phone.
 */
object LocalModeEgress {
    /**
     * The master switch for cloud formatting. A dictation that ran on the phone
     * never gets it, whatever the other preferences say.
     */
    fun mayUseCloud(onDevice: Boolean): Boolean = !onDevice

    /**
     * May the transcript text be sent to a cloud formatter? Turning formatting
     * on does not turn the network on: an on-device dictation wins.
     */
    fun mayCleanUpTranscript(onDevice: Boolean, formattingEnabled: Boolean): Boolean =
        mayUseCloud(onDevice) && formattingEnabled
}
