package dev.breaker.dictation.updater

import dev.breaker.dictation.core.port.Clock
import java.io.IOException

/** A ledger slot in memory. It records each call and fails a write when the test sets the failure flag. */
internal class FakeSlot(private val log: CallLog) : LedgerSlot {
    var values: LedgerValues = LedgerValues.EMPTY
    var storeFails: Boolean = false
    var applyFails: Boolean = false

    override fun read(): LedgerValues {
        log.add("slot.read")
        return values
    }

    override fun storeFinalStatus(status: String): Boolean {
        log.add("slot.storeFinalStatus:$status")
        if (storeFails) throw IOException("the test store cannot be written")
        val record = values.record ?: return false
        values = values.copy(record = record.copy(finalStatus = status))
        return true
    }

    override fun applyChange(removeRecord: Boolean, lastSeenCode: FieldEdit<Int>, lastOutcome: FieldEdit<String>) {
        log.add("slot.applyChange:$removeRecord")
        if (applyFails) throw IOException("the test store cannot be written")
        values = values.copy(
            record = if (removeRecord) null else values.record,
            lastSeenCode = lastSeenCode.applyTo(values.lastSeenCode),
            lastOutcome = lastOutcome.applyTo(values.lastOutcome),
        )
    }
}

/** The platform's session answers, which the test sets. A failure is thrown by both queries. */
internal class FakeSessions(private val log: CallLog) : SessionQuery {
    var known: Boolean = true
    var committed: Boolean = false
    var failure: RuntimeException? = null

    override fun exists(sessionId: Int): Boolean {
        log.add("session.exists:$sessionId")
        failure?.let { throw it }
        return known
    }

    override fun isCommitted(sessionId: Int): Boolean {
        log.add("session.committed:$sessionId")
        failure?.let { throw it }
        return committed
    }
}

/** An abandon that records each call and throws the exception the test sets. */
internal class FakeAbandon(private val log: CallLog) : SessionAbandon {
    var thrown: Exception? = null
    var calls: Int = 0

    override fun abandon(sessionId: Int) {
        calls += 1
        log.add("abandon:$sessionId")
        thrown?.let { throw it }
    }
}

/** A drop log that keeps each reported pair of session ids, in order. */
internal class FakeDrops : DropLog {
    val reported = mutableListOf<Pair<Int, Int?>>()

    override fun dropped(platformSessionId: Int, recordedSessionId: Int?) {
        reported.add(Pair(platformSessionId, recordedSessionId))
    }
}

/** A clock that fails on every read, so a test sees whether anything ran after the failure. */
internal class ThrowingClock(private val log: CallLog) : Clock {
    override fun nowEpochMillis(): Long {
        log.add("clock.throws")
        throw IllegalStateException("the test clock cannot be read")
    }
}

/** The ledger values of a test: the record, the last seen code and the last outcome; the three times stay unset. */
internal fun ledgerWith(record: LedgerRecord?, lastSeen: Int?, lastOutcome: String?): LedgerValues =
    LedgerValues(null, null, null, record, lastSeen, lastOutcome)

/** The time that lies [ageMillis] before the fixed clock of the tests. */
internal fun writtenAgo(ageMillis: Long): Long = NOW_MILLIS - ageMillis

/** The engine wired to fakes that share one call log. */
internal class StartRig {
    val log = CallLog()
    val clock = FakeClock(NOW_MILLIS, log)
    val slot = FakeSlot(log)
    val installed = FakeInstalled(log)
    val sessions = FakeSessions(log)
    val abandon = FakeAbandon(log)
    val drops = FakeDrops()
    val start: StartRun by lazy { StartRun(clock, slot, installed, sessions, abandon, drops) }
}

/** The checker and the engine wired to one call log, for the install gate tests. */
internal class GateRig {
    val fakes = CheckerRig()
    val slot = FakeSlot(fakes.log)
    val sessions = FakeSessions(fakes.log)
    val abandon = FakeAbandon(fakes.log)
    val drops = FakeDrops()
    val start: StartRun by lazy { StartRun(fakes.clock, slot, fakes.installed, sessions, abandon, drops) }
    val gate: InstallGate by lazy { InstallGate(fakes.checker, start) }

    /** Runs one check, so that the checker remembers the default release, and then clears the call log. */
    fun checkFirst() {
        fakes.checker.check(false)
        fakes.log.clear()
    }
}
