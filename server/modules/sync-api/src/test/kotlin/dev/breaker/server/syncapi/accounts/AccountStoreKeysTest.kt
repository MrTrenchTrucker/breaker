package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.auth.presetPrev
import dev.breaker.server.syncapi.auth.readKeyColumns
import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import java.nio.charset.StandardCharsets
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class AccountStoreKeysTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    private val keyVersionTrigger =
        "CREATE TRIGGER forced_keys_failure AFTER UPDATE OF key_version ON accounts " +
            "WHEN NEW.wrapped_dek IS NOT NULL BEGIN SELECT RAISE(ABORT, 'forced'); END"
    private val anyUpdateTrigger =
        "CREATE TRIGGER forced_keys_failure AFTER UPDATE ON accounts " +
            "WHEN NEW.wrapped_dek IS NOT NULL BEGIN SELECT RAISE(ABORT, 'forced'); END"

    @Before
    fun setup() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
    }

    private fun register(name: String, seed: Int): Long =
        AccountFixtures.registered(
            runBlocking {
                store.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed))
            },
        ).accountId

    private fun bundle(): KeyBundle =
        KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())

    private fun storeKeys(accountId: Long, keys: KeyBundle): StoreKeysResult = runBlocking { store.storeKeys(accountId, keys) }

    private fun keysFor(accountId: Long): StoredKeys? = runBlocking { store.keysFor(accountId) }

    private fun stored(result: StoreKeysResult): StoreKeysResult.Stored {
        if (result !is StoreKeysResult.Stored) {
            throw AssertionError("sync-api: expected Stored but got $result")
        }
        return result
    }

    private fun scribble(bytes: ByteArray?) {
        val array = checkNotNull(bytes)
        array[0] = (array[0] + 1).toByte()
    }

    private fun assertKeyless(accountId: Long, label: String) {
        val columns = readKeyColumns(temp, accountId)
        assertNull("sync-api: $label left wrapped_dek set", columns.wrappedDek)
        assertNull("sync-api: $label left wrapped_box_privkey set", columns.wrappedBoxPrivkey)
        assertNull("sync-api: $label left box_pubkey set", columns.boxPubkey)
        assertEquals("sync-api: $label changed key_version", 0, columns.keyVersion)
    }

    private fun assertStoredPatterns(accountId: Long, version: Int, label: String) {
        val columns = readKeyColumns(temp, accountId)
        assertArrayEquals("sync-api: $label wrapped_dek", KeyPatterns.wrappedDek(), columns.wrappedDek)
        assertArrayEquals("sync-api: $label wrapped_box_privkey", KeyPatterns.wrappedBoxPrivkey(), columns.wrappedBoxPrivkey)
        assertArrayEquals("sync-api: $label box_pubkey", KeyPatterns.boxPubkey(), columns.boxPubkey)
        assertEquals("sync-api: $label key_version", version, columns.keyVersion)
    }

    /** The store call fails under the trigger, writes nothing, and works once the trigger is dropped. */
    private fun assertAtomicUnder(triggerSql: String) {
        val id = register("alice", 1)
        runSql(temp, triggerSql)
        val failure = TestDatabases.expectFailure<SQLException>("storeKeys under a forced failure") {
            storeKeys(id, bundle())
        }
        assertTrue("sync-api: the failure must be the forced one: ${failure.message}", failure.message.orEmpty().contains("forced"))
        assertKeyless(id, "the failed storeKeys")
        runSql(temp, "DROP TRIGGER forced_keys_failure")
        assertEquals("sync-api: key_version after the trigger is dropped", 1, stored(storeKeys(id, bundle())).keyVersion)
        assertStoredPatterns(id, 1, "the call after the trigger is dropped")
    }

    private fun bundleWith(member: Int, size: Int): KeyBundle {
        val arrays = arrayOf(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        arrays[member] = ByteArray(size) { i -> (i + 100).toByte() }
        return KeyBundle(arrays[0], arrays[1], arrays[2])
    }

    @Test
    fun `storeKeys on a keyless account returns Stored with key_version 1 and writes the bundle bytes`() {
        val id = register("alice", 1)
        assertEquals("sync-api: key_version of Stored", 1, stored(storeKeys(id, bundle())).keyVersion)
        assertStoredPatterns(id, 1, "the first storeKeys")
    }

    @Test
    fun `a second storeKeys returns AlreadyKeyed and leaves the columns and key_version as they were`() {
        val id = register("alice", 1)
        stored(storeKeys(id, bundle()))
        val other = KeyBundle(ByteArray(60) { 9 }, ByteArray(60) { 8 }, ByteArray(32) { 7 })
        assertEquals("sync-api: second storeKeys result", StoreKeysResult.AlreadyKeyed, storeKeys(id, other))
        assertStoredPatterns(id, 1, "the second storeKeys")
    }

    @Test
    fun `storeKeys for an id with no account returns NoSuchAccount and writes nothing`() {
        val id = register("alice", 1)
        assertEquals("sync-api: result for an unknown id", StoreKeysResult.NoSuchAccount, storeKeys(id + 1000L, bundle()))
        assertKeyless(id, "a storeKeys for another id")
        assertEquals("sync-api: an account was created", 1L, AccountFixtures.accountCount(temp))
    }

    @Test
    fun `a keyless account preset to key_version 3 gets Stored with key_version 4`() {
        val id = register("alice", 1)
        runSql(temp, "UPDATE accounts SET key_version = 3 WHERE id = $id")
        assertEquals("sync-api: key_version of Stored", 4, stored(storeKeys(id, bundle())).keyVersion)
        assertStoredPatterns(id, 4, "the storeKeys on version 3")
    }

    @Test
    fun `storeKeys leaves a second account untouched`() {
        val alice = register("alice", 1)
        val bob = register("bob", 2)
        runSql(temp, "UPDATE accounts SET key_version = 2 WHERE id = $bob")
        stored(storeKeys(alice, bundle()))
        val columns = readKeyColumns(temp, bob)
        assertNull("sync-api: bob gained wrapped_dek", columns.wrappedDek)
        assertNull("sync-api: bob gained wrapped_box_privkey", columns.wrappedBoxPrivkey)
        assertNull("sync-api: bob gained box_pubkey", columns.boxPubkey)
        assertEquals("sync-api: bob key_version changed", 2, columns.keyVersion)
        assertStoredPatterns(alice, 1, "alice")
    }

    @Test
    fun `a forced failure after the first write leaves the columns NULL and key_version 0 and the call works after the trigger is dropped`() {
        assertAtomicUnder(anyUpdateTrigger)
    }

    @Test
    fun `a forced failure on the key_version change leaves the columns NULL and key_version 0 and the call works after the trigger is dropped`() {
        assertAtomicUnder(keyVersionTrigger)
    }

    @Test
    fun `keysFor a keyless account answers null members key_version 0 and no timestamps`() {
        val id = register("alice", 1)
        val keys = checkNotNull(keysFor(id))
        assertNull("sync-api: wrapped_dek", keys.wrappedDek)
        assertNull("sync-api: wrapped_box_privkey", keys.wrappedBoxPrivkey)
        assertNull("sync-api: box_pubkey", keys.boxPubkey)
        assertNull("sync-api: prev_wrapped_dek", keys.prevWrappedDek)
        assertNull("sync-api: rekeyed_at", keys.rekeyedAtMs)
        assertNull("sync-api: reset_at", keys.resetAtMs)
        assertEquals("sync-api: key_version", 0, keys.keyVersion)
    }

    @Test
    fun `keysFor a keyed account answers the stored bytes and key_version 1`() {
        val id = register("alice", 1)
        stored(storeKeys(id, bundle()))
        val keys = checkNotNull(keysFor(id))
        assertArrayEquals("sync-api: wrapped_dek", KeyPatterns.wrappedDek(), keys.wrappedDek)
        assertArrayEquals("sync-api: wrapped_box_privkey", KeyPatterns.wrappedBoxPrivkey(), keys.wrappedBoxPrivkey)
        assertArrayEquals("sync-api: box_pubkey", KeyPatterns.boxPubkey(), keys.boxPubkey)
        assertNull("sync-api: prev_wrapped_dek", keys.prevWrappedDek)
        assertEquals("sync-api: key_version", 1, keys.keyVersion)
    }

    @Test
    fun `keysFor answers a copy of every byte member`() {
        val id = register("alice", 1)
        stored(storeKeys(id, bundle()))
        presetPrev(temp, id, KeyPatterns.prevWrappedDek())
        val keys = checkNotNull(keysFor(id))
        scribble(keys.wrappedDek)
        scribble(keys.wrappedBoxPrivkey)
        scribble(keys.boxPubkey)
        scribble(keys.prevWrappedDek)
        assertArrayEquals("sync-api: wrapped_dek copy", KeyPatterns.wrappedDek(), keys.wrappedDek)
        assertArrayEquals("sync-api: wrapped_box_privkey copy", KeyPatterns.wrappedBoxPrivkey(), keys.wrappedBoxPrivkey)
        assertArrayEquals("sync-api: box_pubkey copy", KeyPatterns.boxPubkey(), keys.boxPubkey)
        assertArrayEquals("sync-api: prev_wrapped_dek copy", KeyPatterns.prevWrappedDek(), keys.prevWrappedDek)
    }

    @Test
    fun `keysFor with prev_wrapped_dek rekeyed_at and reset_at preset answers all three`() {
        val id = register("alice", 1)
        stored(storeKeys(id, bundle()))
        presetPrev(temp, id, KeyPatterns.prevWrappedDek())
        runSql(temp, "UPDATE accounts SET rekeyed_at = ${KeyPatterns.REKEYED_AT_MS}, reset_at = ${KeyPatterns.RESET_AT_MS} WHERE id = $id")
        val expectedRekeyed: Long? = KeyPatterns.REKEYED_AT_MS
        val expectedReset: Long? = KeyPatterns.RESET_AT_MS
        val keys = checkNotNull(keysFor(id))
        assertArrayEquals("sync-api: prev_wrapped_dek", KeyPatterns.prevWrappedDek(), keys.prevWrappedDek)
        assertEquals("sync-api: rekeyed_at", expectedRekeyed, keys.rekeyedAtMs)
        assertEquals("sync-api: reset_at", expectedReset, keys.resetAtMs)
    }

    @Test
    fun `keysFor answers a preset key_version`() {
        val id = register("alice", 1)
        stored(storeKeys(id, bundle()))
        runSql(temp, "UPDATE accounts SET key_version = 5 WHERE id = $id")
        assertEquals("sync-api: key_version", 5, checkNotNull(keysFor(id)).keyVersion)
    }

    @Test
    fun `keysFor an id with no account answers null`() {
        val id = register("alice", 1)
        assertNull("sync-api: keysFor an unknown id", keysFor(id + 1000L))
    }

    @Test
    fun `KeyBundle refuses a wrong size in each member and its message holds no value`() {
        val cases = listOf(0 to 59, 0 to 61, 1 to 59, 1 to 61, 2 to 31, 2 to 33, 2 to 59, 2 to 61)
        for ((member, size) in cases) {
            val bad = ByteArray(size) { i -> (i + 100).toByte() }
            val failure = TestDatabases.expectFailure<IllegalArgumentException>("KeyBundle with $size bytes in member $member") {
                bundleWith(member, size)
            }
            val message = failure.message.orEmpty()
            val expected = if (member == 2) "32" else "60"
            val field = listOf("wrapped_dek", "wrapped_box_privkey", "box_pubkey")[member]
            assertTrue("sync-api: the message must name the field $field: $message", message.contains(field))
            assertTrue("sync-api: the message must name the size $expected: $message", message.contains(expected))
            assertFalse("sync-api: the message holds the base64 of the value", message.contains(KeyPatterns.text(bad)))
            assertFalse(
                "sync-api: the message holds the value as text",
                message.contains(String(bad, StandardCharsets.ISO_8859_1)),
            )
            assertFalse("sync-api: the message holds the value as numbers", message.contains("100, 101"))
        }
    }

    @Test
    fun `KeyBundle copies its inputs and answers copies`() {
        val dek = KeyPatterns.wrappedDek()
        val privkey = KeyPatterns.wrappedBoxPrivkey()
        val pubkey = KeyPatterns.boxPubkey()
        val built = KeyBundle(dek, privkey, pubkey)
        scribble(dek)
        scribble(privkey)
        scribble(pubkey)
        scribble(built.wrappedDek)
        scribble(built.wrappedBoxPrivkey)
        scribble(built.boxPubkey)
        val id = register("alice", 1)
        stored(storeKeys(id, built))
        assertStoredPatterns(id, 1, "the bundle after its arrays were changed")
    }

    @Test
    fun `KeyBundle and StoredKeys print no key value`() {
        val id = register("alice", 1)
        val built = bundle()
        assertEquals("sync-api: KeyBundle text", "KeyBundle", built.toString())
        stored(storeKeys(id, built))
        assertEquals("sync-api: StoredKeys text", "StoredKeys(keyVersion=1)", checkNotNull(keysFor(id)).toString())
    }
}
