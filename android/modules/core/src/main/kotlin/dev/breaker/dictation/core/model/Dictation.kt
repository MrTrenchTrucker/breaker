package dev.breaker.dictation.core.model

/**
 * The states a dictation moves through.
 *
 * The happy path is `IDLE -> ARMED -> RECORDING -> TRANSCRIBING -> SENDING ->
 * IDLE`, which is the path the wake phrase, the recording, the transcription
 * and the text commit each drive. `ERROR` is reachable from the two states that
 * can fail (transcribing, sending) and always returns to `IDLE`.
 */
enum class DictationState { IDLE, ARMED, RECORDING, TRANSCRIBING, SENDING, ERROR }

/**
 * Where a dictation currently is and, while it is still relevant, what came
 * out of the last attempt.
 *
 * The class is immutable: every move returns a new session, so a caller that
 * holds a session cannot be surprised by a change it did not make. Illegal moves
 * are rejected with [IllegalStateException] rather than silently tolerated —
 * a dictation that jumps from idle straight to sending is a wiring bug, and the
 * domain says so instead of guessing.
 */
data class DictationSession(
    val state: DictationState = DictationState.IDLE,
    val lastError: SttError? = null,
    val lastTranscription: Transcription? = null,
) {
    /** True while the session is between idle and sending (i.e. it is busy). */
    val isBusy: Boolean
        get() = state != DictationState.IDLE && state != DictationState.ERROR

    /** Prints the state, the error and the id of the transcription it holds, never its text. */
    override fun toString(): String =
        "DictationSession(state=$state, lastError=$lastError, lastTranscriptionId=${lastTranscription?.id})"

    /**
     * Move to [next], or throw when that move is not one the state machine
     * allows. The message names both states and the legal moves so a failure
     * says what to fix.
     *
     * A move also drops what it makes stale, so a session built by moves never
     * describes a state it has left. (The constructor is permissive: a hand-built
     * session can carry any combination.) The failure of an attempt stays only on
     * [DictationState.ERROR]; the text of a finished transcription stays only
     * while it can still be sent or recovered, on [DictationState.SENDING] and
     * [DictationState.ERROR]. Everywhere else both are cleared.
     */
    fun transitionTo(next: DictationState): DictationSession {
        val allowed = LEGAL_TRANSITIONS[state].orEmpty()
        check(next in allowed) {
            val options = allowed.joinToString(", ") { it.name }.ifEmpty { "nothing" }
            "Illegal dictation transition ${state.name} -> ${next.name}; " +
                "legal moves from ${state.name}: $options"
        }
        return copy(
            state = next,
            lastError = if (next == DictationState.ERROR) lastError else null,
            lastTranscription = if (next == DictationState.SENDING || next == DictationState.ERROR) {
                lastTranscription
            } else {
                null
            },
        )
    }

    /** Wake the dictation: idle -> armed. */
    fun arm(): DictationSession = transitionTo(DictationState.ARMED)

    /** Start capturing audio: armed -> recording. */
    fun startRecording(): DictationSession = transitionTo(DictationState.RECORDING)

    /** Abandon the current attempt and go back to idle. */
    fun cancel(): DictationSession = transitionTo(DictationState.IDLE)

    /** Record a transcription failure on the session (transcribing -> error). */
    fun withError(error: SttError): DictationSession = transitionTo(DictationState.ERROR).copy(lastError = error)

    /** Record the text that is ready to be sent (transcribing -> sending). */
    fun withTranscription(transcription: Transcription): DictationSession =
        transitionTo(DictationState.SENDING).copy(lastTranscription = transcription)

    companion object {
        /**
         * The only moves the state machine allows. Anything absent here is
         * rejected by [transitionTo].
         */
        val LEGAL_TRANSITIONS: Map<DictationState, Set<DictationState>> = mapOf(
            DictationState.IDLE to setOf(DictationState.ARMED),
            DictationState.ARMED to setOf(DictationState.RECORDING, DictationState.IDLE),
            DictationState.RECORDING to setOf(DictationState.TRANSCRIBING, DictationState.IDLE),
            DictationState.TRANSCRIBING to setOf(DictationState.SENDING, DictationState.ERROR),
            DictationState.SENDING to setOf(DictationState.IDLE, DictationState.ERROR),
            DictationState.ERROR to setOf(DictationState.IDLE),
        )
    }
}

/** What one dictation attempt produced. */
sealed class DictationResult {
    /** The session as it stands after the attempt. */
    abstract val session: DictationSession

    /** The audio was transcribed, formatted, and is ready to be sent. */
    data class Success(
        override val session: DictationSession,
        val transcription: Transcription,
    ) : DictationResult() {
        /** Prints the session's state and the transcription's id and length, never its text. */
        override fun toString(): String =
            "Success(sessionState=${session.state}, transcriptionId=${transcription.id}, " +
                "${transcription.text.length} chars)"
    }

    /**
     * The attempt failed. [error] is the reason the domain reports; [detail] is
     * an optional short explanation for the user. A detail may never carry the
     * dictated text itself.
     */
    data class Failure(
        override val session: DictationSession,
        val error: SttError,
        val detail: String? = null,
    ) : DictationResult()
}
