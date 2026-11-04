package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthChangePasswordRouteTest : AuthChangePasswordRouteTestBase() {

    private val changedBody = """{"key_version":2}"""

    /** The raw JSON text of one top-level member of a login answer. */
    private fun memberOf(body: String, name: String): String {
        val marker = "\"$name\":"
        val start = body.indexOf(marker)
        check(start >= 0) { "sync-api test helper: member $name is missing from the body" }
        val from = start + marker.length
        var end = from
        while (end < body.length && body[end] != ',' && body[end] != '}') end++
        return body.substring(from, end)
    }

    /** A stored row written under key_version 1, so it stays below the key_version 2 the change sets. */
    private fun addOldRow(accountId: Long) {
        SyncRouteTestSupport.insertRawRow(
            temp, accountId, SyncRouteTestSupport.CLIENT_ID, 1, SyncRouteTestSupport.ENCRYPTED, KeyPatterns.REKEYED_AT_MS,
        )
    }

    private fun assertChanged(accountId: Long, before: CredentialColumns, prev: ByteArray?, label: String) {
        val after = readCredentialColumns(temp, accountId)
        assertArrayEquals("sync-api: $label salt", newSaltBytes, after.salt)
        assertEquals("sync-api: $label kdf memory", newKdf.memoryKib, after.kdfMemory)
        assertEquals("sync-api: $label kdf iterations", newKdf.iterations, after.kdfIterations)
        assertEquals("sync-api: $label kdf parallelism", newKdf.parallelism, after.kdfParallelism)
        assertEquals("sync-api: $label kdf version", 1, after.kdfVersion)
        assertEquals("sync-api: $label verifier algo", before.verifierAlgo, after.verifierAlgo)
        assertFalse("sync-api: $label verifier hash did not change", before.verifierHash.contentEquals(after.verifierHash))
        val keys = columnsOf(accountId)
        assertArrayEquals("sync-api: $label wrapped_dek", newDekBytes, keys.wrappedDek)
        assertArrayEquals("sync-api: $label wrapped_box_privkey", newPrivkeyBytes, keys.wrappedBoxPrivkey)
        assertArrayEquals("sync-api: $label box_pubkey", newPubkeyBytes, keys.boxPubkey)
        assertArrayEquals("sync-api: $label prev_wrapped_dek", prev, keys.prevWrappedDek)
        assertEquals("sync-api: $label key_version", 2, keys.keyVersion)
        assertEquals("sync-api: $label rekeyed_at", KeyPatterns.REKEYED_AT_MS, keys.rekeyedAt)
        assertNull("sync-api: $label reset_at", keys.resetAt)
    }

    private fun assertKdfAccepted(memory: Int, iterations: Int, parallelism: Int) {
        val id = keyedAccount("alice", 1)
        val reply = postChange(sessionToken(id), withKdf(memory, iterations, parallelism))
        assertAnswer("kdf $memory $iterations $parallelism", reply, HttpStatusCode.OK, changedBody)
        val columns = readCredentialColumns(temp, id)
        assertEquals("sync-api: kdf memory", memory, columns.kdfMemory)
        assertEquals("sync-api: kdf iterations", iterations, columns.kdfIterations)
        assertEquals("sync-api: kdf parallelism", parallelism, columns.kdfParallelism)
    }

    @Test
    fun `kdf values at the floor are accepted`() {
        assertKdfAccepted(65536, 3, 1)
    }

    @Test
    fun `kdf values at the ceiling are accepted`() {
        assertKdfAccepted(262144, 10, 4)
    }

    @Test
    fun `a keyed account changes the password and gets 200 with key_version 2 and every column as sent`() {
        val id = keyedAccount("alice", 1)
        addOldRow(id)
        val before = readCredentialColumns(temp, id)
        assertAnswer("change", postChange(sessionToken(id), validChangeBody()), HttpStatusCode.OK, changedBody)
        assertChanged(id, before, prevDekBytes, "the change")
    }

    @Test
    fun `a change on an account with no stored rows clears prev_wrapped_dek at once`() {
        val id = keyedAccount("alice", 1)
        val before = readCredentialColumns(temp, id)
        assertAnswer("change", postChange(sessionToken(id), validChangeBody()), HttpStatusCode.OK, changedBody)
        assertChanged(id, before, null, "the change without rows")
    }

    @Test
    fun `the answer has a JSON content type`() {
        val id = keyedAccount("alice", 1)
        val answer = postChangeFull(sessionToken(id), validChangeBody())
        assertEquals("sync-api: status", HttpStatusCode.OK, answer.status)
        val type = answer.typeHeader.orEmpty()
        assertTrue("sync-api: the answer has content type $type", type.startsWith("application/json"))
    }

    @Test
    fun `after the change the old verifier is refused at login and the new one opens the new keys`() {
        val id = keyedAccount("alice", 1)
        addOldRow(id)
        assertAnswer("change", postChange(sessionToken(id), validChangeBody()), HttpStatusCode.OK, changedBody)
        assertAnswer("login with the old verifier", loginAs("alice", oldVerifierBytes), HttpStatusCode.Unauthorized, loginRefusedBody)
        val login = loginAs("alice", newVerifierBytes)
        assertEquals("sync-api: login with the new verifier", HttpStatusCode.OK, login.status)
        val body = login.body
        assertEquals("sync-api: wrapped_dek", quoted(textOf(newDekBytes)), memberOf(body, "wrapped_dek"))
        assertEquals("sync-api: wrapped_box_privkey", quoted(textOf(newPrivkeyBytes)), memberOf(body, "wrapped_box_privkey"))
        assertEquals("sync-api: box_pubkey", quoted(textOf(newPubkeyBytes)), memberOf(body, "box_pubkey"))
        assertEquals("sync-api: prev_wrapped_dek", quoted(textOf(prevDekBytes)), memberOf(body, "prev_wrapped_dek"))
        assertEquals("sync-api: key_version", "2", memberOf(body, "key_version"))
        assertEquals("sync-api: rekeyed_at", quoted("2023-11-14T22:13:20.000Z"), memberOf(body, "rekeyed_at"))
    }

    @Test
    fun `the change revokes other sessions and keeps this session and the agent token`() {
        val id = keyedAccount("alice", 1)
        val current = sessionToken(id)
        val otherOne = sessionToken(id)
        val otherTwo = sessionToken(id)
        val agent = agentText(id)
        assertTrue("sync-api: the other session must work before the change", sessionWorks(otherOne))
        assertAnswer("change", postChange(current, validChangeBody()), HttpStatusCode.OK, changedBody)
        for ((label, bearer) in listOf("first other" to otherOne, "second other" to otherTwo)) {
            val reply = getList(bearer)
            assertAnswer("$label session after the change", reply, HttpStatusCode.Unauthorized, SyncRouteTestSupport.UNAUTHORIZED_BODY)
        }
        assertTrue("sync-api: this session must work after the change", sessionWorks(current))
        val agentReply = postChange(agent, validChangeBody())
        assertAnswer("agent token after the change", agentReply, HttpStatusCode.Forbidden, SyncRouteTestSupport.FORBIDDEN_BODY)
    }

    @Test
    fun `the change leaves a second account untouched`() {
        val alice = keyedAccount("alice", 1)
        val bob = keyedAccount("bob", 2)
        val bobSession = sessionToken(bob)
        val before = snapshotOf(bob)
        assertAnswer("change", postChange(sessionToken(alice), validChangeBody()), HttpStatusCode.OK, changedBody)
        assertUnchanged(bob, before, "the change of the other account")
        assertTrue("sync-api: the session of the second account must work", sessionWorks(bobSession))
        assertEquals("sync-api: login of the second account", HttpStatusCode.OK, loginAs("bob", AccountFixtures.verifierBytes(2)).status)
    }

    @Test
    fun `the change clears the wrong-password row with the limit on`() {
        val id = keyedAccount("alice", 1)
        recordWrongTries("alice", 1)
        assertEquals("sync-api: the row must exist before the change", 1L, wrongCountOf("alice"))
        assertAnswer("change", postChange(sessionToken(id), validChangeBody()), HttpStatusCode.OK, changedBody)
        assertEquals("sync-api: the row must be gone after the change", 0L, wrongCountOf("alice"))
    }

    @Test
    fun `the change itself clears the wrong-password row when the proof does not`() {
        val id = keyedAccount("alice", 1)
        limitOff()
        recordWrongTries("alice", 2)
        assertEquals("sync-api: the row must exist before the change", 2L, wrongCountOf("alice"))
        assertAnswer("change", postChange(sessionToken(id), validChangeBody()), HttpStatusCode.OK, changedBody)
        assertEquals("sync-api: the transaction must clear the row", 0L, wrongCountOf("alice"))
    }

    @Test
    fun `the change ends the hand-off codes of the account and keeps those of a second account`() {
        val alice = keyedAccount("alice", 1)
        val bob = keyedAccount("bob", 2)
        val session = sessionToken(alice)
        val firstCode = codeFor(session, sealedPackage)
        val secondCode = codeFor(session, sealedPackage)
        val bobCode = codeFor(sessionToken(bob), sealedPackage)
        assertEquals("sync-api: codes of the account before", 2L, HandoffFixtures.rowCountFor(temp, alice))
        assertAnswer("change", postChange(session, validChangeBody()), HttpStatusCode.OK, changedBody)
        assertEquals("sync-api: codes of the account after", 0L, HandoffFixtures.rowCountFor(temp, alice))
        for (code in listOf(firstCode, secondCode)) {
            val reply = exch("""{"code":"$code","secret":"$secret"}""")
            assertAnswer("exchange of a code of the account", reply, HttpStatusCode.Unauthorized, exchangeRefusedBody)
        }
        assertEquals("sync-api: codes of the second account", 1L, HandoffFixtures.rowCountFor(temp, bob))
        val bobReply = exch("""{"code":"$bobCode","secret":"$secret"}""")
        assertEquals("sync-api: exchange of the second account code", HttpStatusCode.OK, bobReply.status)
    }

    @Test
    fun `a body of exactly 4096 bytes with a declared length is processed and gets 200`() {
        val id = keyedAccount("alice", 1)
        assertAnswer("body at the cap", postChange(sessionToken(id), padTo(validChangeBody(), 4096)), HttpStatusCode.OK, changedBody)
        assertEquals("sync-api: key_version", 2, columnsOf(id).keyVersion)
    }

    @Test
    fun `a body of 4097 bytes with a declared length gets 413 with the exact body and writes nothing`() {
        val id = keyedAccount("alice", 1)
        val session = sessionToken(id)
        val before = snapshotOf(id)
        assertAnswer("body over the cap", postChange(session, padTo(validChangeBody(), 4097)), HttpStatusCode.PayloadTooLarge, tooLargeBody)
        assertUnchanged(id, before, "the body over the cap")
    }

    @Test
    fun `a chunked body of exactly 4096 bytes gets 200`() {
        val id = keyedAccount("alice", 1)
        val reply = postChangeChunked(sessionToken(id), padTo(validChangeBody(), 4096).toByteArray())
        assertAnswer("chunked body at the cap", reply, HttpStatusCode.OK, changedBody)
        assertEquals("sync-api: key_version", 2, columnsOf(id).keyVersion)
    }

    @Test
    fun `a chunked body of 4097 bytes gets 413 with the exact body and writes nothing`() {
        val id = keyedAccount("alice", 1)
        val session = sessionToken(id)
        val before = snapshotOf(id)
        val reply = postChangeChunked(session, padTo(validChangeBody(), 4097).toByteArray())
        assertAnswer("chunked body over the cap", reply, HttpStatusCode.PayloadTooLarge, tooLargeBody)
        assertUnchanged(id, before, "the chunked body over the cap")
    }

    @Test
    fun `an invalid body from a locked name gets 400 because the body is read before the proof`() {
        val id = keyedAccount("alice", 1)
        val session = sessionToken(id)
        recordWrongTries("alice", 5)
        assertAnswer("invalid body", postChange(session, without("new_salt")), HttpStatusCode.BadRequest, badRequestBody)
        assertAnswer("valid body", postChange(session, validChangeBody()), HttpStatusCode.Locked, lockedBody)
        assertEquals("sync-api: key_version", 1, columnsOf(id).keyVersion)
    }

    @Test
    fun `an invalid body with a wrong old verifier gets 400 and is not counted`() {
        val id = keyedAccount("alice", 1)
        val members = validMembers().filter { (key, _) -> key != "new_salt" && key != "old_auth_verifier" }
        val body = objectOf(members + ("old_auth_verifier" to quoted(textOf(wrongVerifierBytes))))
        assertAnswer("invalid body", postChange(sessionToken(id), body), HttpStatusCode.BadRequest, badRequestBody)
        assertEquals("sync-api: the wrong old verifier must not be counted", 0L, wrongCountOf("alice"))
    }

    @Test
    fun `an invalid body from an account that would get 409 gets 400`() {
        val id = keyedAccount("alice", 1)
        presetPrev(temp, id, KeyPatterns.prevWrappedDek())
        assertAnswer("invalid body", postChange(sessionToken(id), without("new_salt")), HttpStatusCode.BadRequest, badRequestBody)
    }
}
