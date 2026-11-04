package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.HandoffReturn
import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.model.parseHandoffReturn
import dev.breaker.dictation.core.port.HandoffReceipt
import dev.breaker.dictation.core.port.HandoffReceiver
import dev.breaker.dictation.core.port.SignInStarter

/** What the website's return did with the waiting sign-in attempt. */
sealed interface HandoffOutcome {
    /** The link was not the one return shape, so nothing was touched. */
    data object Ignored : HandoffOutcome

    /** A well formed code came back with no sign-in waiting, so the receiver was not called. */
    data object NoAttempt : HandoffOutcome

    /** The code went to the receiver together with the waiting attempt. */
    class Handed(val receipt: HandoffReceipt) : HandoffOutcome {
        /** Prints the receipt name only, never the code or any byte. */
        override fun toString(): String = "HandoffOutcome.Handed(${receipt.name})"
    }
}

/**
 * Holds the one sign-in attempt that waits for the website's return, in memory only.
 *
 * Only one attempt is held at a time. [begin] wipes and replaces an older attempt. The
 * attempt leaves the holder when it is handed to the receiver, and after that the holder
 * keeps no reference to it. The holder wipes an attempt only when it is replaced, when
 * it is abandoned, or when the receiver throws; after a normal return the receiver owns
 * the wipe.
 *
 * Nothing is logged or stored here.
 */
class SignInHolder(private val starter: SignInStarter) {
    private val lock = Any()
    private var attempt: SignInAttempt? = null

    /** Wipe and drop any older attempt, then make a new one, keep it and return it. */
    fun begin(): SignInAttempt = synchronized(lock) {
        abandonLocked()
        val fresh = starter.begin()
        attempt = fresh
        fresh
    }

    /** Wipe and drop the waiting attempt. Safe when there is none. */
    fun abandon() {
        synchronized(lock) { abandonLocked() }
    }

    /** True while an attempt waits for the website's return. */
    fun hasAttempt(): Boolean = synchronized(lock) { attempt != null }

    /**
     * Read [link] as the website's return. A link that is not the one return shape is
     * [HandoffOutcome.Ignored] and touches nothing. A code with no waiting attempt is
     * [HandoffOutcome.NoAttempt] and the receiver is not called. Otherwise the code and
     * the waiting attempt go to [receiver] exactly once. If the receiver throws, the
     * attempt is wiped and the same exception is thrown again.
     */
    fun receive(link: String?, receiver: HandoffReceiver): HandoffOutcome {
        val code = when (val parsed = parseHandoffReturn(link)) {
            is HandoffReturn.Code -> parsed.code
            HandoffReturn.Ignored -> return HandoffOutcome.Ignored
        }
        val waiting = takeAttempt() ?: return HandoffOutcome.NoAttempt
        val receipt = try {
            receiver.onHandoff(code, waiting)
        } catch (failure: Throwable) {
            waiting.wipe()
            throw failure
        }
        return HandoffOutcome.Handed(receipt)
    }

    /** Take the waiting attempt out of the holder, so the holder is empty afterwards. */
    private fun takeAttempt(): SignInAttempt? = synchronized(lock) { takeLocked() }

    /** Drop the waiting attempt without returning it. The caller holds [lock]. */
    private fun abandonLocked() {
        takeLocked()?.wipe()
    }

    /** Remove the waiting attempt and return it. The caller holds [lock]. */
    private fun takeLocked(): SignInAttempt? {
        val waiting = attempt
        attempt = null
        return waiting
    }
}
