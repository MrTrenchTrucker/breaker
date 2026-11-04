package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.SignInLinkRefusal
import dev.breaker.dictation.core.model.SignInLinkResult
import dev.breaker.dictation.core.model.buildSignInLink
import dev.breaker.dictation.core.port.DefaultHandoffReceiver
import dev.breaker.dictation.core.port.HandoffReceipt
import dev.breaker.dictation.core.port.HandoffReceiver
import dev.breaker.dictation.core.usecase.HandoffOutcome
import dev.breaker.dictation.core.usecase.SignInHolder

/**
 * Connects the start of a sign-in and the website's return to the one attempt the holder keeps.
 * Its start makes the link for a server address; its return hands a code to the receiver.
 * Nothing here logs, stores or prints a link, a code, a key or a secret.
 */
class SignInStartWiring(private val holder: SignInHolder, private val receiver: HandoffReceiver = DefaultHandoffReceiver) {

    /**
     * Start one sign-in for [serverUrl]. A blank address gives [SignInStartOutcome.NoServer]; a
     * link that can be made keeps the attempt and gives [SignInStartOutcome.Open]; any other refused
     * address drops the attempt and gives [SignInStartOutcome.BadAddress]. Every path that does not
     * open a link leaves the holder empty.
     */
    fun start(serverUrl: String): SignInStartOutcome {
        if (serverUrl.trim().isEmpty()) {
            holder.abandon()
            return SignInStartOutcome.NoServer
        }
        val attempt = holder.begin()
        return when (val built = buildSignInLink(serverUrl, attempt)) {
            is SignInLinkResult.Link -> SignInStartOutcome.Open(built.url)
            is SignInLinkResult.Refused -> {
                holder.abandon()
                if (built.reason == SignInLinkRefusal.NO_SERVER) SignInStartOutcome.NoServer else SignInStartOutcome.BadAddress
            }
        }
    }

    /**
     * Read the return [link] and hand the code to the receiver when a sign-in waits. A receiver that
     * throws is not caught here: the exception leaves this call and the holder has wiped the attempt.
     */
    fun returned(link: String?): SignInReturnState {
        return when (val outcome = holder.receive(link, receiver)) {
            HandoffOutcome.Ignored -> SignInReturnState.IGNORED
            HandoffOutcome.NoAttempt -> SignInReturnState.NO_ATTEMPT
            is HandoffOutcome.Handed -> when (outcome.receipt) {
                HandoffReceipt.TAKEN -> SignInReturnState.TAKEN
                HandoffReceipt.NOT_FINISHED -> SignInReturnState.NOT_FINISHED
            }
        }
    }

    /** Drop the waiting attempt, for example when the browser could not be opened. */
    fun cancel() {
        holder.abandon()
    }
}

/** What a start gave: the link to open, or the one reason no link was made. */
sealed interface SignInStartOutcome {
    /** The link to open in the browser. Its toString never prints the link. */
    class Open(val url: String) : SignInStartOutcome {
        override fun toString(): String = "SignInStartOutcome.Open(redacted)"
    }

    /** No server address was set. */
    data object NoServer : SignInStartOutcome

    /** The server address cannot carry a sign-in link. */
    data object BadAddress : SignInStartOutcome
}

/** What a return did with the waiting sign-in, as the start screen needs it. */
enum class SignInReturnState { IGNORED, NO_ATTEMPT, NOT_FINISHED, TAKEN }
