package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.accounts.AccountFixtures.accountCount
import dev.breaker.server.syncapi.accounts.AccountFixtures.bytesColumn
import dev.breaker.server.syncapi.accounts.AccountFixtures.found
import dev.breaker.server.syncapi.accounts.AccountFixtures.longColumn
import dev.breaker.server.syncapi.accounts.AccountFixtures.registered
import dev.breaker.server.syncapi.accounts.AccountFixtures.salt
import dev.breaker.server.syncapi.accounts.AccountFixtures.textColumn
import dev.breaker.server.syncapi.accounts.AccountFixtures.validKdf
import dev.breaker.server.syncapi.accounts.AccountFixtures.verifier
import dev.breaker.server.syncapi.accounts.AccountFixtures.verifierBytes
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class AccountStoreRegisterTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun openStore() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
    }

    private fun register(
        name: String,
        seed: Int = 1,
        kdf: KdfParams = validKdf,
        kdfVersion: Int = 1,
        saltBytes: ByteArray = salt(seed),
    ): RegisterResult = runBlocking { store.register(name, saltBytes, kdf, kdfVersion, verifier(seed)) }

    @Test
    fun `the first account is the admin and the next two are users`() {
        assertEquals("first", RegisterResult.Registered(1, Role.ADMIN), register("one", 1))
        assertEquals("second", Role.USER, registered(register("two", 2)).role)
        assertEquals("third", Role.USER, registered(register("three", 3)).role)
    }

    @Test
    fun `refused first attempts do not use up the admin slot`() {
        val refusals = listOf(
            RegisterResult.KdfOutOfBounds to register("a1", kdf = KdfParams(65535, 3, 1)),
            RegisterResult.InvalidField("username") to register("a b"),
            RegisterResult.InvalidField("salt") to register("a3", saltBytes = ByteArray(15)),
            RegisterResult.InvalidField("kdf_version") to register("a4", kdfVersion = 0),
        )
        for ((expected, actual) in refusals) {
            assertEquals("refusal", expected, actual)
        }
        assertEquals("refusals must store nothing", 0L, accountCount(temp))
        assertEquals("the next valid account must still be the admin", Role.ADMIN, registered(register("real")).role)
    }

    @Test
    fun `a duplicate username is refused and the original account is untouched`() {
        registered(register("bob", 1))
        assertEquals("duplicate", RegisterResult.UsernameTaken, register("bob", 2, KdfParams(100000, 4, 2), 1))
        assertEquals("case-only duplicate", RegisterResult.UsernameTaken, register("BOB", 3))
        assertEquals("no row may be added", 1L, accountCount(temp))
        val original = found(runBlocking { store.saltFor("bob") })
        assertArrayEquals("the original salt must stay", salt(1), original.salt)
        assertEquals("the original params must stay", validKdf, original.kdf)
        assertEquals("the original account must stay the admin", "admin", textColumn(temp, "role", "bob"))
    }

    @Test
    fun `the username is stored as typed and its folded form is stored beside it`() {
        registered(register("Bob"))
        assertEquals("username", "Bob", textColumn(temp, "username", "Bob"))
        assertEquals("username_lower", "bob", textColumn(temp, "username_lower", "Bob"))
    }

    @Test
    fun `kdf params, kdf version, salt and creation time are stored exactly as given`() {
        registered(register("carol", 4, KdfParams(131072, 5, 2), 1))
        assertEquals("memory", 131072L, longColumn(temp, "kdf_memory_kib", "carol"))
        assertEquals("iterations", 5L, longColumn(temp, "kdf_iterations", "carol"))
        assertEquals("parallelism", 2L, longColumn(temp, "kdf_parallelism", "carol"))
        assertEquals("kdf version", 1L, longColumn(temp, "kdf_version", "carol"))
        assertArrayEquals("salt", salt(4), bytesColumn(temp, "salt", "carol"))
        assertEquals("created_at_ms", AccountFixtures.FIXED_MILLIS, longColumn(temp, "created_at_ms", "carol"))
    }

    @Test
    fun `every kdf field accepts its minimum and its maximum`() {
        val accepted = listOf(
            KdfParams(KdfBounds.MIN_MEMORY_KIB, 3, 1), KdfParams(KdfBounds.MAX_MEMORY_KIB, 3, 1),
            KdfParams(65536, KdfBounds.MIN_ITERATIONS, 1), KdfParams(65536, KdfBounds.MAX_ITERATIONS, 1),
            KdfParams(65536, 3, KdfBounds.MIN_PARALLELISM), KdfParams(65536, 3, KdfBounds.MAX_PARALLELISM),
        )
        for ((index, kdf) in accepted.withIndex()) {
            val result = register("ok$index", index + 1, kdf)
            assertTrue("$kdf must be accepted but got $result", result is RegisterResult.Registered)
        }
    }

    @Test
    fun `every kdf field refuses one below its minimum and one above its maximum`() {
        val refused = listOf(
            KdfParams(KdfBounds.MIN_MEMORY_KIB - 1, 3, 1), KdfParams(KdfBounds.MAX_MEMORY_KIB + 1, 3, 1),
            KdfParams(65536, KdfBounds.MIN_ITERATIONS - 1, 1), KdfParams(65536, KdfBounds.MAX_ITERATIONS + 1, 1),
            KdfParams(65536, 3, KdfBounds.MIN_PARALLELISM - 1), KdfParams(65536, 3, KdfBounds.MAX_PARALLELISM + 1),
        )
        for ((index, kdf) in refused.withIndex()) {
            assertEquals("$kdf", RegisterResult.KdfOutOfBounds, register("bad$index", index + 1, kdf))
        }
        assertEquals("refused params must store nothing", 0L, accountCount(temp))
    }

    @Test
    fun `invalid fields are named in the result`() {
        assertEquals("username", RegisterResult.InvalidField("username"), register(""))
        assertEquals("salt of 15 bytes", RegisterResult.InvalidField("salt"), register("s15", saltBytes = ByteArray(15)))
        assertEquals("salt of 17 bytes", RegisterResult.InvalidField("salt"), register("s17", saltBytes = ByteArray(17)))
        assertEquals("kdf_version 0", RegisterResult.InvalidField("kdf_version"), register("v0", kdfVersion = 0))
        assertEquals("kdf_version -1", RegisterResult.InvalidField("kdf_version"), register("v1", kdfVersion = -1))
        assertEquals("kdf_version 2", RegisterResult.InvalidField("kdf_version"), register("v2", kdfVersion = 2))
        assertEquals("kdf_version 1000", RegisterResult.InvalidField("kdf_version"), register("v3", kdfVersion = 1000))
        assertEquals("kdf_version max", RegisterResult.InvalidField("kdf_version"), register("v4", kdfVersion = Int.MAX_VALUE))
    }

    @Test
    fun `kdf version 1 is accepted and every other version is refused without storing anything`() {
        val accepted = register("ver", 1, kdfVersion = 1)
        assertTrue("version 1 must be accepted but got $accepted", accepted is RegisterResult.Registered)
        for (version in listOf(0, 2, 1000, Int.MAX_VALUE)) {
            assertEquals("version $version", RegisterResult.InvalidField("kdf_version"), register("other$version", 2, kdfVersion = version))
        }
        assertEquals("only the accepted row may exist", 1L, accountCount(temp))
    }

    @Test
    fun `a 65 character username is refused as invalid and a 64 character one is accepted`() {
        assertEquals("65 characters", RegisterResult.InvalidField("username"), register("a".repeat(65)))
        assertEquals("the refused name must store nothing", 0L, accountCount(temp))
        val accepted = register("a".repeat(64))
        assertTrue("64 characters must be accepted but got $accepted", accepted is RegisterResult.Registered)
        assertEquals("only the accepted name must be stored", 1L, accountCount(temp))
    }

    @Test
    fun `the verifier is stored only as a salted hash`() {
        registered(register("dave", 5))
        val raw = verifierBytes(5)
        val storedSalt = bytesColumn(temp, "verifier_salt", "dave")
        val storedHash = bytesColumn(temp, "verifier_hash", "dave")
        assertEquals("algo", VerifierHasher.ALGO, textColumn(temp, "verifier_algo", "dave"))
        assertEquals("iters", 1000L, longColumn(temp, "verifier_iters", "dave"))
        assertEquals("verifier salt size", 16, storedSalt.size)
        assertEquals("verifier hash size", 32, storedHash.size)
        assertFalse("the stored hash must not be the raw verifier", raw.contentEquals(storedHash))
        assertArrayEquals("the stored hash is the derivation", pbkdf2HmacSha256(raw, storedSalt, 1000), storedHash)
    }

    @Test
    fun `two accounts with the same verifier get different salts and hashes`() {
        val same = verifier(6)
        runBlocking {
            registered(store.register("erin", salt(1), validKdf, 1, same))
            registered(store.register("frank", salt(2), validKdf, 1, same))
        }
        for (column in listOf("verifier_salt", "verifier_hash")) {
            val first = bytesColumn(temp, column, "erin")
            val second = bytesColumn(temp, column, "frank")
            assertNotEquals("$column must differ", AccountFixtures.hex(first), AccountFixtures.hex(second))
        }
    }

    @Test
    fun `the raw verifier bytes appear in no file of the database directory`() {
        registered(register("gina", 7))
        temp.closeKeepingFiles()
        val files = AccountFixtures.filesIn(temp.directory)
        assertTrue("the database file must be there: $files", files.contains(temp.file.fileName.toString()))
        val verifierHits = AccountFixtures.filesContaining(temp.directory, verifierBytes(7))
        assertTrue("the raw verifier leaked into $verifierHits", verifierHits.isEmpty())
        // Control: the same search finds bytes that are stored, so it is able to fail.
        val saltHits = AccountFixtures.filesContaining(temp.directory, salt(7))
        assertTrue("the control search must find the stored salt in a file of $files", saltHits.isNotEmpty())
    }

    @Test
    fun `eight concurrent registrations give one admin and seven users`() {
        val results = runBlocking {
            withTimeout(30_000) {
                (1..8).map { n ->
                    async(Dispatchers.Default) { store.register("user$n", salt(n), validKdf, 1, verifier(n)) }
                }.awaitAll()
            }
        }
        val accounts = results.map { result -> registered(result) }
        assertEquals("admins", 1, accounts.count { it.role == Role.ADMIN })
        assertEquals("users", 7, accounts.count { it.role == Role.USER })
        assertEquals("rows", 8L, accountCount(temp))
    }
}
