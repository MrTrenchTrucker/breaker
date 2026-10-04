package dev.breaker.dictation.audio

/**
 * Timings shared by the mic-indicator listener throw tests, kept out of
 * both split halves so neither file carries the other's copy.
 *
 * Separate from the race test's own support on purpose: the race split
 * needs a 150ms budget for a join that is supposed to win, while
 * [JOIN_TIMEOUT_MS] here is a stuck-thread recovery bound a test has to
 * out-wait, and the two are not the same mechanism. Merging them would
 * make one number serve both, which is a behaviour change dressed as a
 * move.
 */
internal object IndicatorTestSupport {

    /**
     * Long enough that the re-entered stop() has to wait to be caught, short
     * enough that a test which stalls on it says so quickly.
     *
     * The unfixed code spends exactly this on the inner stop, which is what
     * the elapsed-time assertion is calibrated against.
     */
    const val JOIN_TIMEOUT_MS = 2_000L

    /**
     * The join bound for the test that needs a thread to STILL be running
     * when the join gives up.
     *
     * Not [JOIN_TIMEOUT_MS]: this test's whole claim is about a dispatcher
     * that outlives its join, so it parks that dispatcher and then pays this
     * to find out. Two seconds of dead wait would prove the same thing four
     * times as slowly, and a test that spends two seconds proving a
     * teardown is stuck is a test nobody runs twice.
     */
    const val STUCK_JOIN_TIMEOUT_MS = 300L
}
