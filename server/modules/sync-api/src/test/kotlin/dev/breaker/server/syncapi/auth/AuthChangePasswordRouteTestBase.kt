package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.KdfParams
import dev.breaker.server.syncapi.accounts.KeyBundle
import dev.breaker.server.syncapi.accounts.StoreKeysResult
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.syncApiModule
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

/** One answer with the two headers the change-password tests read. */
internal class ChangeAnswer(val status: HttpStatusCode, val body: String, val retryAfter: String?, val typeHeader: String?)

private fun hexOrNull(bytes: ByteArray?): String = if (bytes == null) "null" else AccountFixtures.hex(bytes)

/** The credential columns of one account row, read straight from the table. */
internal class CredentialColumns(
    val salt: ByteArray?,
    val kdfMemory: Int,
    val kdfIterations: Int,
    val kdfParallelism: Int,
    val kdfVersion: Int,
    val verifierAlgo: String?,
    val verifierIters: Int,
    val verifierSalt: ByteArray?,
    val verifierHash: ByteArray?,
) {
    /** Every column as one text, so two reads compare with one assertion. */
    fun asText(): String = listOf(
        hexOrNull(salt), kdfMemory.toString(), kdfIterations.toString(), kdfParallelism.toString(),
        kdfVersion.toString(), verifierAlgo.toString(), verifierIters.toString(), hexOrNull(verifierSalt),
        hexOrNull(verifierHash),
    ).joinToString("/")
}

internal fun readCredentialColumns(temp: TempDatabase, accountId: Long): CredentialColumns = runBlocking {
    temp.db.transaction { connection ->
        connection.prepareStatement(
            "SELECT salt, kdf_memory_kib, kdf_iterations, kdf_parallelism, kdf_version, verifier_algo, " +
                "verifier_iters, verifier_salt, verifier_hash FROM accounts WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sync-api test helper: no account with id $accountId" }
                CredentialColumns(
                    rows.getBytes(1), rows.getInt(2), rows.getInt(3), rows.getInt(4), rows.getInt(5),
                    rows.getString(6), rows.getInt(7), rows.getBytes(8), rows.getBytes(9),
                )
            }
        }
    }
}

/** The token rows of one account as one text: id and revoked time (or live), in id order. */
internal fun tokenStateOf(temp: TempDatabase, accountId: Long): String = runBlocking {
    temp.db.transaction { connection ->
        connection.prepareStatement("SELECT id, revoked_at_ms FROM tokens WHERE account_id = ? ORDER BY id").use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                val parts = ArrayList<String>()
                while (rows.next()) {
                    val revoked = rows.getLong(2).let { value -> if (rows.wasNull()) "live" else value.toString() }
                    parts.add("${rows.getLong(1)}:$revoked")
                }
                parts.joinToString(",")
            }
        }
    }
}

/** The shared set-up of the change-password route tests: the new key patterns, body builders, request helpers and state reads. */
internal abstract class AuthChangePasswordRouteTestBase : AuthKeysRouteTestBase() {

    protected val changePath = "/v1/auth/change-password"
    protected val loginPath = "/v1/auth/login"

    protected val oldVerifierBytes: ByteArray = AccountFixtures.verifierBytes(1)
    protected val newVerifierBytes: ByteArray = AccountFixtures.verifierBytes(77)
    protected val wrongVerifierBytes: ByteArray = AccountFixtures.verifierBytes(99)
    protected val newSaltBytes: ByteArray = ByteArray(16) { i -> (i * 13 + 25).toByte() }
    protected val newDekBytes: ByteArray = ByteArray(60) { i -> (i * 3 + 21).toByte() }
    protected val newPrivkeyBytes: ByteArray = ByteArray(60) { i -> (i * 5 + 22).toByte() }
    protected val newPubkeyBytes: ByteArray = ByteArray(32) { i -> (i * 7 + 23).toByte() }
    protected val prevDekBytes: ByteArray = ByteArray(60) { i -> (i * 11 + 24).toByte() }
    protected val newKdf = KdfParams(131072, 4, 2)

    protected val oldVerifierWrongBody = """{"error":"unauthorized","message":"Invalid old verifier"}"""
    protected val lockedBody = """{"error":"locked","message":"Too many wrong tries. Try again in 15 minutes."}"""
    protected val noKeysBody = """{"error":"conflict","message":"The account has no keys"}"""
    protected val versionBody = """{"error":"conflict","message":"key_version is not the account's current key_version plus one"}"""
    protected val unfinishedBody = """{"error":"conflict","message":"A previous re-key is unfinished"}"""
    protected val exchangeRefusedBody = """{"error":"unauthorized","message":"Invalid sign-in code or secret"}"""
    protected val loginRefusedBody = """{"error":"unauthorized","message":"Invalid username or verifier"}"""

    protected fun quoted(text: String): String = "\"$text\""

    protected fun textOf(bytes: ByteArray): String = KeyPatterns.text(bytes)

    protected fun kdfRaw(memory: Int, iterations: Int, parallelism: Int): String =
        """{"memory_kib":$memory,"iterations":$iterations,"parallelism":$parallelism}"""

    /** The ten members of a valid body, in the documented order, as name and raw JSON value. */
    protected fun validMembers(): List<Pair<String, String>> = listOf(
        "old_auth_verifier" to quoted(textOf(oldVerifierBytes)),
        "new_salt" to quoted(textOf(newSaltBytes)),
        "new_kdf_params" to kdfRaw(newKdf.memoryKib, newKdf.iterations, newKdf.parallelism),
        "new_kdf_version" to "1",
        "new_wrapped_dek" to quoted(textOf(newDekBytes)),
        "new_wrapped_box_privkey" to quoted(textOf(newPrivkeyBytes)),
        "new_box_pubkey" to quoted(textOf(newPubkeyBytes)),
        "new_auth_verifier" to quoted(textOf(newVerifierBytes)),
        "new_key_version" to "2",
        "prev_wrapped_dek" to quoted(textOf(prevDekBytes)),
    )

    protected fun objectOf(members: List<Pair<String, String>>): String =
        members.joinToString(",", "{", "}") { (name, raw) -> "\"$name\":$raw" }

    protected fun validChangeBody(): String = objectOf(validMembers())

    /** The valid body with the raw JSON value of the member called [name] replaced. */
    protected fun withRaw(name: String, raw: String): String =
        objectOf(validMembers().map { (key, value) -> key to (if (key == name) raw else value) })

    protected fun withText(name: String, text: String): String = withRaw(name, quoted(text))

    protected fun without(name: String): String = objectOf(validMembers().filter { (key, _) -> key != name })

    protected fun withKdf(memory: Int, iterations: Int, parallelism: Int): String =
        withRaw("new_kdf_params", kdfRaw(memory, iterations, parallelism))

    protected fun withKeyVersion(version: Int): String = withRaw("new_key_version", version.toString())

    protected fun withOldVerifier(bytes: ByteArray): String = withText("old_auth_verifier", textOf(bytes))

    protected fun postChange(bearer: String?, body: String): Reply = postTo(changePath, bearer, body)

    protected fun loginAs(name: String, verifierBytes: ByteArray): Reply =
        postTo(loginPath, null, """{"username":"$name","auth_verifier":"${textOf(verifierBytes)}"}""")

    /** Sends the Authorization header exactly as given, or none when it is null. */
    protected fun postChangeWithAuthorization(authorization: String?, body: String): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post(changePath) {
                    if (authorization != null) header(HttpHeaders.Authorization, authorization)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    /** The answer with its Retry-After and Content-Type headers. */
    protected fun postChangeFull(bearer: String?, body: String): ChangeAnswer {
        var answer: ChangeAnswer? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post(changePath) {
                    if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                answer = ChangeAnswer(
                    response.status,
                    response.bodyAsText(),
                    response.headers[HttpHeaders.RetryAfter],
                    response.headers[HttpHeaders.ContentType],
                )
            }
        }
        return checkNotNull(answer)
    }

    /** A body sent without a declared length (a chunked request). */
    protected fun postChangeChunked(bearer: String?, bytes: ByteArray): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post(changePath) {
                    if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                    setBody(object : OutgoingContent.ReadChannelContent() {
                        override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
                    })
                    contentType(ContentType.Application.Json)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    /** Registers [name] with the verifier of [seed] and stores the base key set, so key_version is 1. */
    protected fun keyedAccount(name: String, seed: Int): Long {
        val id = register(name, seed)
        val result = runBlocking {
            accountStore.storeKeys(id, KeyBundle(wrappedDekBytes, wrappedBoxPrivkeyBytes, boxPubkeyBytes))
        }
        if (result !is StoreKeysResult.Stored) {
            throw AssertionError("sync-api: expected Stored in the set-up but got $result")
        }
        return id
    }

    /** Writes [count] wrong-try counts for the folded name straight through the attempt store. */
    protected fun recordWrongTries(foldedName: String, count: Int) {
        repeat(count) { runBlocking { accountStore.attempts.recordWrong(foldedName) } }
    }

    protected fun wrongCountOf(foldedName: String): Long =
        runBlocking { accountStore.attempts.attemptOf(foldedName) }?.wrongCount ?: 0L

    /** Turns the wrong-password limit off, so the old-verifier proof neither counts nor clears. */
    protected fun limitOff() {
        runBlocking { accountStore.settings.setWrongPwLimitEnabled(false) }
    }

    /** True when the bearer still opens a protected route. */
    protected fun sessionWorks(bearer: String): Boolean = getList(bearer).status == HttpStatusCode.OK

    /** Credentials, keys and token rows of one account as one text. */
    protected fun snapshotOf(accountId: Long): String {
        val keys = readKeyColumns(temp, accountId)
        val keyText = listOf(
            hexOrNull(keys.wrappedDek), hexOrNull(keys.wrappedBoxPrivkey), hexOrNull(keys.boxPubkey),
            keys.keyVersion.toString(), hexOrNull(keys.prevWrappedDek), keys.rekeyedAt.toString(), keys.resetAt.toString(),
        ).joinToString("/")
        return readCredentialColumns(temp, accountId).asText() + "/" + keyText + "/" + tokenStateOf(temp, accountId)
    }

    protected fun assertAnswer(label: String, reply: Reply, status: HttpStatusCode, body: String) {
        assertEquals("sync-api: $label status", status, reply.status)
        assertEquals("sync-api: $label body", body, reply.body)
    }

    protected fun assertUnchanged(accountId: Long, before: String, label: String) {
        assertEquals("sync-api: $label changed the account or its tokens", before, snapshotOf(accountId))
    }
}
