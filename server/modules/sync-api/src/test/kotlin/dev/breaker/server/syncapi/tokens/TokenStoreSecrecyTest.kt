package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Looks at the bytes on disk, in the main file and in the write-ahead log. The control
 * tests prove the scan can see data that was written, so an empty result means absence.
 */
internal class TokenStoreSecrecyTest : TempDatabaseTest() {

    private val label = "alpha-agent-label"

    private fun populate(temp: TempDatabase): List<IssuedToken> {
        val store = TokenFixtures.tokenStore(temp, MutableClock(TokenFixtures.START))
        val owner = TokenFixtures.register(temp, "alice", 1)
        val tokens = ArrayList<IssuedToken>()
        repeat(3) {
            tokens.add(TokenFixtures.issuedOf(runBlocking { store.issueSession(owner.accountId) }))
        }
        tokens.add(TokenFixtures.issuedOf(runBlocking { store.issueAgent(owner.accountId, AgentScope.ADMIN, label, null) }))
        tokens.add(
            TokenFixtures.issuedOf(runBlocking { store.issueAgent(owner.accountId, AgentScope.TRANSCRIBE, "beta", null) }),
        )
        for (token in tokens) {
            assertNotNull("sync-api: an issued token must resolve once", runBlocking { store.resolve(token.secret.reveal()) })
        }
        return tokens
    }

    private fun filesHolding(directory: Path, needle: ByteArray): List<String> =
        AccountFixtures.filesContaining(directory, needle)

    private fun assertAbsent(phase: String, what: String, directory: Path, needle: ByteArray) {
        assertEquals(
            "sync-api: $what must not be in any database file $phase",
            emptyList<String>(),
            filesHolding(directory, needle),
        )
    }

    private fun assertPresent(phase: String, what: String, directory: Path, needle: ByteArray) {
        assertFalse(
            "sync-api: $what must be found in a database file $phase (the scan has to see written data)",
            filesHolding(directory, needle).isEmpty(),
        )
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun `no raw token text is in any database file, open or checkpointed`() {
        val temp = openTemp()
        val tokens = populate(temp)

        for (token in tokens) {
            assertAbsent("while open", "token text of id ${token.tokenId}", temp.directory, ascii(token.secret.reveal()))
        }
        temp.closeKeepingFiles()
        for (token in tokens) {
            assertAbsent("after close", "token text of id ${token.tokenId}", temp.directory, ascii(token.secret.reveal()))
        }
    }

    @Test
    fun `the decoded random bytes of a token are in no database file, open or checkpointed`() {
        val temp = openTemp()
        val tokens = populate(temp)
        val decoded = tokens.map { token -> Base64.getUrlDecoder().decode(token.secret.reveal()) }
        assertEquals("sync-api: a token decodes to 32 bytes", 32, decoded[0].size)

        for ((index, bytes) in decoded.withIndex()) {
            assertAbsent("while open", "decoded bytes of token $index", temp.directory, bytes)
        }
        temp.closeKeepingFiles()
        for ((index, bytes) in decoded.withIndex()) {
            assertAbsent("after close", "decoded bytes of token $index", temp.directory, bytes)
        }
    }

    @Test
    fun `the label is on disk, so the scan can see written data, open and checkpointed`() {
        val temp = openTemp()
        populate(temp)

        assertPresent("while open", "the agent label", temp.directory, ascii(label))
        temp.closeKeepingFiles()
        assertPresent("after close", "the agent label", temp.directory, ascii(label))
    }

    @Test
    fun `the SHA-256 hash of a token is on disk, so the hash is what is stored`() {
        val temp = openTemp()
        val tokens = populate(temp)
        val hashes = tokens.map { token -> checkNotNull(TokenSecret.parse(token.secret.reveal())).hash() }

        for ((index, hash) in hashes.withIndex()) {
            assertPresent("while open", "the hash of token $index", temp.directory, hash)
        }
        temp.closeKeepingFiles()
        for ((index, hash) in hashes.withIndex()) {
            assertPresent("after close", "the hash of token $index", temp.directory, hash)
        }
    }
}
