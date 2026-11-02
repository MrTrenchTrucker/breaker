package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

internal class SyncRoutesDeleteRouteTest : TempDatabaseTest() {

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

    private val CID = SyncRouteTestSupport.CLIENT_ID

    @Test
    fun deleteLiveRowAnswers204() {
        val pushBody = "{\"client_id\":\"$CID\",\"key_version\":0,\"encrypted\":\"aGVsbG8=\"}"
        val pushReply = SyncRouteTestSupport.postSync(tokenStore, store, token, pushBody)
        assertEquals("POST /v1/sync stores", HttpStatusCode.NoContent, pushReply.status)

        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, CID)
        assertEquals("DELETE live row answers 204", HttpStatusCode.NoContent, deleteReply.status)

        // Row read back by SQL: deleted flag is 1, body is '-'.
        assertEquals("row deleted flag after delete", 1, runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT deleted FROM transcriptions WHERE account_id = ? AND client_id = ?").use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        rows.getInt(1)
                    }
                }
            }
        })
        assertEquals("row body after delete", "-", runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT encrypted FROM transcriptions WHERE account_id = ? AND client_id = ?").use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        rows.getString(1)
                    }
                }
            }
        })
    }

    @Test
    fun deleteRepeatAnswers204() {
        val pushBody = "{\"client_id\":\"$CID\",\"key_version\":0,\"encrypted\":\"aGVsbG8=\"}"
        val pushReply = SyncRouteTestSupport.postSync(tokenStore, store, token, pushBody)
        assertEquals("POST /v1/sync stores", HttpStatusCode.NoContent, pushReply.status)

        val first = SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, CID)
        assertEquals("first delete answers 204", HttpStatusCode.NoContent, first.status)

        val second = SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, CID)
        assertEquals("repeat delete answers 204", HttpStatusCode.NoContent, second.status)

        // change_seq and change_no are unchanged by the repeat delete.
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
                    s.setString(2, CID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        rows.getLong(1)
                    }
                }
            }
        }
        SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, CID)
        assertEquals("change_seq unchanged by third delete", beforeSeq, runBlocking {
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
        assertEquals("change_no unchanged by third delete", beforeCn, runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_no FROM transcriptions WHERE account_id = ? AND client_id = ?").use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        rows.getLong(1)
                    }
                }
            }
        })
    }

    @Test
    fun deleteUnknownIdAnswers204() {
        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, "0000" + "0000-0000-0000-0000-" + "000000" + "000000")
        assertEquals("DELETE unknown id answers 204", HttpStatusCode.NoContent, deleteReply.status)
    }

    @Test
    fun deleteUppercaseIdAnswers400() {
        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, "0F9C" + "4D1E-6A2B-4C3D-9E8F-" + "1A2B3C" + "4D5E6F")
        assertEquals("DELETE uppercase id answers 400", HttpStatusCode.BadRequest, deleteReply.status)
        assertEquals("DELETE uppercase id fixed body", SyncRouteTestSupport.BAD_REQUEST_BODY, deleteReply.body)
    }

    @Test
    fun deleteMissingHyphenIdAnswers400() {
        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, "0f9c" + "4d1e" + "6a2b" + "4c3d" + "9e8f" + "1a2b" + "3c4d" + "5e6f")
        assertEquals("DELETE missing-hyphen id answers 400", HttpStatusCode.BadRequest, deleteReply.status)
        assertEquals("DELETE missing-hyphen id fixed body", SyncRouteTestSupport.BAD_REQUEST_BODY, deleteReply.body)
    }

    @Test
    fun deleteThirtySevenCharIdAnswers400() {
        // A real 37-character lowercase value that is not a UUID.
        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, "0f9c" + "4d1e" + "6a2b" + "4c3d" + "9e8f" + "1a2b" + "3c4d" + "5e6f" + "00000")
        assertEquals("DELETE 37-char id answers 400", HttpStatusCode.BadRequest, deleteReply.status)
        assertEquals("DELETE 37-char id fixed body", SyncRouteTestSupport.BAD_REQUEST_BODY, deleteReply.body)
    }

    @Test
    fun deleteNoTokenAnswers401() {
        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, null, CID)
        assertEquals("DELETE no token answers 401", HttpStatusCode.Unauthorized, deleteReply.status)
    }

    @Test
    fun deleteMalformedBearerAnswers401() {
        val deleteReply = SyncRouteTestSupport.deleteTranscriptionWithAuthorization(tokenStore, store, "Bearer", CID)
        assertEquals("DELETE malformed Bearer answers 401", HttpStatusCode.Unauthorized, deleteReply.status)
    }

    @Test
    fun deleteUnknownTokenAnswers401() {
        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, SyncRouteTestSupport.UNKNOWN_TOKEN, CID)
        assertEquals("DELETE unknown token answers 401", HttpStatusCode.Unauthorized, deleteReply.status)
    }

    @Test
    fun deleteAgentTokenAnswers403() {
        val agentToken = SyncRouteTestSupport.agentToken(tokenStore, alice)
        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, agentToken, CID)
        assertEquals("DELETE agent token answers 403", HttpStatusCode.Forbidden, deleteReply.status)
    }

    @Test
    fun deleteClosedDatabaseAnswers503() {
        // Tokens on an open database; the TranscriptionStore is on a separate closed database so the
        // DELETE handler is reached and the store failure answers 503.
        val closed = openTemp()
        closed.db.close()
        val closedStore = SyncRouteTestSupport.newStore(closed, clock)
        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, closedStore, token, CID)
        assertEquals("DELETE closed database answers 503", HttpStatusCode.ServiceUnavailable, deleteReply.status)
        assertEquals("DELETE closed database fixed body", SyncRouteTestSupport.UNAVAILABLE_BODY, deleteReply.body)
    }

    @Test
    fun reanimateDeletedRowWithMarkAnswers204() {
        val pushBody = "{\"client_id\":\"$CID\",\"key_version\":0,\"encrypted\":\"aGVsbG8=\"}"
        val pushReply = SyncRouteTestSupport.postSync(tokenStore, store, token, pushBody)
        assertEquals("POST stores", HttpStatusCode.NoContent, pushReply.status)

        val deleteReply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, CID)
        assertEquals("DELETE answers 204", HttpStatusCode.NoContent, deleteReply.status)

        // Re-save with resave=true brings the row back: 204, deleted 0, new body.
        val reanimateBody = "{\"client_id\":\"$CID\",\"key_version\":0,\"encrypted\":\"d29ybGQ=\",\"resave\":true}"
        val reanimateReply = SyncRouteTestSupport.postSync(tokenStore, store, token, reanimateBody)
        assertEquals("POST reanimate answers 204", HttpStatusCode.NoContent, reanimateReply.status)

        assertEquals("row deleted flag after reanimate", 0, runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT deleted FROM transcriptions WHERE account_id = ? AND client_id = ?").use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        rows.getInt(1)
                    }
                }
            }
        })
        assertEquals("row body after reanimate", "d29ybGQ=", runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT encrypted FROM transcriptions WHERE account_id = ? AND client_id = ?").use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        rows.getString(1)
                    }
                }
            }
        })
    }
}
