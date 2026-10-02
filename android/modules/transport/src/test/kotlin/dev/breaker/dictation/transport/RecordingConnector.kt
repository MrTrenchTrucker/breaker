package dev.breaker.dictation.transport

import dev.breaker.dictation.core.port.Clock
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The tail every failure message in this module's tests ends with.
 *
 * A failing assertion has to say which module's promise broke, and the card is
 * where the promise is written down: an invariant named here is one a reader can
 * go and check the code against, rather than one they have to infer from the
 * shape of a test.
 */
internal const val CARD = "- android/modules/transport (card AGENTS.md, Invariants)"

/** Builds the failure message the card says it should carry. */
internal fun cardFailure(message: String): String = "$message $CARD"

/**
 * A TCP connector the test drives: it answers what it is scripted to answer,
 * and remembers every target it was asked about.
 *
 * The recording is the point. An answer alone cannot tell a probe that dialled
 * the configured server from one that dialled something else on the way, and
 * "never contacts any host other than the configured Local Server" is the
 * promise this module is judged on. So the target is written down - address,
 * port and the timeout offered - rather than being inferred from the boolean
 * that came back.
 *
 * The scripted answers are consumed one per call and the last one repeats, so
 * a test that asks twice with a single scripted answer says what it means.
 *
 * Outcomes can be scripted by name rather than as bare booleans because a bare
 * false does not say WHY a dial failed, and the reason is what a test about a
 * refused connect has to be able to state. The seam collapses them: the
 * connector reports only whether the connect was accepted, so a test scripts
 * the cause it means and the double reports the same boolean production code
 * sees for every one of them.
 *
 * The recording is safe to write from several threads at once: the concurrency test
 * drives one connector from eight.
 */
internal class RecordingConnector(
    /** Answers handed out in order; the final one answers every call after it. */
    script: List<Any> = listOf(ProbeOutcome.CONNECTED),
    /**
     * Runs on the worker thread with the attempt index, before the answer.
     *
     * A scripted answer alone is instantaneous, so nothing in the script says
     * that a dial took time. A test about a budget that SHRINKS across attempts
     * needs an attempt that really spends some of it, and that is what this
     * hook is for: it is the only place a test can make a dial cost wall-clock
     * time without a real socket.
     */
    private val beforeAnswer: (Int) -> Unit = {},
) : TcpConnector {

    /** One dial, as the connector was asked to make it. */
    data class Attempt(val address: InetAddress, val port: Int, val timeoutMillis: Int) {
        /** The name this address was resolved from. */
        val host: String get() = address.hostName
    }

    private val scripted: List<ProbeOutcome> = script.map { answer ->
        when (answer) {
            is ProbeOutcome -> answer
            is Boolean -> if (answer) ProbeOutcome.CONNECTED else ProbeOutcome.REFUSED
            else -> throw IllegalArgumentException("script an outcome or a boolean, not ${answer::class.simpleName}")
        }
    }

    private val attempts = CopyOnWriteArrayList<Attempt>()

    override fun connect(address: InetAddress, port: Int, timeoutMillis: Int): Boolean {
        val index = attempts.size
        attempts.add(Attempt(address, port, timeoutMillis))
        beforeAnswer(index)
        return scripted[minOf(index, scripted.size - 1)] == ProbeOutcome.CONNECTED
    }

    /** The scripted outcome behind each recorded dial, in order. */
    val outcomes: List<ProbeOutcome>
        get() = attempts.indices.map { scripted[minOf(it, scripted.size - 1)] }

    /** How many times a connect was attempted. */
    val callCount: Int get() = attempts.size

    /** Every dial, in the order they were made. */
    val allAttempts: List<Attempt> get() = attempts.toList()

    /** The host of every dial, in order, duplicates kept. */
    val hosts: List<String> get() = attempts.map { it.host }

    /** The port of every dial, in order. */
    val ports: List<Int> get() = attempts.map { it.port }

    /** The connect timeout offered on every dial, in order. */
    val timeouts: List<Int> get() = attempts.map { it.timeoutMillis }
}

/**
 * A clock the test moves by hand.
 *
 * The 30 s cache window is the reason this exists: waiting it out in real time
 * would make the cache tests take half a minute each and prove nothing that a
 * number cannot. Advancing the instant is how a test says "30 001 ms later"
 * without sleeping.
 *
 * Deliberately not @Volatile. The instant is only ever moved by the test thread,
 * after the workers under test have been joined, and joining happens-before
 * carries the write to them. Anything else would be a claim about the probe's
 * own visibility that this double is not in a position to make.
 */
class FakeClock(var instant: Long) : Clock {
    override fun nowEpochMillis(): Long = instant
}

/**
 * A resolver that answers without asking anyone.
 *
 * A real lookup would make these tests depend on a name server and on a
 * `.local` name resolving at all, so every host is answered with a loopback
 * address carrying the requested name. Nothing is dialled here - the address is
 * only a label the connector records - so the loopback is a stand-in for "some
 * address this host resolved to", and never a route to anywhere.
 *
 * The hosts asked about are recorded, because a name resolution is as much a
 * contact with the outside world as a dial is: a probe that quietly looked up a
 * second name has left the configured server even if it never opened a socket
 * to it.
 */
internal class FakeHostResolver(
    /**
     * Overridable so a test can script a resolution that finds nothing, and so
     * a test can script a host that resolves to SEVERAL addresses.
     *
     * A list rather than a single address because one host resolving to more
     * than one address is the ordinary case, not an exotic one, and it is the
     * case that exercises a probe's whole per-address loop. A double that
     * always answers with exactly one address makes that loop untestable: a
     * probe that gives its second address a fresh full connect budget behaves
     * identically to one that correctly hands it what is left of a shared
     * deadline, because there is never a second address to tell them apart.
     */
    private val addressesFor: (String) -> List<InetAddress> = { host -> listOf(loopbackFor(host)) },
) : HostResolver {

    private val asked = CopyOnWriteArrayList<String>()

    override fun resolve(host: String): List<InetAddress> {
        asked.add(host)
        return addressesFor(host)
    }

    /** Every host name that was looked up, in order, duplicates kept. */
    val hostsAskedFor: List<String> get() = asked.toList()

    companion object {
        /**
         * An address that says which host it came from, without a lookup.
         *
         * [InetAddress.getByName] would resolve the name for real; building the
         * address from bytes keeps the name as the host and the bytes as a
         * placeholder, so a test that asserts on which host was dialled is not
         * really asserting on whether this machine has a resolver.
         */
        fun loopbackFor(host: String): InetAddress =
            InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1))
    }
}
