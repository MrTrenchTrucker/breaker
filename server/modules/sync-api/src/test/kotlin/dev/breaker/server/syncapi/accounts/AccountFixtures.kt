package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TestDatabases
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking

internal object AccountFixtures {
    const val FAST_ITERATIONS = 1000
    const val FIXED_MILLIS = 1_700_000_000_000L

    /** Known answer, computed outside this code: PBKDF2-HMAC-SHA256 of [fixturePassword] with [fixtureSalt], 1000 iterations. */
    const val FIXTURE_HASH_HEX = "47b2cefd2c9ca602722ecd29c3ca7ce2c98d6abadc9ff6364573da147443d234"

    val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC)
    val validKdf = KdfParams(65536, 3, 1)

    fun fastHasher(): VerifierHasher = VerifierHasher(iterations = FAST_ITERATIONS)

    fun fixturePassword(): ByteArray = ByteArray(32) { index -> index.toByte() }

    fun fixtureSalt(): ByteArray = ByteArray(16) { index -> (index + 16).toByte() }

    // Derived from the seed, never random: a failing run must be reproducible. The odd
    // multipliers make different seeds give different bytes.
    fun verifierBytes(seed: Int): ByteArray = ByteArray(32) { index -> (seed * 31 + index * 7 + 1).toByte() }

    fun verifier(seed: Int): AuthVerifier =
        checkNotNull(AuthVerifier.fromBytes(verifierBytes(seed))) { "sync-api test helper: verifier bytes rejected" }

    fun salt(seed: Int): ByteArray = ByteArray(16) { index -> (seed * 17 + index * 5 + 3).toByte() }

    fun newStore(temp: TempDatabase, hasher: VerifierHasher = fastHasher()): AccountStore =
        AccountStore(temp.db, hasher, fixedClock)

    fun registered(result: RegisterResult): RegisterResult.Registered {
        if (result !is RegisterResult.Registered) {
            throw AssertionError("expected Registered but got $result")
        }
        return result
    }

    fun found(lookup: SaltLookup): SaltLookup.Found {
        if (lookup !is SaltLookup.Found) {
            throw AssertionError("expected Found but got $lookup")
        }
        return lookup
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { value -> "%02x".format(value.toInt() and 0xff) }

    fun fromHex(text: String): ByteArray {
        require(text.length % 2 == 0) { "sync-api test helper: odd hex length" }
        return ByteArray(text.length / 2) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    /** A copy of [bytes] with the lowest bit of the byte at [index] flipped. */
    fun flipBit(bytes: ByteArray, index: Int): ByteArray {
        val copy = bytes.copyOf()
        copy[index] = (copy[index].toInt() xor 1).toByte()
        return copy
    }

    fun accountCount(temp: TempDatabase): Long = runBlocking {
        temp.db.transaction { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM accounts").single()
        }
    }

    // [column] is always a literal from the calling test, never input.
    private fun <T> readColumn(temp: TempDatabase, column: String, username: String, read: (ResultSet) -> T): T =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT $column FROM accounts WHERE username = ?").use { statement ->
                    statement.setString(1, username)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "sync-api test helper: no account named '$username'" }
                        read(rows)
                    }
                }
            }
        }

    fun bytesColumn(temp: TempDatabase, column: String, username: String): ByteArray =
        readColumn<ByteArray>(temp, column, username) { rows -> rows.getBytes(1) }

    fun textColumn(temp: TempDatabase, column: String, username: String): String =
        readColumn<String>(temp, column, username) { rows -> rows.getString(1) }

    fun longColumn(temp: TempDatabase, column: String, username: String): Long =
        readColumn<Long>(temp, column, username) { rows -> rows.getLong(1) }

    fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) {
            return false
        }
        for (start in 0..(haystack.size - needle.size)) {
            var same = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    same = false
                    break
                }
            }
            if (same) {
                return true
            }
        }
        return false
    }

    /** Names of the regular files directly inside [directory] whose bytes contain [needle]. */
    fun filesContaining(directory: Path, needle: ByteArray): List<String> {
        val hits = ArrayList<String>()
        Files.list(directory).use { entries ->
            for (entry in entries.toList()) {
                if (Files.isRegularFile(entry) && containsBytes(Files.readAllBytes(entry), needle)) {
                    hits.add(entry.fileName.toString())
                }
            }
        }
        return hits
    }

    fun filesIn(directory: Path): List<String> =
        Files.list(directory).use { entries -> entries.toList().map { entry -> entry.fileName.toString() } }
}
