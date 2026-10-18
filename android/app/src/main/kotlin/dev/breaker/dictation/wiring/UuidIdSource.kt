package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.IdSource
import java.util.UUID

/** An [IdSource] that mints a random UUID string for every new record. */
class UuidIdSource : IdSource {
    override fun newId(): String = UUID.randomUUID().toString()
}
