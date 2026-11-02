package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

internal class SyncRoutesPushResaveTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var alice: Long = 0L
    private var token: String = ""

    @Before
    fun openRoutes() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        token = SyncRouteTestSupport.sessionToken(tokenStore, alice)
    }

    private fun postSync(tokenStore: TokenStore, store: TranscriptionStore, token: String?, body: String) =
        SyncRouteTestSupport.postSync(tokenStore, store, token, body)

    @Test
    fun postSyncResaveFalseDefaultAnswers204() {
        val body = "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\"}"
        val reply = postSync(tokenStore, store, token, body)
        assertEquals("POST /v1/sync resave=false answers 204", HttpStatusCode.NoContent, reply.status)
    }

    @Test
    fun postSyncExplicitResaveFalseAnswers204() {
        val body = "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\",\"resave\":false}"
        val reply = postSync(tokenStore, store, token, body)
        assertEquals("POST /v1/sync explicit resave:false answers 204", HttpStatusCode.NoContent, reply.status)
    }

    @Test
    fun postSyncExplicitResaveTrueOnDeletedRowAnswers204() {
        // Push a row, delete it, then re-save with resave=true: the mark matters.
        val pushBody = "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\"}"
        assertEquals("POST stores", HttpStatusCode.NoContent, postSync(tokenStore, store, token, pushBody).status)

        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, CLIENT_ID)
        assertEquals("DELETE answers 204", HttpStatusCode.NoContent, deleteReply.status)

        val reanimateBody = "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"d29ybGQ=\",\"resave\":true}"
        val reply = postSync(tokenStore, store, token, reanimateBody)
        assertEquals("POST /v1/sync explicit resave:true on deleted row answers 204", HttpStatusCode.NoContent, reply.status)
        val live: String = runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "SELECT deleted, encrypted FROM transcriptions WHERE account_id = ? AND client_id = ?",
                ).use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CLIENT_ID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row for the client id" }
                        rows.getInt(1).toString() + "|" + rows.getString(2)
                    }
                }
            }
        }
        assertEquals("the mark made the row live again with the new body", "0|d29ybGQ=", live)
    }

    @Test
    fun postSyncResaveNonLiteralAnswers400() {
        val body = "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\",\"resave\":\"yes\"}"
        val reply = postSync(tokenStore, store, token, body)
        assertEquals("POST /v1/sync resave non-literal answers 400", HttpStatusCode.BadRequest, reply.status)
    }

    @Test
    fun postSyncUnknownTokenAnswers401() {
        val body = "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\",\"resave\":true}"
        val reply = postSync(tokenStore, store, SyncRouteTestSupport.UNKNOWN_TOKEN, body)
        assertEquals("POST /v1/sync unknown token answers 401", HttpStatusCode.Unauthorized, reply.status)
    }

    // A fresh save of a deleted row follows the insert rule on the key version. Above the
    // account version is refused first; below is refused lower and writes nothing; at reanimates.
    @Test
    fun postSyncResaveOnDeletedRowBelowAccountVersionAnswersRefused() {
        // Push and delete a row at version 0; set the account to version 1.
        val pushBody = "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\"}"
        assertEquals("POST stores", HttpStatusCode.NoContent, postSync(tokenStore, store, token, pushBody).status)
        assertEquals("DELETE answers 204", HttpStatusCode.NoContent, SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, CLIENT_ID).status)
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET key_version = ? WHERE id = ?").use { s ->
                    s.setInt(1, 1)
                    s.setLong(2, alice)
                    s.executeUpdate()
                }
            }
        }
        val beforeSeq = runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { s ->
                    s.setLong(1, alice)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no account" }
                        rows.getLong(1)
                    }
                }
            }
        }
        val beforeCn = runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_no FROM transcriptions WHERE account_id = ? AND client_id = ?").use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CLIENT_ID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        rows.getLong(1)
                    }
                }
            }
        }
        // Re-save at version 0: below the account's version 1 -> RefusedLower, no number taken.
        val belowBody = "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"d29ybGQ=\",\"resave\":true}"
        val reply = postSync(tokenStore, store, token, belowBody)
        assertEquals("POST /v1/sync resave below account version answers 409", HttpStatusCode.Conflict, reply.status)
        assertEquals("POST /v1/sync resave below account version fixed body", SyncRouteTestSupport.LOWER_REFUSED_BODY, reply.body)
        assertEquals("change_seq unchanged by refused re-save", beforeSeq, runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { s ->
                    s.setLong(1, alice)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no account" }
                        rows.getLong(1)
                    }
                }
            }
        })
        assertEquals("change_no unchanged by refused re-save", beforeCn, runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_no FROM transcriptions WHERE account_id = ? AND client_id = ?").use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CLIENT_ID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        rows.getLong(1)
                    }
                }
            }
        })
        // Row is still deleted.
        assertEquals("row still deleted after refused re-save", 1, runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT deleted FROM transcriptions WHERE account_id = ? AND client_id = ?").use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CLIENT_ID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        rows.getInt(1)
                    }
                }
            }
        })
    }

    @Test
    fun postSyncResaveOnDeletedRowAtAccountVersionAnswers204() {
        // Push and delete a row at version 0; set the account to version 1.
        val pushBody = "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\"}"
        assertEquals("POST stores", HttpStatusCode.NoContent, postSync(tokenStore, store, token, pushBody).status)
        assertEquals("DELETE answers 204", HttpStatusCode.NoContent, SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, CLIENT_ID).status)
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET key_version = ? WHERE id = ?").use { s ->
                    s.setInt(1, 1)
                    s.setLong(2, alice)
                    s.executeUpdate()
                }
            }
        }
        val beforeSeq = runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { s ->
                    s.setLong(1, alice)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no account" }
                        rows.getLong(1)
                    }
                }
            }
        }
        // Re-save at version 1: at the account's version -> Stored, row live again.
        val atBody = "{\"client_id\":\"$CLIENT_ID\",\"key_version\":1,\"encrypted\":\"d29ybGQ=\",\"resave\":true}"
        val reply = postSync(tokenStore, store, token, atBody)
        assertEquals("POST /v1/sync resave at account version answers 204", HttpStatusCode.NoContent, reply.status)
        assertEquals("change_seq moved by accepted re-save", beforeSeq + 1, runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { s ->
                    s.setLong(1, alice)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no account" }
                        rows.getLong(1)
                    }
                }
            }
        })
        assertEquals("row live after accepted re-save", 0, runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT deleted FROM transcriptions WHERE account_id = ? AND client_id = ?").use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CLIENT_ID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        rows.getInt(1)
                    }
                }
            }
        })
    }
}
