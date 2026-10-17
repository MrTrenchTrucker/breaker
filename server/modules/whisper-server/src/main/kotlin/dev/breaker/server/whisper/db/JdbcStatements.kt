package dev.breaker.server.whisper.db

import java.sql.Connection

internal fun Connection.executeStatement(sql: String) {
    createStatement().use { it.execute(sql) }
}

// A failing ROLLBACK (for example when BEGIN itself failed and no transaction is
// open) must not replace the error that made us roll back: the caller rethrows
// the original and the rollback problem rides along as a suppressed exception.
internal fun Connection.rollbackAfter(failure: Throwable) {
    try {
        executeStatement("ROLLBACK")
    } catch (rollbackFailure: Exception) {
        failure.addSuppressed(rollbackFailure)
    }
}
