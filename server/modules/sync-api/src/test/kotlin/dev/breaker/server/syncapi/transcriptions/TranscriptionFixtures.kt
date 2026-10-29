package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TestDatabases
import java.time.Clock
import kotlinx.coroutines.runBlocking

/** Helpers for the transcription tests. Accounts are inserted with raw SQL, as the schema tests do. */
internal object TranscriptionFixtures {
    const val ENCRYPTED_TEXT: String = "ZW5jcnlwdGVkLXRleHQtZm9yLXRlc3Rz"

    /** A 36 character client id; the hexadecimal number [n] makes distinct ids that sort in the same order. */
    fun clientId(n: Int): String = "%08x-0000-4000-8000-000000000000".format(n)

    fun accountId(temp: TempDatabase, name: String): Long = runBlocking {
        temp.db.transaction { connection ->
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(mapOf("username" to name, "username_lower" to name)),
            )
            TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = '$name'").single()
        }
    }

    fun store(temp: TempDatabase, clock: Clock): TranscriptionStore = TranscriptionStore(temp.db, clock)

    fun push(
        store: TranscriptionStore,
        accountId: Long,
        clientId: String,
        keyVersion: Int = 0,
        encrypted: String = ENCRYPTED_TEXT,
    ): PushResult = runBlocking { store.push(accountId, clientId, keyVersion, encrypted) }

    fun list(store: TranscriptionStore, accountId: Long): List<StoredTranscription> =
        runBlocking { store.list(accountId) }

    fun rowCount(temp: TempDatabase): Long = runBlocking {
        temp.db.transaction { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions").single()
        }
    }
}
